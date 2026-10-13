module log;
import cstr, fs, libc, mem, os, str;

/* Leveled logging to stderr or a file, one minimal line per call:
 *
 *   2026-10-13T14:02:11.482Z INFO listening on :8080
 *   2026-10-13T14:02:11.482Z WARN main.ktm:42 serve: slow request
 *
 * The time is UTC with milliseconds; file:line and the function are there
 * when Options.showFile / showFunc ask for them. A file rotates by size
 * (app.log -> app.log.1 -> ... -> app.log.<keep>). With threadSafe one
 * mutex covers formatting the time, rotating and writing, so lines from
 * several threads never mix. */

/* The code log.open throws when the file can't be opened */
pub const i64 ERR_LOG = 0x6B746D16;

/* The least important first: a Logger writes its level and the ones after */
pub enum Level { debug, info, warn, error };

/* How log.open sets a Logger up. Start from log.options() (a declared
 * Options isn't zeroed), then change what you need. */
pub struct Options {
    u8 *path;          /* the file, appended to; null for stderr */
    bool toStdout;     /* stdout: instead of stderr, or as well as the file */
    bool threadSafe;   /* a mutex around each line, for loggers threads share */
    i64 maxBytes;      /* rotate before a line would take the file past it; 0 never */
    i64 keep;          /* rotated files kept: path.1 (newest) to path.<keep> */
    bool showFile;     /* the call's file name and line */
    bool showFunc;     /* the function the call is in */
    Level level;       /* the least important level written */
};

/* An open log: make one with log.open, write with lg.info(...) and the
 * others, end it with lg.close(). `level` can be changed at any time; the
 * other fields are read-only outside module log. */
pub struct Logger {
    Options opts;
    Level level;
    i32 fd;
    i64 size;          /* bytes in the file now */
    i64 failed;        /* lines a failed write or rotation dropped */
    libc.pthread_mutex_t lock;
};

extern "c" libc.tm *gmtime_r(i64 *t, libc.tm *out);

u8 *gLogLevelNames[4] = { "DEBUG", "INFO", "WARN", "ERROR" };

/* The defaults: stderr, not thread safe, no rotation, INFO and up, no
 * file or function */
pub Options options()
{
    Options o;
    libc.memset(&o, 0, sizeof(Options));
    o.level = Level.info;
    return o;
}

static i32 logOpenFile(u8 *path, bool truncate)
{
    i32 flags = libc.O_CREAT | libc.O_WRONLY | (truncate ? libc.O_TRUNC : libc.O_APPEND);
    return libc.open(path, flags, 0x1A4);   /* rw-r--r-- */
}

/* A Logger as `o` says (o is copied; path too). Throws log.ERR_LOG when
 * the file can't be opened, with the reason. */
pub Logger *open(Options *o)
{
    Logger *lg = mem.alloc(sizeof(Logger));
    libc.memset(lg, 0, sizeof(Logger));
    lg.opts = *o;
    lg.level = o.level;
    if (lg.opts.maxBytes < 0) { lg.opts.maxBytes = 0; }
    if (lg.opts.keep < 0) { lg.opts.keep = 0; }
    if (!o.path) {
        lg.fd = o.toStdout ? 1 : 2;
    } else {
        lg.opts.path = cstr.dup(o.path);
        lg.fd = logOpenFile(lg.opts.path, false);
        if (lg.fd < 0) {
            i32 err = os.errno();
            mem.free(lg.opts.path);
            mem.free(lg);
            throw(ERR_LOG, "log.open: can't open %s: %s", o.path, libc.strerror(err));
        }
        fs.FileStat st;
        if (fs.fstat(&st, lg.fd) == 0) { lg.size = st.st_size; }
    }
    if (lg.opts.threadSafe) { libc.pthread_mutex_init(&lg.lock, null); }
    return lg;
}

/* app.log.<n> */
static String logRotatedName(u8 *path, i64 n)
{
    return str.format("%s.%d", path, n);
}

/* app.log.<keep-1> .. app.log.1 move up one, app.log becomes app.log.1
 * and a new app.log is started; with keep 0 the file is emptied */
static void logRotate(Logger *lg)
{
    u8 *path = lg.opts.path;
    libc.close(lg.fd);
    bool truncate = lg.opts.keep == 0;
    if (!truncate) {
        for (i64 i = lg.opts.keep - 1; i >= 1; i--) {
            String from = logRotatedName(path, i);
            String to = logRotatedName(path, i + 1);
            if (fs.exists(from)) { fs.rename(from, to); }
            from.free();
            to.free();
        }
        String first = logRotatedName(path, 1);
        if (fs.rename(cstr.view(path), first) != 0) { truncate = true; }
        first.free();
    }
    lg.fd = logOpenFile(path, truncate);
    lg.size = 0;
}

/* 2026-10-13T14:02:11.482Z */
static void logTime(String *out)
{
    libc.timeval tv;
    libc.gettimeofday(&tv, null);
    i64 secs = tv.tv_sec;
    libc.tm t;
    gmtime_r(&secs, &t);
    out.appendFormat("%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
                     t.tm_year + 1900, t.tm_mon + 1, t.tm_mday,
                     t.tm_hour, t.tm_min, t.tm_sec, tv.tv_usec / 1000);
}

static u8 *logBaseName(u8 *path)
{
    u8 *slash = libc.strrchr(path, '/');
    return slash ? slash + 1 : path;
}

/* All of line to fd, what printf holds first when fd is stdout so the two
 * stay in order; the bytes written */
static i64 logWriteAll(i32 fd, String *line)
{
    if (fd == 1) { libc.fflush(libc.stdout); }
    i64 done = 0;
    while (fd >= 0 && done < line.len) {
        i64 n = libc.write(fd, line.ptr + done, line.len - done);
        if (n < 0 && os.errno() == libc.EINTR) { continue; }
        if (n <= 0) { break; }
        done += n;
    }
    return done;
}

