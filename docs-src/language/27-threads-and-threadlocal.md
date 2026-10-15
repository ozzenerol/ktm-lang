# Threads and threadlocal

A ktm program can run several threads at once, each with its own stack, all sharing the
program's globals and heap. The language adds one thing for them, `threadlocal`: a global with
one copy per thread. The rest comes from the library: POSIX threads through `libc`, a thread
pool in `thread`, atomic integers in `atomic`, and coroutines in `coro`. This chapter covers
starting threads, sharing data safely, `threadlocal` in depth, errors in threads, and coroutines
in brief.

## Starting a thread

`libc.pthread_create` starts a thread running a function of type `fn(void *) void *`; the
argument is passed through as a `void *`. `libc.pthread_join` waits for it. Pass `&th` to
create and `th` to join:

```ktm run
import libc;

i64 sums[4];

void *sum(void *arg) {
    i64 part = (i64)arg, total = 0;
    for (i64 i = part * 250 + 1; i <= (part + 1) * 250; i++) {
        total += i;
    }
    sums[part] = total;
    return null;
}

i32 main() {
    libc.pthread_t *th[4];
    for (i64 i = 0; i < 4; i++) {
        libc.pthread_create(&th[i], null, &sum, (void *)i);
    }
    i64 total = 0;
    for (i64 i = 0; i < 4; i++) {
        libc.pthread_join(th[i]);
        total += sums[i];
    }
    printf("1 + 2 + ... + 1000 = %d\n", total);
    return 0;
}
```

```output
1 + 2 + ... + 1000 = 500500
```

Each thread wrote only its own slot of `sums`, so they didn't need to coordinate. A struct passed
by pointer carries several arguments:

```ktm run
import libc;

struct Job {
    u8 *name;
    i64 from, to;
    i64 result;
};

void *count(void *arg) {
    Job *j = arg;
    for (i64 i = j.from; i < j.to; i++) {
        if (i % 7 == 0) {
            j.result++;
        }
    }
    return null;
}

i32 main() {
    Job jobs[2] = {{"low", 0, 500, 0}, {"high", 500, 1000, 0}};
    libc.pthread_t *th[2];
    for (i64 i = 0; i < 2; i++) {
        libc.pthread_create(&th[i], null, &count, &jobs[i]);
    }
    for (i64 i = 0; i < 2; i++) {
        libc.pthread_join(th[i]);
        printf("%s: %d multiples of 7\n", jobs[i].name, jobs[i].result);
    }
    return 0;
}
```

```output
low: 72 multiples of 7
high: 71 multiples of 7
```

## Sharing data

When threads change the same data, they must take turns. A mutex does it:

```ktm run
import libc;

libc.pthread_mutex_t gLock;
i64 gTotal = 0;

void *add(void *arg) {
    for (i64 i = 0; i < 10000; i++) {
        libc.pthread_mutex_lock(&gLock);
        gTotal++;
        libc.pthread_mutex_unlock(&gLock);
    }
    return null;
}

i32 main() {
    libc.pthread_mutex_init(&gLock, null);
    libc.pthread_t *th[4];
    for (i64 i = 0; i < 4; i++) {
        libc.pthread_create(&th[i], null, &add, null);
    }
    for (i64 i = 0; i < 4; i++) {
        libc.pthread_join(th[i]);
    }
    printf("%d\n", gTotal);
    return 0;
}
```

```output
40000
```

For a single integer, the `atomic` module is simpler and faster:

```ktm run
import atomic, libc;

i64 gHits = 0;

void *worker(void *unused) {
    for (i64 i = 0; i < 100000; i++) {
        atomic.add(&gHits, 1);
    }
    return null;
}

i32 main() {
    libc.pthread_t *th[4];
    for (i64 i = 0; i < 4; i++) {
        libc.pthread_create(&th[i], null, &worker, null);
    }
    for (i64 i = 0; i < 4; i++) {
        libc.pthread_join(th[i]);
    }
    printf("%d hits\n", atomic.load(&gHits));
    return 0;
}
```

```output
400000 hits
```

