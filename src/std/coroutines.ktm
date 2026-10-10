/* Asymmetric coroutines: each runs a function on its own stack and pauses
 * with CoroutineYield, handing a value back to the CoroutineNext that
 * resumed it; the next CoroutineNext resumes it and makes that Yield
 * return the value it sends.
 *
 * A coroutine remembers who resumed it, so coroutines can resume each
 * other; the running one is kept per thread in Fs() (except.ktm), and a
 * thread's own stack has a save slot there too (coro_root). On every
 * switch the thread's catch chain is swapped as well: each coroutine has
 * its own, so a try can span a yield, and a throw that leaves the
 * coroutine's function is rethrown by the CoroutineNext that resumed it.
 *
 * The only assembly is CoroSwap (save the callee-saved registers, switch
 * stacks, restore) and CoroEntry, where a new stack starts. */
#include "./defs.ktmh"

#define CORO_STACK_DEFAULT (256 * 1024)
#define CORO_CACHE_MAX     16  /* released stacks kept per thread */

#define CORO_READY     0  /* made, never resumed */
#define CORO_SUSPENDED 1  /* paused in CoroutineYield */
#define CORO_ACTIVE    2  /* running, or waiting in a CoroutineNext */
#define CORO_DONE      3  /* its function returned or threw */

#define CORO_PROT_NONE  0x0
#define CORO_PROT_READ  0x1
#define CORO_PROT_WRITE 0x2
#define CORO_MAP_PRIVATE 0x02
#ifdef IS_MACOS
#define CORO_MAP_ANONYMOUS 0x1000
#else
#define CORO_MAP_ANONYMOUS 0x20
#endif

extern "c" U0 *mmap(U0 *addr, U64 len, I32 prot, I32 flags, I32 fd, I64 offset);
extern "c" I32 munmap(U0 *addr, U64 len);
extern "c" I32 mprotect(U0 *addr, U64 len, I32 prot);
extern "c" I32 getpagesize();

public class Coroutine;
public class Coroutine
{
  U0 *sp;               /* saved stack pointer while not running */
  U0 *stack_base;       /* the mapping: guard page, then the stack */
  I64 stack_len;        /* bytes mapped, guard included */
  Coroutine *resumer;   /* who to go back to on Yield/return */
  U0 *(*fn)(U0 *arg);
  U0 *arg;
  CatchFrame *catch_top;/* this coroutine's catch chain while it is switched out */
  U64 except_ch;
  U64 thrown;           /* value of an uncaught throw, rethrown by Next */
  I64 status;
  Bool failed;          /* fn threw; Next rethrows `thrown` */
  Bool is_root;         /* the save slot of a thread's own stack */
};

#ifdef IS_X86_64
/* RDI = &from->sp, RSI = to->sp, RDX = value, returned in RAX on the
 * other side. Saves the SysV callee-saved registers plus MXCSR and the
 * x87 control word (raw bytes: the assembler has no mnemonics for them).
 * The saved rsp is 16-byte aligned. */
asm {
_CORO_SWAP::
    PUSH   RBP
    PUSH   RBX
    PUSH   R12
    PUSH   R13
    PUSH   R14
    PUSH   R15
    SUB    RSP, 8
    DU8    0x0F, 0xAE, 0x1C, 0x24   // STMXCSR [RSP]
    DU8    0xD9, 0x7C, 0x24, 0x04   // FNSTCW  4[RSP]
    MOV    [RDI], RSP
    MOV    RSP, RSI
    DU8    0x0F, 0xAE, 0x14, 0x24   // LDMXCSR [RSP]
    DU8    0xD9, 0x6C, 0x24, 0x04   // FLDCW   4[RSP]
    ADD    RSP, 8
    POP    R15
    POP    R14
    POP    R13
    POP    R12
    POP    RBX
    POP    RBP
    MOV    RAX, RDX
    RET

// A new stack's first CoroSwap returns here with RSP 16-byte aligned and
// the coroutine in RBX. CoroMain never returns.
_CORO_ENTRY::
    MOV    RDI, RBX
    CALL   CoroMain
    DU8    0x0F, 0x0B               // UD2
}
#endif

#ifdef IS_ARM_64
/* X0 = &from->sp, X1 = to->sp, X2 = value, returned in X0 on the other
 * side. Saves the AAPCS64 callee-saved registers: x19-x28, fp, lr and
 * d8-d15 (x18 is the platform register and is left alone). */