/* One line: the parts after the time, the message on one line ('\n' and
 * '\r' written as \n and \r) */
static void logWrite(Logger *lg, Level level, u8 *file, i64 line, u8 *func,
                     u8 *fmt, u64[] args)
{
    if (lg.fd < 0) { lg.failed++; return; }
    u8 *msg = cstr.formatArgs(null, fmt, args);
    String rest = str.new();
    defer rest.free();
    rest.appendByte(' ');
    rest.append(cstr.view(gLogLevelNames[level]));
    rest.appendByte(' ');
    if (lg.opts.showFile) { rest.appendFormat("%s:%d ", logBaseName(file), line); }
    if (lg.opts.showFunc && func[0]) { rest.appendFormat("%s: ", func); }
    for (u8 *p = msg; p && *p; p++) {
        if (*p == '\n') { rest.append("\\n"); }
        else if (*p == '\r') { rest.append("\\r"); }
        else { rest.appendByte(*p); }
    }
    rest.appendByte('\n');
    mem.free(msg);

    if (lg.opts.threadSafe) { libc.pthread_mutex_lock(&lg.lock); }
    String full = str.withCap(rest.len + 32);
    logTime(&full);
    full.append(rest);
    if (lg.opts.path && lg.opts.maxBytes > 0 && lg.size > 0 &&
        lg.size + full.len > lg.opts.maxBytes)
    {
        logRotate(lg);
    }
    i64 done = logWriteAll(lg.fd, &full);
    if (done < full.len) { lg.failed++; }
    lg.size += done;
    /* a file and stdout both */
    if (lg.opts.path && lg.opts.toStdout && logWriteAll(1, &full) < full.len) {
        lg.failed++;
    }
    if (lg.opts.threadSafe) { libc.pthread_mutex_unlock(&lg.lock); }
    full.free();
}

/* The four levels: printf's format and arguments. The call's file, line
 * and function are filled in by the compiler (@caller). */
pub void Logger.debug(@caller("file") u8 *file, @caller("line") i64 line,
                      @caller("func") u8 *func, u8 *fmt, ...args)
{
    if (self.level > Level.debug) { return; }
    logWrite(self, Level.debug, file, line, func, fmt, args);
}

pub void Logger.info(@caller("file") u8 *file, @caller("line") i64 line,
                     @caller("func") u8 *func, u8 *fmt, ...args)
{
    if (self.level > Level.info) { return; }
    logWrite(self, Level.info, file, line, func, fmt, args);
}

pub void Logger.warn(@caller("file") u8 *file, @caller("line") i64 line,
                     @caller("func") u8 *func, u8 *fmt, ...args)
{
    if (self.level > Level.warn) { return; }
    logWrite(self, Level.warn, file, line, func, fmt, args);
}

pub void Logger.error(@caller("file") u8 *file, @caller("line") i64 line,
                      @caller("func") u8 *func, u8 *fmt, ...args)
{
    logWrite(self, Level.error, file, line, func, fmt, args);
}

/* Closes the file (not stderr) and frees the Logger */
pub void Logger.close()
{
    if (self.fd > 2) { libc.close(self.fd); }
    if (self.opts.threadSafe) { libc.pthread_mutex_destroy(&self.lock); }
    if (self.opts.path) { mem.free(self.opts.path); }
    mem.free(self);
}

/* The default logger, for log.info(...) and the others from anywhere in
 * the program: stderr at INFO until log.setup gives it Options. */
Logger gLogStderr;
Logger *gLogDefault = null;

static Logger *logDefault()
{
    if (gLogDefault) { return gLogDefault; }
    gLogStderr.fd = 2;
    gLogStderr.opts.level = Level.info;
    gLogStderr.level = Level.info;
    gLogDefault = &gLogStderr;
    return gLogDefault;
}

/* Sets the default logger up from `o`, as log.open does (throws
 * log.ERR_LOG the same way, and the old one is then kept). A default set
 * up before is closed. */
pub void setup(Options *o)
{
    Logger *lg = open(o);
    shutdown();
    gLogDefault = lg;
}

/* Closes the default logger; later calls write to stderr at INFO again */
pub void shutdown()
{
    if (gLogDefault && gLogDefault != &gLogStderr) { gLogDefault.close(); }
    gLogDefault = null;
}

/* The default logger's level, for the calls from now on */
pub void setLevel(Level level)
{
    logDefault().level = level;
}

/* The default Logger itself, for code that takes a Logger * */
pub Logger *logger()
{
    return logDefault();
}

pub void debug(@caller("file") u8 *file, @caller("line") i64 line,
               @caller("func") u8 *func, u8 *fmt, ...args)
{
    Logger *lg = logDefault();
    if (lg.level > Level.debug) { return; }
    logWrite(lg, Level.debug, file, line, func, fmt, args);
}

pub void info(@caller("file") u8 *file, @caller("line") i64 line,
              @caller("func") u8 *func, u8 *fmt, ...args)
{
    Logger *lg = logDefault();
    if (lg.level > Level.info) { return; }
    logWrite(lg, Level.info, file, line, func, fmt, args);
}

pub void warn(@caller("file") u8 *file, @caller("line") i64 line,
              @caller("func") u8 *func, u8 *fmt, ...args)
{
    Logger *lg = logDefault();
    if (lg.level > Level.warn) { return; }
    logWrite(lg, Level.warn, file, line, func, fmt, args);
}

pub void error(@caller("file") u8 *file, @caller("line") i64 line,
               @caller("func") u8 *func, u8 *fmt, ...args)
{
    logWrite(logDefault(), Level.error, file, line, func, fmt, args);
}
