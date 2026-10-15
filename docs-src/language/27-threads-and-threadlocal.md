# Threads and threadlocal

A ktm program can run several threads at once, each with its own stack, all sharing the
program's globals and heap. The [thread](../std/index.html#thread) module starts them and gives
them locks to take turns with; the language adds `threadlocal`, a global with one copy per
thread. This chapter covers starting threads, errors in them, sharing data safely, `threadlocal`,
many threads at once (WaitGroup, each, channels, pools), coroutines in brief, and C's
threads.

## Starting a thread

`thread.spawn(&f, &arg)` runs `f(&arg)` on a new thread and returns a `thread.Thread`; its
`join()` waits for it to end. The function takes a pointer to any type, so a thread gets its
input and leaves its result in the same struct:

```ktm run
import thread;

struct Part {
    i64 from, to;
    i64 sum;
};

void add(Part *p) {
    for (i64 i = p.from; i < p.to; i++) {
        p.sum += i;
    }
}

void main() {
    Part parts[4];
    thread.Thread t[4];
    for (i64 i = 0; i < 4; i++) {
        parts[i].from = i * 250 + 1;
        parts[i].to = (i + 1) * 250 + 1;
        t[i] = thread.spawn(&add, &parts[i]);
    }
    i64 total = 0;
    for (i64 i = 0; i < 4; i++) {
        t[i].join();
        total += parts[i].sum;
    }
    printf("1 + 2 + ... + 1000 = %d\n", total);
}
```

```output
1 + 2 + ... + 1000 = 500500
```

Each thread wrote only its own `Part`, so they didn't need to take turns. The argument is shared
with the caller: keep it alive until `join()` returns.

A function with no argument is started with `thread.spawn(&f)`:

```ktm run
import thread;

i64 gDone;

void work() {
    gDone = 1;
}

void main() {
    thread.Thread t = thread.spawn(&work);
    t.join();
    printf("done: %d\n", gDone);
}
```

```output
done: 1
```

- A `Thread` is joined once; joining it again, or joining a `Thread` never started, does nothing.
- A thread that is never joined keeps a little memory until the program ends.
- A spawned thread has all of ktm's runtime: a crash in it is reported with its frames, `try`
  works, and `threadlocal` globals are its own.

## Many threads at once

### go and WaitGroup

To start many threads and wait for all of them, `thread.go(&f, &arg, &wg)` starts one and counts
it in a `thread.WaitGroup`; `wg.wait()` returns when every one has finished. There is nothing to
join:

```ktm run
import thread;

struct Job {
    i64 n;
    i64 square;
};

void work(Job *j) {
    j.square = j.n * j.n;
}

void main() {
    Job jobs[5];
    thread.WaitGroup wg;
    for (i64 i = 0; i < 5; i++) {
        jobs[i].n = i + 1;
        thread.go(&work, &jobs[i], &wg);
    }
    wg.wait();
    for (i64 i = 0; i < 5; i++) {
        printf("%d ", jobs[i].square);
    }
    printf("\n");
}
```

```output
1 4 9 16 25 
```

`wg.wait()` throws the first error one of its threads threw. A `WaitGroup` can count work of
your own too: `wg.add(n)` before it starts, `wg.done()` as each piece ends.

### each

`thread.each(items, &f)` runs `f` on every element of a slice, split over as many threads as the
machine has cores, and returns when all are done. It is the simplest way to use every core:

```ktm run
import thread;

struct Image {
    i64 id;
    i64 pixels;
};

void shrink(Image *img) {
    img.pixels = img.pixels / 4;
}

void main() {
    Image images[6];
    for (i64 i = 0; i < 6; i++) {
        images[i].id = i;
        images[i].pixels = 1000 * (i + 1);
    }
    thread.each(images[0:6], &shrink);
    for (i64 i = 0; i < 6; i++) {
        printf("%d ", images[i].pixels);
    }
    printf("\n");
}
```

```output
250 500 750 1000 1250 1500 
```

Like `wg.wait()`, `each` throws the first error one of the calls threw.

### Channels

A `thread.Channel<T>` passes values from thread to thread: `send(v)` puts one in, `recv(&v)`
takes the oldest out. `thread.channel<T>(n)` makes one that holds up to `n` values: a send waits
while it is full, a receive while it is empty. `close()` says no more will come: `recv` returns
false once a closed channel is empty. `scoped` frees it:

```ktm run
import thread;

void produce(thread.Channel<i64> *ch) {
    for (i64 i = 1; i <= 5; i++) {
        ch.send(i * 10);
    }
    ch.close();
}

void main() {
    scoped thread.Channel<i64> ch = thread.channel<i64>(2);
    thread.Thread t = thread.spawn(&produce, &ch);
    i64 v;
    i64 total = 0;
    while (ch.recv(&v)) {
        total += v;
    }
    t.join();
    printf("received %d in all\n", total);
}
```