asm {
_CORO_SWAP::
    SUB    SP, SP, 160
    STP    X19, X20, [SP]
    STP    X21, X22, [SP, 16]
    STP    X23, X24, [SP, 32]
    STP    X25, X26, [SP, 48]
    STP    X27, X28, [SP, 64]
    STP    X29, X30, [SP, 80]
    STP    D8, D9, [SP, 96]
    STP    D10, D11, [SP, 112]
    STP    D12, D13, [SP, 128]
    STP    D14, D15, [SP, 144]
    ADD    X9, SP, 0
    STR    X9, [X0]
    ADD    SP, X1, 0
    LDP    X19, X20, [SP]
    LDP    X21, X22, [SP, 16]
    LDP    X23, X24, [SP, 32]
    LDP    X25, X26, [SP, 48]
    LDP    X27, X28, [SP, 64]
    LDP    X29, X30, [SP, 80]
    LDP    D8, D9, [SP, 96]
    LDP    D10, D11, [SP, 112]
    LDP    D12, D13, [SP, 128]
    LDP    D14, D15, [SP, 144]
    ADD    SP, SP, 160
    MOV    X0, X2
    RET

// A new stack's first CoroSwap returns here (via the saved lr) with the
// coroutine in X19. CoroMain never returns.
_CORO_ENTRY::
    MOV    X0, X19
    BL     CoroMain
    DU8    0x00, 0x00, 0x20, 0xD4   // BRK 0
}
#endif

/* A released stack in its thread's cache (Fs()->coro_cache). It lives
 * in the top bytes of the stack itself, so caching allocates nothing. */
class CoroCached;
class CoroCached
{
  CoroCached *next;
  I64 len;              /* bytes mapped, guard included */
};

/* A cached stack of len bytes (guard included), or NULL. */
static U0 *CoroStackGet(HCFs *fs, I64 len)
{
  CoroCached **link = &fs->coro_cache;
  CoroCached *n;
  for (n = *link; n; n = *link) {
    if (n->len == len) {
      *link = n->next;
      fs->coro_cached--;
      return n(U8 *) + sizeof(CoroCached) - len;
    }
    link = &n->next;
  }
  return NULL;
}

/* Keep a released stack for reuse; FALSE when the cache is full. */
static Bool CoroStackPut(HCFs *fs, U0 *base, I64 len)
{
  if (fs->coro_cached >= CORO_CACHE_MAX) return FALSE;
  CoroCached *n = (base + len - sizeof(CoroCached))(CoroCached *);
  n->len = len;
  n->next = fs->coro_cache;
  fs->coro_cache = n;
  fs->coro_cached++;
  return TRUE;
}

/* Unmap a thread's cached stacks: FsFree calls it when the thread ends. */
U0 CoroStacksFree(HCFs *fs)
{
  CoroCached *n = fs->coro_cache, *next;
  while (n) {
    next = n->next;
    munmap(n(U8 *) + sizeof(CoroCached) - n->len, n->len);
    n = next;
  }
  fs->coro_cache = NULL;
  fs->coro_cached = 0;
}

_extern _CORO_SWAP U0 *CoroSwap(U0 **save_sp, U0 *new_sp, U0 *value);
_extern _CORO_ENTRY U0 CoroEntry();

static U0 CoroDie(U8 *msg)
{
  Write(STDERR, msg, StrLen(msg));
  Exit(1);
}

/* The save slot for the calling thread's own stack. */
static Coroutine *CoroRoot(HCFs *fs)
{
  Coroutine *r = fs->coro_root;
  if (!r) {
    r = calloc(1, sizeof(Coroutine));
    if (!r) CoroDie("out of memory for the coroutine state\n");
    r->status = CORO_ACTIVE;
    r->is_root = TRUE;
    fs->coro_root = r;
  }
  return r;
}

/* Switch the thread from `from` to `to`, handing over `value`; returns
 * what is handed to `from` when something switches back to it. */
static U0 *CoroSwitch(HCFs *fs, Coroutine *from, Coroutine *to, U0 *value)
{
  from->catch_top = fs->catch_top;
  from->except_ch = fs->except_ch;
  fs->catch_top = to->catch_top;
  fs->except_ch = to->except_ch;
  if (to->is_root) fs->coro_cur = NULL;
  else             fs->coro_cur = to;
  return CoroSwap(&from->sp, to->sp, value);
}

/* Bottom of every coroutine stack (called from CoroEntry): run the
 * function, keep its result or its uncaught throw, go back for good. */
U0 CoroMain(Coroutine *c)
{
  U0 *result = NULL;
  try {
    result = c->fn(c->arg);
  } catch {
    c->failed = TRUE;
    c->thrown = Fs->except_ch;
  }
  Coroutine *to = c->resumer;
  c->resumer = NULL;
  c->status = CORO_DONE;
  CoroSwitch(Fs(), c, to, result);
  CoroDie("a finished coroutine was resumed\n");
}

/* A new coroutine that will run fn(arg) on its own stack of stack_size
 * bytes (0 = 256 KiB; rounded up to whole pages, at least 4), with an
 * inaccessible guard page below it so an overflow crashes instead of
 * writing over other memory. Nothing runs until the first CoroutineNext.
 * NULL if the stack can't be mapped. */