See [thread](../std/index.html#thread) for the thread pool and [atomic](../std/index.html#atomic)
for every atomic operation.

## threadlocal

A global marked `threadlocal` has one copy per thread. Every thread starts with the declared
value, and what one thread writes the others don't see:

```ktm run
import libc;

threadlocal i64 hits = 10;
i64 seen[2];

void *work(void *arg) {
    i64 k = (i64)arg;
    for (i64 i = 0; i <= k; i++) {
        hits++;
    }
    seen[k] = hits;
    return null;
}

i32 main() {
    hits = 100;
    libc.pthread_t *th[2];
    for (i64 i = 0; i < 2; i++) {
        libc.pthread_create(&th[i], null, &work, (void *)i);
    }
    for (i64 i = 0; i < 2; i++) {
        libc.pthread_join(th[i]);
    }
    printf("%d %d, main %d\n", seen[0], seen[1], hits);
    return 0;
}
```

```output
11 12, main 100
```

Main's `hits = 100` changed only main's copy; each thread started from 10.

A threadlocal is useful for per-thread state that would otherwise be passed through every call:
a buffer each thread reuses, a counter, the current request:

```ktm run
import libc;

threadlocal u8 *gWorker = "main";

void say(u8 *what) {
    printf("[%s] %s\n", gWorker, what);
}

void *run(void *name) {
    gWorker = name;
    say("started");
    return null;
}

i32 main() {
    libc.pthread_t *th;
    libc.pthread_create(&th, null, &run, "worker-1");
    libc.pthread_join(th);
    say("still main");
    return 0;
}
```

```output
[worker-1] started
[main] still main
```

A struct or an array can be threadlocal too, each thread getting its own:

```ktm run
import libc;

struct Stats {
    i64 calls;
    i64 errors;
};

threadlocal Stats gStats;

void *busy(void *arg) {
    for (i64 i = 0; i < 5; i++) {
        gStats.calls++;
    }
    gStats.errors = 1;
    printf("thread: %d calls, %d errors\n", gStats.calls, gStats.errors);
    return null;
}

i32 main() {
    gStats.calls = 1;
    libc.pthread_t *th;
    libc.pthread_create(&th, null, &busy, null);
    libc.pthread_join(th);
    printf("main: %d calls, %d errors\n", gStats.calls, gStats.errors);
    return 0;
}
```

```output
thread: 5 calls, 1 errors
main: 1 calls, 0 errors
```

The thread started from a zeroed `Stats` of its own; main's `calls = 1` and the thread's changes
never met.

### threadlocal rules

- Only a global can be `threadlocal`: `threadlocal i64 x;`, `static threadlocal ...`,
  `pub threadlocal ...`, `extern threadlocal ...`.
- The initial value must be a constant: a number, a string, `{...}`, or the address of an
  ordinary global.
- `&x` is the calling thread's copy. A coroutine resumed on another thread sees that thread's
  copies.
- Compiled programs reach a threadlocal in one or two instructions through the thread pointer
  (on macOS through the system's thread-local call). The JIT keeps a copy per thread, a little
  slower, and can't reach the threadlocal of a module built with `-lib`: read it through a
  function of the module there.

```ktm error
i32 main() {
    threadlocal i64 n = 0;
    return 0;
}
```

```output
`threadlocal` only goes on a global variable, not a local
```

## Errors in threads

Each thread has its own chain of `try` blocks: a throw is caught only by a `try` of its own
thread. An error no `try` catches, in any thread, ends the whole program (see
[Errors](15-errors.html)):

```ktm run
import libc;

enum Err { bad };

void *job(void *arg) {
    try {
        throw(Err.bad, "inside the thread");
    } catch (Error e) {
        printf("thread caught: %s\n", e.message);
    }
    return null;
}

i32 main() {
    libc.pthread_t *th;
    libc.pthread_create(&th, null, &job, null);
    libc.pthread_join(th);
    printf("main goes on\n");
    return 0;
}
```

```output
thread caught: inside the thread
main goes on
```

## Coroutines in brief

A coroutine is a function that can pause in the middle and be resumed later, on its own small
stack, in the same thread. Thousands of them are cheap. The `coro` module provides them: as
generators, and as the event loop behind ktm's network servers, where a coroutine waiting for
the network lets the others run.

```ktm run
import coro;

void *count(void *arg) {
    for (i64 i = 1; i <= 3; i++) {
        coro.yield((void *)i);
    }
    return null;
}

i32 main() {
    coro.Coroutine *c = coro.spawn(&count);
    while (true) {
        i64 v = (i64)coro.next(c);
        if (coro.done(c)) {
            break;
        }
        printf("%d ", v);
    }
    printf("\n");
    coro.release(c);
    return 0;
}
```

```output
1 2 3 
```

See [coro](../std/index.html#coro) for generators, the event loop (`coro.go`, `coro.run`,
`coro.read`, `coro.write`) and `coro.serve`, which runs a loop on every core.

## Rules

- Threads come from `libc.pthread_create`/`pthread_join`; their function is
  `fn(void *) void *`.
- Threads share globals and the heap; protect shared changes with a mutex or `atomic`.
- `threadlocal` on a global gives each thread its own copy, starting at the declared constant
  value.
- Each thread has its own `try` chain; an uncaught error in any thread ends the program.
- Coroutines run inside a thread; see the `coro` module.