```output
received 150 in all
```

A channel made with no size (`thread.channel<T>()`) holds nothing: each send waits until a
receive takes the value. Sending on a closed channel throws.

### A pool of threads

Starting a thread costs some microseconds. For many small jobs, a `thread.Pool` keeps a few
threads running and hands them the jobs: `thread.pool(n)` starts `n` (0 for one per core),
`p.run(&f, &arg)` queues a job, `p.wait()` waits until the queue is done and throws the first
error a job threw. `scoped` stops it:

```ktm run
import thread;

struct Job {
    i64 n;
    i64 result;
};

void triple(Job *j) {
    j.result = j.n * 3;
}

void main() {
    Job jobs[8];
    scoped thread.Pool *p = thread.pool(2);
    for (i64 i = 0; i < 8; i++) {
        jobs[i].n = i;
        p.run(&triple, &jobs[i]);
    }
    p.wait();
    printf("%d %d\n", jobs[1].result, jobs[7].result);
}
```

```output
3 21
```

## Errors in threads

What a thread throws comes back from `join()`: the same code, message, file and line, thrown in
the thread that joins. A `try` around the `join()` catches it:

```ktm run
import thread;

enum Err { badInput };

struct Job {
    i64 n;
};

void check(Job *j) {
    if (j.n < 0) {
        throw(Err.badInput, "job %d: negative input", j.n);
    }
}

void main() {
    Job j = {-3};
    thread.Thread t = thread.spawn(&check, &j);
    try {
        t.join();
    } catch (Error e) {
        printf("the thread failed: %s\n", e.message);
    }
}
```

```output
the thread failed: job -3: negative input
```

Within a thread, `try` and `catch` work as anywhere else: each thread has its own chain of `try`
blocks, and a throw is caught only by a `try` of its own thread before `join()` sees it.

## Sharing data

When threads change the same data, they must take turns. A `thread.Mutex` is held by one thread
at a time: `lock()` waits until no other thread holds it. It needs no setup and nothing to free:

```ktm run
import thread;

thread.Mutex gLock;
i64 gTotal = 0;

void add() {
    for (i64 i = 0; i < 10000; i++) {
        gLock.lock();
        gTotal++;
        gLock.unlock();
    }
}

void main() {
    thread.Thread t[4];
    for (i64 i = 0; i < 4; i++) {
        t[i] = thread.spawn(&add);
    }
    for (i64 i = 0; i < 4; i++) {
        t[i].join();
    }
    printf("%d\n", gTotal);
}
```

```output
40000
```

Without the lock some of the 40000 additions would be lost: two threads read the same value and
both write it plus one.

`guard()` locks and returns a `thread.Guard` that unlocks when it is freed, so with `scoped` the
lock is held until the end of the block, however the block is left (a `return`, a `break`, a
throw):

```ktm run
import thread;

thread.Mutex gLock;
i64 gBalance = 100;

bool withdraw(i64 amount) {
    scoped thread.Guard g = gLock.guard();
    if (gBalance < amount) {
        return false;
    }
    gBalance -= amount;
    return true;
}

void main() {
    printf("%d %d %d\n", withdraw(60), withdraw(60), gBalance);
}
```

```output
1 0 40
```

`tryLock()` takes the lock only if it is free, and says whether it did.

### Waiting for a condition

A `thread.Cond` lets a thread sleep until another one says something changed. The waiting thread
holds the mutex, checks the condition in a loop, and `wait(&m)` lets go of the mutex while it
sleeps:

```ktm run
import thread;

thread.Mutex gM;
thread.Cond gReady;
i64 gQueued;
i64 gTaken;

void consume() {
    for (i64 n = 0; n < 3; n++) {
        gM.lock();
        while (gQueued == 0) {
            gReady.wait(&gM);
        }
        gQueued--;
        gTaken++;
        gM.unlock();
    }
}

void main() {
    thread.Thread t = thread.spawn(&consume);
    for (i64 n = 0; n < 3; n++) {
        gM.lock();
        gQueued++;
        gReady.signal();
        gM.unlock();
    }
    t.join();
    printf("taken %d\n", gTaken);
}
```

```output
taken 3
```

`signal()` wakes one waiting thread, `broadcast()` all of them.

### Readers and writers, and running once

A `thread.RwLock` lets many threads read at once while no one writes: `readLock()` and
`readUnlock()` around reading, `writeLock()` and `writeUnlock()` around changing. A
`thread.Once` runs a function once, however many threads ask:

