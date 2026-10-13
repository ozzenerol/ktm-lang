module libc;

pub extern "c" i8 *getcwd(u8 *buf, u64 size);

pub extern "c" i8 *getenv(u8 *buf);

pub extern "c" i32 isatty(i32 fd);

/* C's stdio streams, as fprintf and fflush take them. printf writes to
 * stdout, so fprintf(libc.stdout, ...) stays in order with it; stderr is
 * unbuffered. */
pub struct FILE { u8 opaque; };

#ifdef IS_MACOS
pub _extern ___stdinp FILE *stdin;
pub _extern ___stdoutp FILE *stdout;
pub _extern ___stderrp FILE *stderr;
#else
pub _extern stdin FILE *stdin;
pub _extern stdout FILE *stdout;
pub _extern stderr FILE *stderr;
#endif

/* printf's format to a stream: fprintf(libc.stderr, "bad: %s\n", why) */
pub extern "c" i32 fprintf(FILE *stream, u8 *fmt, ...);
