module coro;
import cstr, libc, mem, os, rt;

const i64 CORO_STACK_DEFAULT = (256 * 1024);

const i64 CORO_CACHE_MAX = 16;  /* released stacks kept per thread */

const i64 CORO_READY = 0;  /* made, never resumed */

const i64 CORO_SUSPENDED = 1;  /* paused in coro.yield */

const i64 CORO_ACTIVE = 2;  /* running, or waiting in a coro.next */

const i64 CORO_DONE = 3;  /* its function returned or threw */

const i64 CORO_PROT_NONE = 0x0;

const i64 CORO_PROT_READ = 0x1;

const i64 CORO_PROT_WRITE = 0x2;

const i64 CORO_MAP_PRIVATE = 0x02;

#ifdef IS_MACOS
const i64 CORO_MAP_ANONYMOUS = 0x1000;
#else
const i64 CORO_MAP_ANONYMOUS = 0x20;
#endif

pub struct Coroutine;

pub struct Coroutine {
    void *sp;               /* saved stack pointer while not running */
    void *stackBase;       /* the mapping: guard page, then the stack */
    i64 stackLen;        /* bytes mapped, guard included */
    Coroutine *resumer;   /* who to go back to on Yield/return */
    fn(void *arg) void *body;
    void *arg;
    rt.CatchFrame *catchTop;/* this coroutine's catch chain while it is switched out */
    u64 exceptCh;
    Error error;          /* its error in flight while it is switched out */
    Error thrown;         /* an uncaught error, rethrown by Next */
    i64 status;
    bool failed;          /* fn threw; Next rethrows `thrown` */
    bool isRoot;         /* the save slot of a thread's own stack */
    void *loopTask;      /* its LoopTask when coro.go started it (loop.ktm) */
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
// the coroutine in RBX. coro.coroMain (its symbol: modules mangle their
// names) never returns.
_CORO_ENTRY::
        MOV    RDI, RBX
        CALL   _K4coro8coroMain
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
// coroutine in X19. coro.coroMain (its symbol) never returns.
_CORO_ENTRY::
        MOV    X0, X19
        BL     _K4coro8coroMain
        DU8    0x00, 0x00, 0x20, 0xD4   // BRK 0
}
#endif

/* A cached stack of len bytes (guard included), or null. */
static void *coroStackGet(rt.HCFs *fs, i64 len) {
    rt.CoroCached **link = &fs.coroCache;
    rt.CoroCached *n;
    for (n = *link; n; n = *link) {
        if (n.len == len) {
            *link = n.next;
            fs.coroCached--;
            return (u8 *)n + sizeof(rt.CoroCached) - len;
        }
        link = &n.next;
    }
    return null;
}

/* Keep a released stack for reuse; false when the cache is full. */
static bool coroStackPut(rt.HCFs *fs, void *base, i64 len) {
    if (fs.coroCached >= CORO_CACHE_MAX) { return false; }
    rt.CoroCached *n = (rt.CoroCached *)(base + len - sizeof(rt.CoroCached));
    n.len = len;
    n.next = fs.coroCache;
    fs.coroCache = n;
    fs.coroCached++;
    return true;
}

_extern _CORO_SWAP void *coroSwap(void **saveSp, void *newSp, void *value);

_extern _CORO_ENTRY void coroEntry();

static void coroDie(u8 *msg) {
    libc.write(os.STDERR, msg, cstr.len(msg));
    os.exit(1);
}

/* The save slot for the calling thread's own stack. */
static Coroutine *coroRoot(rt.HCFs *fs) {
    Coroutine *r = fs.coroRoot;
    if (!r) {
        r = libc.calloc(1, sizeof(Coroutine));
        if (!r) { coroDie("out of memory for the coroutine state\n"); }
        r.status = CORO_ACTIVE;
        r.isRoot = true;
        fs.coroRoot = r;
    }
    return r;
}

/* Switch the thread from `from` to `to`, handing over `value`; returns
 * what is handed to `from` when something switches back to it. */
static void *coroSwitch(rt.HCFs *fs, Coroutine *from, Coroutine *to, void *value) {
    from.catchTop = fs.catchTop;
    from.exceptCh = fs.exceptCh;
    from.error = fs.error;
    fs.catchTop = to.catchTop;
    fs.exceptCh = to.exceptCh;
    fs.error = to.error;
    if (to.isRoot) { fs.coroCur = null; }
    else             { fs.coroCur = to; }
    return coroSwap(&from.sp, to.sp, value);
}

/* Bottom of every coroutine stack (called from CoroEntry): run the
 * function, keep its result or its uncaught throw, go back for good. */
void coroMain(Coroutine *c) {
    void *result = null;
    try {
        result = c.body(c.arg);
    } catch (Error e) {
        c.failed = true;
        rt.__ktmErrorCopy(&c.thrown, &e);
    }
    Coroutine *to = c.resumer;
    c.resumer = null;
    c.status = CORO_DONE;
    coroSwitch(rt.Fs(), c, to, result);
    coroDie("a finished coroutine was resumed\n");
}

