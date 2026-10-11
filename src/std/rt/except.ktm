module rt;

/* strings.ktm */

/* Compiler reserves 256 bytes per CatchFrame. We split that into a
 * 248-byte jmp_buf storage region (setjmp owns the format) plus the
 * 8-byte chain pointer the runtime walks. */
#define __JMP_BUF_BYTES__ 248

public class CatchFrame;

public class CatchFrame
{
  I8 jb[__JMP_BUF_BYTES__];
  CatchFrame *prev;
};

public class HCFs
{
  CatchFrame *catch_top;  /* head of the active-try-frames chain */
  U64 except_ch;          /* code of the most recent throw */
  U0 *coro_cur;           /* running Coroutine, NULL on the thread's own stack */
  U0 *coro_root;          /* save slot for the thread's own stack (coroutines.ktm) */
  U0 *coro_cache;         /* released coroutine stacks kept for reuse */
  I64 coro_cached;        /* how many */
  Error error;            /* the error being thrown, until a catch takes it */
};

/* The "" of an Error without a message (or file): never freed. */
static U8 _err_none[8];

static Bool ErrOwns(U8 *message)
{
  return message && message != _err_none;
}

/* coroutines.ktm */

/* pthread_once_t is an int on Linux and a signed struct on macOS
 * (PTHREAD_ONCE_INIT is {0x30B1BCBA, {0}}); pthread_key_t is 4 bytes on
 * Linux and 8 on macOS, so 8 zeroed bytes hold either. */
#ifdef IS_MACOS
static I64 _fs_once[2] = {0x30B1BCBA, 0};
#else
static I64 _fs_once[2] = {0, 0};
#endif

static U64 _fs_key = 0;

static U0 FsFree(U0 *p)
{
  HCFs *fs = p;
  if (ErrOwns(fs->error.message)) Free(fs->error.message);
  CoroStacksFree(fs);
  free(fs->coro_root);
  free(fs);
}

static U0 FsKeyCreate()
{
  pthread_key_create(&_fs_key, &FsFree);
}

/* The calling thread's HCFs, created on its first use. */
public HCFs *Fs()
{
  pthread_once(_fs_once, &FsKeyCreate);
  HCFs *fs = pthread_getspecific(_fs_key);
  if (!fs) {
    fs = calloc(1, sizeof(HCFs));
    if (!fs) {
      "out of memory for the exception state\n";
      Exit(1);
    }
    pthread_setspecific(_fs_key, fs);
  }
  return fs;
}

/* Push the frame onto the catch-stack. The setjmp call itself MUST
 * stay inline in the user function (it captures the caller's frame,
 * and longjmp to a returned function's frame is UB), so the chain
 * mutation is the only thing we delegate to a helper. */
public U0 KTM_PushFrame(CatchFrame *f)
{
  HCFs *fs = Fs();
  f->prev = fs->catch_top;
  fs->catch_top = f;
}

public U0 KTM_TryLeave(CatchFrame *f)
{
  Fs()->catch_top = f->prev;
}

/* Make e (a struct copy) the thread's current error; the message moves
 * in, the one there before is freed. */
static U0 ErrSet(HCFs *fs, U64 code, U8 *message, U8 *file, I64 line)
{
  if (ErrOwns(fs->error.message)) Free(fs->error.message);
  fs->error.code = code;
  fs->error.message = message ? message : _err_none;
  fs->error.file = file ? file : _err_none;
  fs->error.line = line;
  fs->except_ch = code;
}

static U0 WriteErr(U8 *s)
{
  I64 n = 0;
  while (s[n]) n++;
  Write(STDERR, s, n);
}

/* Report an error no try catches on stderr and exit with 1:
 *   uncaught error <code>: <message> (<file>:<line>)
 * A code that is a character constant whose bytes are all printable is
 * shown as text ('Late'), any other as a signed decimal number. */