```ktm run
import thread;

thread.Once gSetup;
i64 gSetups;

void setup() {
    gSetups++;
}

void worker() {
    gSetup.run(&setup);
}

void main() {
    thread.Thread t[4];
    for (i64 i = 0; i < 4; i++) {
        t[i] = thread.spawn(&worker);
    }
    for (i64 i = 0; i < 4; i++) {
        t[i].join();
    }
    printf("setup ran %d time\n", gSetups);
}
```

```output
setup ran 1 time
```

### Atomics

For a single integer, the [atomic](../std/index.html#atomic) module is simpler and faster than
a lock:

```ktm run
import atomic, thread;

i64 gHits = 0;

void worker() {
    for (i64 i = 0; i < 100000; i++) {
        atomic.add(&gHits, 1);
    }
}

void main() {
    thread.Thread t[4];
    for (i64 i = 0; i < 4; i++) {
        t[i] = thread.spawn(&worker);
    }
    for (i64 i = 0; i < 4; i++) {
        t[i].join();
    }
    printf("%d hits\n", atomic.load(&gHits));
}
```

```output
400000 hits
```

## threadlocal

A global marked `threadlocal` has one copy per thread. Every thread starts with the declared
value, and what one thread writes the others don't see:

```ktm run
import thread;

threadlocal i64 hits = 10;
i64 seen[2];

void work(i64 *k) {
    for (i64 i = 0; i <= *k; i++) {
        hits++;
    }
    seen[*k] = hits;
}

void main() {
    hits = 100;
    i64 ks[2] = {0, 1};
    thread.Thread t[2];
    for (i64 i = 0; i < 2; i++) {
        t[i] = thread.spawn(&work, &ks[i]);
    }
    for (i64 i = 0; i < 2; i++) {
        t[i].join();
    }
    printf("%d %d, main %d\n", seen[0], seen[1], hits);
}
```

```output
11 12, main 100
```

Main's `hits = 100` changed only main's copy; each thread started from 10.

A threadlocal is useful for per-thread state that would otherwise be passed through every call:
a buffer each thread reuses, a counter, the current request:

```ktm run
import thread;

threadlocal u8 *gWorker = "main";

void say(u8 *what) {
    printf("[%s] %s\n", gWorker, what);
}

void run(u8 *name) {
    gWorker = name;
    say("started");
}

void main() {
    thread.Thread t = thread.spawn(&run, "worker-1");
    t.join();
    say("still main");
}
```

```output
[worker-1] started
[main] still main
```

A struct or an array can be threadlocal too, each thread getting its own:

```ktm run
import thread;

struct Stats {
    i64 calls;
    i64 errors;
};

threadlocal Stats gStats;

void busy() {
    for (i64 i = 0; i < 5; i++) {
        gStats.calls++;
    }
    gStats.errors = 1;
    printf("thread: %d calls, %d errors\n", gStats.calls, gStats.errors);
}

void main() {
    gStats.calls = 1;
    thread.Thread t = thread.spawn(&busy);
    t.join();
    printf("main: %d calls, %d errors\n", gStats.calls, gStats.errors);
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

## C threads

The `thread` module is built on POSIX threads, which a program can also use directly through
`libc`, as C does: `libc.pthread_create` starts a thread running a function of type
`fn(void *) void *`, and `libc.pthread_join` waits for it. Pass `&th` to create and `th` to join:

```ktm run
import libc;

i64 gSum;

void *add(void *arg) {
    gSum = (i64)arg * 2;
    return null;
}

void main() {
    libc.pthread_t *th;
    libc.pthread_create(&th, null, &add, (void *)21);
    libc.pthread_join(th);
    printf("%d\n", gSum);
}
```

```output
42
```

A thread started this way gets no help: what it throws ends the program unless it catches it, and
its argument and result go through `void *`.

## Rules

- `thread.spawn(&f, &arg)` runs `f(&arg)` on a new thread (`f` takes a pointer), and
  `thread.spawn(&f)` a function with no argument; `join()` waits for it.
- What a thread throws, `join()` throws in the thread that joins; `WaitGroup.wait()`,
  `thread.each` and `Pool.wait()` throw the first error their threads threw.
- `thread.go` with a `WaitGroup` starts threads to wait for together, `thread.each` runs a
  function over a slice on every core, a `Channel<T>` passes values between threads, and a
  `Pool` runs many small jobs on a few threads.
- Threads share globals and the heap; protect shared changes with a `thread.Mutex`, or use
  `atomic` for a single integer. The module's locks need no setup and nothing to free; don't
  copy one that is in use.
- `threadlocal` on a global gives each thread its own copy, starting at the declared constant
  value.
- Coroutines run inside a thread; see the `coro` module.
- C's threads are there too, through `libc.pthread_*`.
