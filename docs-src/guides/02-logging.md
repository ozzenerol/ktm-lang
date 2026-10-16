# Logging

Write what a program does to stderr, to stdout or to a file, with the standard library's `log`
module. This guide starts with one line and adds a step at a time: levels, a level from the
environment, a log file, where each line came from, rotation, a second log of your own, threads,
and the access log of an HTTP server. Every step is a whole program you can build and run.

## What you need

Any ktm. `log` is part of the standard library, so there is nothing to fetch. Every program in
this guide is one file, `app.ktm`, built and run like this:

```sh
ktm app.ktm -o app
./app
```

The times in the outputs below are the ones from the runs that made this guide. Yours are the
time you run it.

## The first lines

```ktm
import log;

i32 main() {
    log.info("starting, version %s", "1.4.0");
    log.debug("this line is not written: debug is below the default level");
    log.warn("disk %d%% full", 91);
    log.error("can't reach %s: %s", "db.internal:5432", "connection refused");
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
2026-10-16T02:23:59.539Z INFO  starting, version 1.4.0
2026-10-16T02:23:59.539Z WARN  disk 91% full
2026-10-16T02:23:59.539Z ERROR can't reach db.internal:5432: connection refused
```

- Each call writes one line: the time, the level, then the message.
- The time is UTC, with milliseconds, so lines from machines in different time zones sort
  together.
- The message is formatted like `printf`: `%s`, `%d`, `%%` for a percent sign, and the rest.
- `log.debug` wrote nothing. The default level is `info`, and a logger writes its level and the
  ones above it: `debug` < `info` < `warn` < `error`.
- `log.info` and the others work without any set-up. Until you set the log up, they write to
  stderr.

The lines go to stderr, so they stay out of what the program prints on stdout. The shell sends
them wherever you want:

```sh
./app 2> app.err
cat app.err
```

```output
2026-10-16T02:23:59.541Z INFO  starting, version 1.4.0
2026-10-16T02:23:59.541Z WARN  disk 91% full
2026-10-16T02:23:59.541Z ERROR can't reach db.internal:5432: connection refused
```

## Levels

Use `debug` for detail you need only while hunting a bug, `info` for what the program is doing,
`warn` for something wrong that it got past, and `error` for something it couldn't do.
`log.setLevel` changes which of them are written. A common way to choose is an environment
variable, so the same build can be quiet in production and chatty while you look into a problem:

```ktm
import log, proc, str;

/* The level LOG_LEVEL names (debug, info, warn or error); info when it is
 * unset or something else */
log.Level levelFromEnv() {
    scoped String v = proc.getenv("LOG_LEVEL");
    if (str.equals(v[0:], "debug")) { return log.Level.debug; }
    if (str.equals(v[0:], "warn")) { return log.Level.warn; }
    if (str.equals(v[0:], "error")) { return log.Level.error; }
    return log.Level.info;
}

i32 main() {
    log.setLevel(levelFromEnv());
    log.debug("config loaded from %s", "/etc/app.conf");
    log.info("starting");
    log.warn("cache is cold");
    log.error("payment provider timed out");
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
2026-10-16T02:24:08.546Z INFO  starting
2026-10-16T02:24:08.546Z WARN  cache is cold
2026-10-16T02:24:08.546Z ERROR payment provider timed out
```

```sh
LOG_LEVEL=debug ./app
```

```output
2026-10-16T02:24:08.547Z DEBUG config loaded from /etc/app.conf
2026-10-16T02:24:08.547Z INFO  starting
2026-10-16T02:24:08.547Z WARN  cache is cold
2026-10-16T02:24:08.547Z ERROR payment provider timed out
```

```sh
LOG_LEVEL=error ./app
```

```output
2026-10-16T02:24:08.548Z ERROR payment provider timed out
```

- `log.Level` is an enum: `log.Level.debug`, `log.Level.info`, `log.Level.warn`,
  `log.Level.error`.
- `log.setLevel` takes effect for the calls after it, and can be called at any time.
- `error` lines are always written, whatever the level.
- A call below the level returns before it formats anything, so `debug` lines cost almost
  nothing when they are off.

## Logging to a file

`log.setup` sets the default logger up from a `log.Options`: the one `log.info` and the others
write to. Start from `log.options()`, which holds the defaults, and change what you need. Here the
lines go to a file, with the file, line and function of each call:

```ktm
import log;

void loadConfig() {
    log.info("config loaded");
}

void connect(u8 *host) {
    log.warn("%s is slow to answer", host);
}

i32 main() {
    log.Options o = log.options();
    o.path = "app.log";
    o.showFile = true;
    o.showFunc = true;
    log.setup(&o);
    defer log.shutdown();

    log.info("starting");
    loadConfig();
    connect("db.internal");
    log.error("query failed:\nSELECT * FROM users\nno such table: users");
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
cat app.log
```