/* A new coroutine that will run fn(arg) on its own stack of stack_size
 * bytes (0 = 256 KiB; rounded up to whole pages, at least 4), with an
 * inaccessible guard page below it so an overflow crashes instead of
 * writing over other memory. Nothing runs until the first coro.next.
 * null if the stack can't be mapped. */
pub Coroutine *spawn(fn(void *arg) void *body, void *arg=null,
                                                             i64 stackSize=0) {
    if (!body) { coroDie("CoroutineNew: null function\n"); }
    i64 page = libc.getpagesize();
    if (stackSize <= 0) { stackSize = CORO_STACK_DEFAULT; }
    if (stackSize < 4 * page) { stackSize = 4 * page; }
    stackSize = (stackSize + page - 1) & ~(page - 1);

    Coroutine *c = libc.calloc(1, sizeof(Coroutine));
    if (!c) { return null; }
    c.stackLen = page + stackSize;
    c.stackBase = coroStackGet(rt.Fs(), c.stackLen);
    if (!c.stackBase) {
        c.stackBase = libc.mmap(null, c.stackLen, CORO_PROT_READ|CORO_PROT_WRITE,
                CORO_MAP_PRIVATE|CORO_MAP_ANONYMOUS, -1, 0);
        if ((i64)c.stackBase == -1) { /* MAP_FAILED */
            libc.free(c);
            return null;
        }
        if (libc.mprotect(c.stackBase, page, CORO_PROT_NONE) != 0) {
            libc.munmap(c.stackBase, c.stackLen);
            libc.free(c);
            return null;
        }
    }
    c.body = body;
    c.arg = arg;
    c.status = CORO_READY;

    /* A frame as CoroSwap leaves it, so the first switch "returns" into
     * CoroEntry with the coroutine in a callee-saved register. A reused
     * stack holds old data, so the frame is zeroed first. */
    u64 *top = (u64 *)(c.stackBase + c.stackLen);
#ifdef IS_X86_64
    u64 *sp = top - 8;
    mem.set(sp, 0, 8 * sizeof(u64));
    sp[0] = 0x037F00001F80;      /* MXCSR 0x1F80, x87 control word 0x37F */
    sp[5] = (u64)c;              /* RBX */
    sp[7] = (u64)&coroEntry;     /* return address */
#endif
#ifdef IS_ARM_64
    u64 *sp = top - 20;
    mem.set(sp, 0, 20 * sizeof(u64));
    sp[0] = (u64)c;              /* X19 */
    sp[11] = (u64)&coroEntry;    /* X30 (lr); X29 (fp) stays 0 */
#endif
    c.sp = sp;
    return c;
}

/* Run c until it yields or returns, and return the value it yielded or,
 * once its function has returned (coro.done(c) is then true), the
 * function's return value. `send` becomes the return value of the
 * coro.yield that c is paused in; the first coro.next ignores it
 * (the function gets its arg). If c's function throws, the throw goes on
 * from here. null if c is already done. */
pub void *next(Coroutine *c, void *send=null) {
    if (!c) { coroDie("CoroutineNext: null coroutine\n"); }
    if (c.status == CORO_DONE) { return null; }
    if (c.status == CORO_ACTIVE) {
        coroDie("CoroutineNext: the coroutine is already running\n");
    }
    rt.HCFs *fs = rt.Fs();
    Coroutine *from = fs.coroCur;
    if (!from) { from = coroRoot(fs); }
    c.resumer = from;
    c.status = CORO_ACTIVE;
    void *v = coroSwitch(fs, from, c, send);
    if (c.failed) {
        c.failed = false;
        rt.__ktmRethrow(&c.thrown);
    }
    return v;
}

/* Inside a coroutine: pause it and hand value to the coro.next that
 * resumed it. Returns the `send` of the coro.next that resumes it
 * again. */
pub void *yield(void *value=null) {
    rt.HCFs *fs = rt.Fs();
    Coroutine *c = fs.coroCur;
    if (!c) { coroDie("CoroutineYield: not inside a coroutine\n"); }
    Coroutine *to = c.resumer;
    c.resumer = null;
    c.status = CORO_SUSPENDED;
    return coroSwitch(fs, c, to, value);
}

/* true once c's function has returned or thrown (also for null). */
pub bool done(Coroutine *c) {
    return !c || c.status == CORO_DONE;
}

/* The coroutine that is running, null on a thread's own stack. */
pub Coroutine *current() {
    return rt.Fs().coroCur;
}

/* Free c. Its stack goes to the calling thread's cache for the next
 * coro.spawn of the same size (at most CORO_CACHE_MAX stacks),
 * otherwise it is unmapped. A coroutine paused in coro.yield is
 * dropped where it is: whatever it allocated is not freed. c must not
 * be running. null is ignored. */
pub void release(Coroutine *c) {
    if (!c) { return; }
    if (c.status == CORO_ACTIVE) {
        coroDie("CoroutineRelease: the coroutine is running\n");
    }
    rt.__ktmErrorDrop(&c.error);
    if (!coroStackPut(rt.Fs(), c.stackBase, c.stackLen)) {
        libc.munmap(c.stackBase, c.stackLen);
    }
    libc.free(c);
}