public Coroutine *CoroutineNew(U0 *(*fn)(U0 *arg), U0 *arg=NULL,
                               I64 stack_size=0)
{
  if (!fn) CoroDie("CoroutineNew: NULL function\n");
  I64 page = getpagesize();
  if (stack_size <= 0) stack_size = CORO_STACK_DEFAULT;
  if (stack_size < 4 * page) stack_size = 4 * page;
  stack_size = (stack_size + page - 1) & ~(page - 1);

  Coroutine *c = calloc(1, sizeof(Coroutine));
  if (!c) return NULL;
  c->stack_len = page + stack_size;
  c->stack_base = CoroStackGet(Fs(), c->stack_len);
  if (!c->stack_base) {
    c->stack_base = mmap(NULL, c->stack_len, CORO_PROT_READ|CORO_PROT_WRITE,
        CORO_MAP_PRIVATE|CORO_MAP_ANONYMOUS, -1, 0);
    if (c->stack_base(I64) == -1) { /* MAP_FAILED */
      free(c);
      return NULL;
    }
    if (mprotect(c->stack_base, page, CORO_PROT_NONE) != 0) {
      munmap(c->stack_base, c->stack_len);
      free(c);
      return NULL;
    }
  }
  c->fn = fn;
  c->arg = arg;
  c->status = CORO_READY;

  /* A frame as CoroSwap leaves it, so the first switch "returns" into
   * CoroEntry with the coroutine in a callee-saved register. A reused
   * stack holds old data, so the frame is zeroed first. */
  U64 *top = (c->stack_base + c->stack_len)(U64 *);
#ifdef IS_X86_64
  U64 *sp = top - 8;
  MemSet(sp, 0, 8 * sizeof(U64));
  sp[0] = 0x037F00001F80;      /* MXCSR 0x1F80, x87 control word 0x37F */
  sp[5] = c(U64);              /* RBX */
  sp[7] = &CoroEntry(U64);     /* return address */
#endif
#ifdef IS_ARM_64
  U64 *sp = top - 20;
  MemSet(sp, 0, 20 * sizeof(U64));
  sp[0] = c(U64);              /* X19 */
  sp[11] = &CoroEntry(U64);    /* X30 (lr); X29 (fp) stays 0 */
#endif
  c->sp = sp;
  return c;
}

/* Run c until it yields or returns, and return the value it yielded or,
 * once its function has returned (CoroutineDone(c) is then TRUE), the
 * function's return value. `send` becomes the return value of the
 * CoroutineYield that c is paused in; the first CoroutineNext ignores it
 * (the function gets its arg). If c's function throws, the throw goes on
 * from here. NULL if c is already done. */
public U0 *CoroutineNext(Coroutine *c, U0 *send=NULL)
{
  if (!c) CoroDie("CoroutineNext: NULL coroutine\n");
  if (c->status == CORO_DONE) return NULL;
  if (c->status == CORO_ACTIVE)
    CoroDie("CoroutineNext: the coroutine is already running\n");
  HCFs *fs = Fs();
  Coroutine *from = fs->coro_cur;
  if (!from) from = CoroRoot(fs);
  c->resumer = from;
  c->status = CORO_ACTIVE;
  U0 *v = CoroSwitch(fs, from, c, send);
  if (c->failed) {
    c->failed = FALSE;
    throw(c->thrown);
  }
  return v;
}

/* Inside a coroutine: pause it and hand value to the CoroutineNext that
 * resumed it. Returns the `send` of the CoroutineNext that resumes it
 * again. */
public U0 *CoroutineYield(U0 *value=NULL)
{
  HCFs *fs = Fs();
  Coroutine *c = fs->coro_cur;
  if (!c) CoroDie("CoroutineYield: not inside a coroutine\n");
  Coroutine *to = c->resumer;
  c->resumer = NULL;
  c->status = CORO_SUSPENDED;
  return CoroSwitch(fs, c, to, value);
}

/* TRUE once c's function has returned or thrown (also for NULL). */
public Bool CoroutineDone(Coroutine *c)
{
  return !c || c->status == CORO_DONE;
}

/* The coroutine that is running, NULL on a thread's own stack. */
public Coroutine *CoroutineSelf()
{
  return Fs()->coro_cur;
}

/* Free c. Its stack goes to the calling thread's cache for the next
 * CoroutineNew of the same size (at most CORO_CACHE_MAX stacks),
 * otherwise it is unmapped. A coroutine paused in CoroutineYield is
 * dropped where it is: whatever it allocated is not freed. c must not
 * be running. NULL is ignored. */
public U0 CoroutineRelease(Coroutine *c)
{
  if (!c) return;
  if (c->status == CORO_ACTIVE)
    CoroDie("CoroutineRelease: the coroutine is running\n");
  if (!CoroStackPut(Fs(), c->stack_base, c->stack_len))
    munmap(c->stack_base, c->stack_len);
  free(c);
}