```output
2026-10-16T02:24:13.683Z INFO  app.ktm:19 main:    starting
2026-10-16T02:24:13.684Z INFO  app.ktm:4 loadConfig: config loaded
2026-10-16T02:24:13.684Z WARN  app.ktm:8 connect:    db.internal is slow to answer
2026-10-16T02:24:13.684Z ERROR app.ktm:22 main:      query failed:\nSELECT * FROM users\nno such table: users
```

- `o.path` is the file. It is created when it isn't there, and appended to when it is, so the
  next run adds its lines after these.
- `o.showFile` adds the call's file name and line, and `o.showFunc` the function it is in. The
  compiler fills them in for each call; there is nothing to pass.
- The messages start in one column. That column is as wide as the widest file and function part
  so far (20 characters at least), so it can move right once, as it did after `loadConfig`.
- A message is always one line. A newline inside it is written as `\n` and a carriage return as
  `\r`, so every line of the file starts with a time and a level, and tools that read the log a
  line at a time see one entry per line.
- `log.setup` copies what it needs from the Options, so `o` can go away after the call.
- `defer log.shutdown();` closes the file when `main` returns. The [next section but
  one](#shutting-down) says where it must go.

## Stdout, for containers

Docker, Kubernetes and systemd collect what a program writes to stdout and stderr. A program run
by one of them usually logs to stdout and has no file. `o.toStdout` without a path does that:

```ktm
import log;

i32 main() {
    log.Options o = log.options();
    o.toStdout = true;
    log.setup(&o);
    defer log.shutdown();

    log.info("to stdout, for a container or a service manager to collect");
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
2026-10-16T02:25:08.605Z INFO  to stdout, for a container or a service manager to collect
```

With a path as well, every line goes to the file and to stdout:

```ktm
import log;

i32 main() {
    log.Options o = log.options();
    o.path = "app.log";
    o.toStdout = true;
    log.setup(&o);
    defer log.shutdown();

    log.info("written to app.log and to stdout");
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
cat app.log
```

```output
2026-10-16T02:24:20.965Z INFO  written to app.log and to stdout
2026-10-16T02:24:20.965Z INFO  written to app.log and to stdout
```

The first line is the one on stdout, the second the one `cat` shows from the file. A program that
also prints with `printf` keeps its output and its log lines in the order it wrote them.

## Shutting down

`log.shutdown()` closes the default logger. After it, `log.info` and the others write to stderr
again, at the level the closed one had. Defer it in `main`, so it runs when the program ends.
Deferred anywhere else, it runs too early:

```ktm
import log;

void work() {
    log.Options o = log.options();
    o.path = "app.log";
    log.setup(&o);
    defer log.shutdown();
    log.info("working");
}

i32 main() {
    work();
    log.info("done");
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
cat app.log
```

```output
log: a line logged after log.shutdown() at app.ktm:13 goes to stderr (was the shutdown deferred in an inner block?)
2026-10-16T02:24:21.020Z INFO  done
2026-10-16T02:24:21.020Z INFO  working
```

The shutdown ran when `work` returned, so `done` went to stderr instead of `app.log`. The first
line after a shutdown says where it came from, once. Move `log.setup` and the deferred
`log.shutdown` into `main` to fix it.

- Calling `log.setup` again closes the logger set up before and opens the new one.
- `log.shutdown` with no logger set up does nothing.

## When the file can't be opened

`log.setup` throws `log.ERR_LOG` when it can't open the file, with the reason. The logger that
was there before stays, so a program can say what went wrong and keep going on stderr:

```ktm
import log;

i32 main() {
    log.Options o = log.options();
    o.path = "/var/log/app/app.log";
    try {
        log.setup(&o);
    } catch (Error e) {
        log.error("%s; logging to stderr", e.message);
    }
    log.info("starting");
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
2026-10-16T02:24:21.080Z ERROR log.open: can't open /var/log/app/app.log: No such file or directory; logging to stderr
2026-10-16T02:24:21.080Z INFO  starting
```

The log module doesn't make directories. Make `/var/log/app` when the program is installed, owned
by the user it runs as. Without the `try`, the error stops the program at start-up with an
`uncaught error` report, which is also a fine choice for a service that must have its log.

A write that fails later, on a full disk for example, doesn't stop the program either. The line
is dropped and counted in the logger's `failed` field: `log.logger().failed`.

## Rotation

A log file of a program that runs for months grows without end. `o.maxBytes` starts a new file
before a line would take the current one past that size, and `o.keep` says how many old files to
keep:

```ktm
import log;

i32 main() {
    log.Options o = log.options();
    o.path = "app.log";
    o.maxBytes = 1000;   /* small, to see it rotate: use megabytes for real */
    o.keep = 3;
    log.setup(&o);
    defer log.shutdown();

    for (i64 i = 1; i <= 100; i++) {
        log.info("request %d done", i);
    }
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
wc -c app.log*
```

```output
 753 app.log
 987 app.log.1
 987 app.log.2
 987 app.log.3
3714 total
```

```sh
head -1 app.log.3
tail -1 app.log
```

```output
2026-10-16T02:24:25.721Z INFO  request 22 done
2026-10-16T02:24:25.721Z INFO  request 100 done
```

- `app.log` is always the newest. When it is full it becomes `app.log.1`, `app.log.1` becomes
  `app.log.2`, and so on. The file past `keep` is deleted: requests 1 to 21 are gone.
- A line is never split between two files, and a file only goes over `maxBytes` when a single
  line is bigger than that.
- With `keep` 0, the file is emptied and started again instead.
- `maxBytes` 0, the default, never rotates. Use it when something else rotates the file, such as
  logrotate.
- Pick sizes for the disk you have: `o.maxBytes = 50 * 1024 * 1024; o.keep = 5;` keeps at most
  300 MB.

## A logger of your own

Some lines belong in a log of their own: an audit trail of money moving, a slow-query log.
`log.open` makes a separate `log.Logger` from the same Options. Its methods are the same four
calls:

```ktm
import log;

/* Code that takes the Logger to write to */
void transfer(log.Logger *audit, u8 *from, u8 *to, i64 cents) {
    log.debug("transfer %s -> %s", from, to);
    audit.info("transfer from=%s to=%s cents=%d", from, to, cents);
}

i32 main() {
    log.Options o = log.options();
    o.path = "audit.log";
    log.Logger *audit = log.open(&o);
    defer audit.close();

    log.info("starting");
    transfer(audit, "ann", "bob", 2500);
    transfer(audit, "bob", "cat", 120);
    transfer(log.logger(), "dan", "eve", 990);   /* the default logger: stderr */

    audit.level = log.Level.warn;
    transfer(audit, "cat", "ann", 75);      /* below warn: not in audit.log */
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
2026-10-16T02:25:07.408Z INFO  starting
2026-10-16T02:25:07.408Z INFO  transfer from=dan to=eve cents=990
```

```sh
cat audit.log
```

```output
2026-10-16T02:25:07.408Z INFO  transfer from=ann to=bob cents=2500
2026-10-16T02:25:07.408Z INFO  transfer from=bob to=cat cents=120
```

- `log.open(&o)` returns a `log.Logger *`. Close it with `close()`, which closes the file and
  frees the logger. It throws `log.ERR_LOG` like `log.setup` when the file can't be opened.
- A Logger has its own level: `audit.level = log.Level.warn;`. After that line the third
  transfer, an `info` line, wasn't written.
- `log.logger()` is the default logger as a `log.Logger *`. Code that takes a Logger can be
  given either one: the `dan` transfer went to stderr.
- `transfer`'s `log.debug` line wasn't written either: the default logger is at `info`.

## Threads

Several threads can share one logger. Set `o.threadSafe` on it:

```ktm
import log, thread;

struct Job {
    i64 id;
};

void worker(Job *job) {
    for (i64 i = 1; i <= 10000; i++) {
        log.info("worker %d: item %d", job.id, i);
    }
}

i32 main() {
    log.Options o = log.options();
    o.path = "app.log";
    o.threadSafe = true;
    log.setup(&o);
    defer log.shutdown();

    Job jobs[4];
    thread.Thread threads[4];
    for (i64 i = 0; i < 4; i++) {
        jobs[i].id = i + 1;
        threads[i] = thread.spawn(&worker, &jobs[i]);
    }
    for (i64 i = 0; i < 4; i++) {
        threads[i].join();
    }
    log.info("all workers done");
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
wc -l app.log
tail -1 app.log
```

```output
40001 app.log
2026-10-16T02:24:40.138Z INFO  all workers done
```

- `o.threadSafe` puts a lock around each line: the time, rotation and the write. Without it, two
  threads can rotate the file at once or miscount its size.
- Each line is written whole, in one write, so lines from different threads never mix
  characters. Their order is the order the threads got the lock.
- The lock is only taken for a line that is written. A `debug` line below the level costs no lock.

## An HTTP server's access log

`net.http` writes through the default logger. `accessLog` in the server's Options adds an `info`
line per request, and a handler that throws is logged as an `error`. The server answers on
several threads, so the logger is thread safe:

```ktm
import log, net.http;

const i64 ERR_NO_USER = 1;

void hello(http.Writer *w, http.Request *r) {
    u8[] name = r.query("name");
    if (!name.len) { name = "world"; }
    w.text(200, "hello, %s\n", name);
}

void order(http.Writer *w, http.Request *r) {
    log.warn("order %s: payment declined", r.param("id"));
    w.text(402, "payment declined\n");
}

void crash(http.Writer *w, http.Request *r) {
    throw(ERR_NO_USER, "no user %s", r.param("id"));
}

i32 main() {
    log.Options lo = log.options();
    lo.path = "access.log";
    lo.threadSafe = true;      /* the server answers on several threads */
    log.setup(&lo);
    defer log.shutdown();

    http.Mux mux;
    mux.handle("GET /hello", &hello);
    mux.handle("POST /orders/{id}", &order);
    mux.handle("GET /users/{id}", &crash);

    http.Options o;
    o.accessLog = true;
    log.info("listening on :8080");
    return http.listen(8080, &mux, null, &o);
}
```

Run it and send it a few requests from a second terminal:

```sh
ktm app.ktm -o app
./app
```

```sh
curl 'localhost:8080/hello?name=ada'
curl -X POST localhost:8080/orders/17
curl localhost:8080/users/7
curl localhost:8080/nope
```

Stop the server with Ctrl+C, then read the log:

```sh
cat access.log
```

```output
2026-10-16T02:25:07.492Z INFO  listening on :8080
2026-10-16T02:25:07.998Z INFO  GET /hello?name=ada 200 11 0.006ms
2026-10-16T02:25:08.004Z WARN  order 17: payment declined
2026-10-16T02:25:08.004Z INFO  POST /orders/17 402 17 0.012ms
2026-10-16T02:25:08.010Z ERROR http: GET /users/7: the handler threw 0x1 at app.ktm:17: no user 7
2026-10-16T02:25:08.010Z INFO  GET /users/7 500 33 0.020ms
2026-10-16T02:25:08.016Z INFO  GET /nope 404 21 0.003ms
```

- An access line is the method, the path with its query, the status, the body's size in bytes,
  and how long the handler took.
- The access line is written when the request is done, so a handler's own lines come before it.
- A handler that throws gets a 500 for the client and an `error` line with the code, the place
  and the message for you.
- Ctrl+C (SIGINT) and SIGTERM stop the server: `http.listen` returns, and the deferred
  `log.shutdown()` closes the file.

## Reference

| Call | What it does |
| --- | --- |
| `log.debug(fmt, ...)`, `log.info`, `log.warn`, `log.error` | one line on the default logger, formatted like `printf` |
| `log.setLevel(level)` | the default logger's level, for the calls from now on |
| `log.options()` | Options with the defaults: stderr, `info`, no file or function, no rotation |
| `log.setup(&o)` | sets the default logger up from `o`; closes the one before; throws `log.ERR_LOG` |
| `log.shutdown()` | closes the default logger; later lines go to stderr |
| `log.logger()` | the default logger, as a `log.Logger *` |
| `log.open(&o)` | a Logger of its own; throws `log.ERR_LOG` |
| `lg.debug(fmt, ...)`, `lg.info`, `lg.warn`, `lg.error` | one line on that logger |
| `lg.close()` | closes its file and frees it |

| Option | Default | What it does |
| --- | --- | --- |
| `path` | `null` | the file to append to; `null` writes to stderr |
| `toStdout` | `false` | stdout instead of stderr, or as well as the file |
| `level` | `log.Level.info` | the least important level written |
| `showFile` | `false` | the call's file name and line |
| `showFunc` | `false` | the function the call is in |
| `maxBytes` | `0` | rotate before the file would pass this size; `0` never |
| `keep` | `0` | how many rotated files to keep (`path.1` is the newest) |
| `threadSafe` | `false` | a lock around each line, for a logger threads share |

A `log.Logger` has two fields to read: `level`, which can also be set, and `failed`, the lines a
failed write dropped.

## Rules of thumb

- **Set the log up first thing in `main`, and defer `log.shutdown()` right after.** Lines logged
  before the set-up go to stderr.
- **Log events, not every step.** One `info` line per thing that happened (a request served, a
  job finished) reads well. Put the steps in between at `debug`.
- **Put values in the message as `key=value`** (`transfer from=ann cents=2500`). Then `grep` finds
  them, and a log tool can split them into fields.
- **Never log secrets.** Passwords, tokens, keys and full card numbers stay out of the message.
- **`error` means someone should look.** If nobody has to act, it is a `warn`.
- **Set `threadSafe` whenever more than one thread logs**, which includes every `net.http`
  server.