static U0 KTM_Uncaught(HCFs *fs)
{
  U8 buf[64], digits[24];
  I64 len = 0, n = 0, i;
  U64 v = fs->error.code, u, c;
  Bool text = v != 0;

  for (u = v; u; u >>= 8) {
    c = u & 0xFF;
    if (c < ' ' || c > '~') text = FALSE;
  }
  if (text) {
    buf[len++] = '\'';
    for (u = v; u; u >>= 8) buf[len++] = u & 0xFF;
    buf[len++] = '\'';
  } else {
    if (v(I64) < 0) {
      buf[len++] = '-';
      u = -v(I64);
    } else {
      u = v;
    }
    do {
      digits[n++] = '0' + u % 10;
      u /= 10;
    } while (u);
    for (i = n - 1; i >= 0; i--) buf[len++] = digits[i];
  }
  buf[len] = 0;
  fflush(NULL);  /* what the program printed comes first */
  WriteErr("uncaught error ");
  WriteErr(buf);
  if (fs->error.message && fs->error.message[0]) {
    WriteErr(": ");
    WriteErr(fs->error.message);
  }
  if (fs->error.file && fs->error.file[0]) {
    WriteErr(" (");
    WriteErr(fs->error.file);
    len = 0;
    n = 0;
    u = fs->error.line;
    buf[len++] = ':';
    do {
      digits[n++] = '0' + u % 10;
      u /= 10;
    } while (u);
    for (i = n - 1; i >= 0; i--) buf[len++] = digits[i];
    buf[len++] = ')';
    buf[len] = 0;
    WriteErr(buf);
  }
  WriteErr("\n");
  Exit(1);
}

static U0 ErrJump(HCFs *fs)
{
  auto t = fs->catch_top;
  if (!t) KTM_Uncaught(fs);
  _longjmp(t->jb, 1);
}

/* `throw(code)`: the compiler passes where the throw is. */
public U0 KTM_ThrowAt(U64 code, U8 *file, I64 line)
{
  HCFs *fs = Fs();
  ErrSet(fs, code, NULL, file, line);
  ErrJump(fs);
}

/* `throw(code, fmt, ...)`: the compiler checks the call as one to
 * KTM_ThrowFmt, then calls this with where the throw is in front. */
public U0 KTM_ThrowFmtAt(U8 *file, I64 line, U64 code, U8 *fmt, ...)
{
  U8 *message = StrPrintJoin(NULL, fmt, argc, argv);
  HCFs *fs = Fs();
  ErrSet(fs, code, message, file, line);
  ErrJump(fs);
}

public U0 KTM_ThrowFmt(U64 code, U8 *fmt, ...)
{
  U8 *message = StrPrintJoin(NULL, fmt, argc, argv);
  HCFs *fs = Fs();
  ErrSet(fs, code, message, NULL, 0);
  ErrJump(fs);
}

/* `throw(e)`: the same error again, with a copy of its message (the
 * catch that holds e frees its own when it is left). */
public U0 KTM_ThrowError(Error *e)
{
  U8 *message = ErrOwns(e->message) ? StrNew(e->message) : NULL;
  HCFs *fs = Fs();
  ErrSet(fs, e->code, message, e->file, e->line);
  ErrJump(fs);
}

public U0 KTM_Throw(U64 v)
{
  KTM_ThrowAt(v, NULL, 0);
}

/* `catch (Error e)`: move the current error into e. */
public U0 KTM_ErrorTake(Error *e)
{
  HCFs *fs = Fs();
  e->code = fs->error.code;
  e->message = fs->error.message ? fs->error.message : _err_none;
  e->file = fs->error.file ? fs->error.file : _err_none;
  e->line = fs->error.line;
  fs->error.message = _err_none;
}

/* Free e's message (if it has one of its own). */
public U0 KTM_ErrorDrop(Error *e)
{
  if (ErrOwns(e->message)) Free(e->message);
  e->message = _err_none;
}

/* dst = src, with its own copy of the message. */
public U0 KTM_ErrorCopy(Error *dst, Error *src)
{
  dst->code = src->code;
  dst->message = ErrOwns(src->message) ? StrNew(src->message) : _err_none;
  dst->file = src->file ? src->file : _err_none;
  dst->line = src->line;
}

/* A catch without a binding: nothing can read the message, free it. */
public U0 KTM_ErrorForget()
{
  HCFs *fs = Fs();
  if (ErrOwns(fs->error.message)) Free(fs->error.message);
  fs->error.message = _err_none;
}

/* A defer caught a throw leaving its block: make the try that was active
 * before f the innermost again and move the error into *saved, out of the
 * way of throws caught inside the deferred statement. */
public U0 KTM_DeferCaught(CatchFrame *f, Error *saved)
{
  HCFs *fs = Fs();
  fs->catch_top = f->prev;
  KTM_ErrorTake(saved);
}

/* After the deferred statement: *saved is the current error again (its
 * message moves back) and goes on to the next frame out. */
public U0 KTM_Rethrow(Error *saved)
{
  HCFs *fs = Fs();
  U8 *message = saved->message;
  saved->message = _err_none;
  ErrSet(fs, saved->code, message, saved->file, saved->line);
  ErrJump(fs);
}
