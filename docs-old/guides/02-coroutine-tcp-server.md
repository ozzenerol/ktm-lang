# Guide 2: a multi-client TCP server with coroutines

[Guide 1](01-threaded-tcp-server.md) built a chat server with one thread per client. It
works, but it has three problems: a client that stops reading stalls everyone, shared
state needs locks, and there's no clean way to shut down.

This guide builds the same server on **coroutines**: one thread runs one lightweight
coroutine per client, all scheduled by ktm's `coro` event loop. On top of guide 1 it adds:

- **no locks**, because only one coroutine runs at a time,
- **non-blocking output with a per-client buffer**, so a slow client can't stall anyone and
  gets disconnected when it falls too far behind,
- **idle timeouts**,
- **graceful shutdown** on Ctrl-C or `SIGTERM`: stop accepting, tell every client, flush,
  exit 0,
- `/nick` and a `/who` that lists names.

Written and tested with **ktm v0.5.1** on Linux x86_64, compiled and in the JIT under
`-Memsafe` (no leaks or double frees reported). The full program is
[`chat_coro.ktm`](chat_coro.ktm), next to this file.

---

## 1. How the `coro` loop works

A coroutine is a function running on its own small stack that can **pause in the middle
and be resumed later**. The `coro` module adds a scheduler on top of that:

| Call                          | What it does                                                                 |
|-------------------------------|------------------------------------------------------------------------------|
| `coro.go(&Fn, arg)`           | start `void *Fn(void *arg)` as a coroutine in this thread's loop             |
| `coro.run()`                  | run the loop until every coroutine has returned                              |
| `coro.accept(lfd, ms)`        | next client as a **non-blocking** fd; `-1` on timeout or after `coro.stop()` |
| `coro.wait(fd, events, ms)`   | pause until `fd` is ready; returns `revents` (`> 0`), or `0` on timeout      |
| `coro.waitIdle(fd, events, ms)` | the same, but returns `-1` once `coro.stop()` is called                    |
| `coro.sleep(ms)`              | pause for `ms`, letting the others run                                       |
| `coro.close(fd)`              | close an fd (and let a paused `coro.accept` retry when fds ran out)          |
| `coro.stop()`                 | wind down: `accept`/`waitIdle` return `-1`, plain `wait`s carry on           |

Each turn of the loop is a single `poll()` over every fd some coroutine is waiting on.
The ready ones are resumed, one after another. A coroutine runs until its next `wait`,
`accept`, `sleep` or `return`, and then the next one gets the CPU.

That gives you the most important property in this guide: **between two waits, a
coroutine runs alone.** No other coroutine can change shared data under it. The client
list is a plain `list.List`, with no mutex.

It also brings the one rule you must not break: **never block the thread.** A blocking
`read`, `sleep(3)` or slow computation inside a coroutine freezes every client. Sockets
from `coro.accept` are already non-blocking. Every wait goes through `coro.wait*`.

The skeleton of every coroutine server:

```c
void *serve(void *arg)
{
    i32 fd = (i32)(i64)arg;
    // ... coro.wait(fd, libc.POLLIN, ms), read, write ...
    coro.close(fd);
    return null;
}

void *acceptor(void *unused)
{
    i32 fd;
    while ((fd = coro.accept(gListen)) != -1) {
        coro.go(&serve, (void *)(i64)fd);
    }
    return null;
}

i32 main()
{
    gListen = net.listen(7000, "*", 128);
    coro.go(&acceptor);
    coro.run();
    return 0;
}
```

`coro.accept` also handles running out of file descriptors (`EMFILE`): it rests instead
of spinning in a hot loop, and tries again when a `coro.close` frees one. That's why
connections should be closed with `coro.close`, not `libc.close`.

## 2. A struct per client

With threads, each client's state lived in local variables of its thread. Here it
lives in a heap struct, because other coroutines need to reach it to broadcast:

```c
struct Client {
    i32 fd;
    String name;
    bytes.Buffer in;              /* bytes read, not yet a whole line */
    bytes.Buffer out;             /* bytes to send, not yet taken by the kernel */
    i64 lastSeen;                 /* date.monoMs() of the last line */
    bool dead;                    /* kicked: its coroutine cleans up */
    list.List *node;              /* its node in gClients, to remove it in O(1) */
};

list.List gClients;               /* of Client *; gClients.init() in main */
i64 gCount = 0;                   /* gClients.count() walks the list */

void addClient(Client *c)
{
    gClients.append(c);
    c.node = gClients.prev;       /* append adds at the end: the head's prev */
    gCount++;
}

void removeClient(Client *c)
{
    c.node.remove();              /* unlinks and frees the node, not the client */
    c.node = null;
    gCount--;
}
```

The clients live in a `list.List` (see guide 1 for how it works). Guide 1 found a client
by walking the list. Here **each client remembers its own node**, so removal is O(1) no
matter how many clients are connected. `append` doesn't return the node it made, but the
list is circular and `append` adds at the end, so the new node is always `gClients.prev`.
`gCount` keeps the count in O(1) for the "server full" check, because `count()` walks
the list.

Iterating gives back the `Client *` as `it.value`:

```c
for (list.List *it = gClients.next; it != &gClients; it = it.next) {
    if (it.value != from) { sendTo(it.value, msg); }
}
```

This loop is safe even though `sendTo` can kick a client: kicking only marks the client
and shuts its socket down. The node leaves the list later, in that client's own coroutine.

**Ownership rule: a client's own coroutine is the only code that frees it.** Other
coroutines may queue output for it, or mark it `dead`, but never free it or close its fd.
That one rule removes a whole class of use-after-free bugs.

## 3. Output: never block on a slow client

The socket is non-blocking, so `write` takes what fits in the kernel's send buffer and
returns `-1` with `EAGAIN` for the rest. Broadcasting appends to each client's `out`
buffer and pushes out what fits right away:

```c
/* Send what the kernel takes now. false: the connection is broken. */
bool flush(Client *c)
{
    while (c.out.len > 0) {
        if (c.out.writeTo(c.fd) < 0) {
            return os.errno() == libc.EAGAIN;   /* full: the rest goes later */
        }
    }
    return true;
}

void sendTo(Client *c, u8[] msg)
{
    if (c.dead) { return; }
    c.out.append(msg);
    if (!flush(c) || c.out.len > MAX_PENDING) { kick(c); }
}
```

`bytes.Buffer.writeTo` does one `write(2)` and consumes what went out. Since it uses
plain `write`, the program ignores `SIGPIPE` once at startup. Without that, writing to a
client that just disconnected would kill the server:

```c
libc.signal(libc.SIGPIPE, libc.SIG_IGN);
```

A client that falls more than `MAX_PENDING` (64 KiB) behind is **kicked**. That only
happens to a client that has stopped reading: the kernel's own buffer, often a few
hundred KiB, is already full by the time ours grows. Kicking from inside someone else's
coroutine must not free anything, so `kick` only marks it and shuts the socket down:

```c
void kick(Client *c)
{
    c.dead = true;
    libc.shutdown(c.fd, libc.SHUT_RDWR);   /* its coroutine wakes up and sees end of file */
}
```

`shutdown` makes the socket readable with end-of-file. The kicked client's own coroutine
wakes up from its wait, sees `dead` or a `0` read, and cleans up by the ownership rule.

## 4. The client coroutine

Each client's coroutine waits for input and also, when output is pending, for room to
send it:

```c
while (!c.dead) {
    i64 ms = gIdleMs - (date.monoMs() - c.lastSeen);
    i16 events = libc.POLLIN;
    if (c.out.len > 0) {
        events |= libc.POLLOUT;
        if (ms > FLUSH_MS) { ms = FLUSH_MS; }
    }
    if (ms <= 0) {
        sendTo(c, "* idle too long, bye\n");
        break;
    }
    i64 r = coro.waitIdle(c.fd, events, ms);
    if (r < 0) {                                  /* coro.stop(): the server is going down */
        sendTo(c, "* server shutting down\n");
        break;
    }
    if (c.out.len > 0 && !flush(c)) { break; }
    if (r == 0 || !(r & (libc.POLLIN | libc.POLLHUP | libc.POLLERR))) { continue; }

    i64 n = c.in.readFrom(c.fd);
    if (n == 0) { break; }                        /* the client closed */
    if (n < 0) {
        if (os.errno() == libc.EAGAIN) { continue; }
        break;
    }
    c.lastSeen = date.monoMs();
    if (!handleInput(c)) { break; }
}
```

Some details:

- **One wait handles three jobs.** The timeout is "time left until idle", so reaching it
  means the client has been silent too long. `r & POLLIN` means input is ready. `-1` means
  shutdown.
- **`waitIdle`, not `wait`**, because "waiting for the client's next line" is idle
  waiting. When the server stops, idle waits end at once, while plain `coro.wait`s
  (used in `drain` below) keep going so in-flight work can finish.
- **Why `FLUSH_MS`.** A coroutine's wait is set when it parks. If another client broadcasts
  to this one while it's parked on `POLLIN` alone, the new bytes that didn't fit stay
  queued until this coroutine wakes. With output pending, the wait is capped at 100 ms, so
  those bytes go out soon. This only happens when the kernel buffer was full, so it costs
  nothing in the normal case.
- **`POLLHUP`/`POLLERR` lead to a read**, which then returns `0` or `-1` and ends the loop.
  Don't treat them as errors directly: a client can send its last line and close, and the
  data comes before the end-of-file.

Line framing is the same as in guide 1: read onto `c.in`, take whole lines off the front,
and cap the length.

### Cleaning up

When the loop ends, for any reason, everything is released in one place:

```c
drain(c);                       // let the last messages out (up to 1 s)
removeClient(c);                // out of the list before anything else can see it
String bye = str.format("* %s left\n", c.name);
broadcast(null, bye);
bye.free();
coro.close(c.fd);
clientFree(c);
return null;
```

`drain` uses plain `coro.wait(fd, POLLOUT, ...)`, which still works during shutdown, so
"server shutting down" actually reaches the client before the socket closes.

## 5. Graceful shutdown

`signal.stopInit()` turns `SIGINT` and `SIGTERM` into a readable fd. One more coroutine
waits on it:

```c
void *onSignal(void *unused)
{
    if (coro.waitIdle(signal.stopInit(), libc.POLLIN) > 0) {
        printf("signal %d: stopping\n", signal.stopSignal());
        coro.stop();
    }
    return null;
}
```

What happens on Ctrl-C:

1. `coro.stop()` is called. `coro.accept` returns `-1`, so the acceptor returns and no
   new clients get in.
2. Every client coroutine's `waitIdle` returns `-1`. Each queues "server shutting down",
   drains, and cleans up.
3. When the last coroutine returns, `coro.run()` returns in `main`, which closes the
   listener and exits with `0`.

A second Ctrl-C kills the process the usual way, in case something hangs. `onSignal`
uses `waitIdle` too, so if the server stops some other way, this coroutine also ends
instead of keeping the loop alive forever.

## 6. The full program

```c
import bytes, coro, cstr, date, libc, list, mem, net, os, signal, str;

#define MAX_CLIENTS 1024
#define MAX_LINE    1024
#define MAX_PENDING (64 * 1024)   /* unsent bytes a client may fall behind by */
#define FLUSH_MS    100           /* how often a client with unsent bytes retries */

struct Client {
    i32 fd;
    String name;
    bytes.Buffer in;              /* bytes read, not yet a whole line */
    bytes.Buffer out;             /* bytes to send, not yet taken by the kernel */
    i64 lastSeen;                 /* date.monoMs() of the last line */
    bool dead;                    /* kicked: its coroutine cleans up */
    list.List *node;              /* its node in gClients, to remove it in O(1) */
};

/* One thread, so no locks: only one coroutine runs at a time, and it
 * only gives way at coro.wait / coro.waitIdle / coro.accept. */
list.List gClients;               /* of Client *; gClients.init() in main */
i64 gCount = 0;                   /* gClients.count() walks the list */
i32 gListen;
i64 gIdleMs = 5 * 60 * 1000;

Client *clientNew(i32 fd)
{
    Client *c = mem.allocZero(sizeof(Client));
    c.fd = fd;
    c.name = net.peerAddr(fd);
    c.in = bytes.bufferNew();
    c.out = bytes.bufferNew();
    c.lastSeen = date.monoMs();
    return c;
}

void clientFree(Client *c)
{
    c.name.free();
    c.in.free();
    c.out.free();
    mem.free(c);
}

void addClient(Client *c)
{
    gClients.append(c);
    c.node = gClients.prev;       /* append adds at the end: the head's prev */
    gCount++;
}

void removeClient(Client *c)
{
    c.node.remove();              /* unlinks and frees the node, not the client */
    c.node = null;
    gCount--;
}

/* Send what the kernel takes now. false: the connection is broken. */
bool flush(Client *c)
{
    while (c.out.len > 0) {
        if (c.out.writeTo(c.fd) < 0) {
            return os.errno() == libc.EAGAIN;   /* full: the rest goes later */
        }
    }
    return true;
}

/* Too slow or broken: wake its coroutine (it sees end of file) to clean up. */
void kick(Client *c)
{
    c.dead = true;
    libc.shutdown(c.fd, libc.SHUT_RDWR);
}

/* Queue msg for one client and push out what fits. */
void sendTo(Client *c, u8[] msg)
{
    if (c.dead) { return; }
    c.out.append(msg);
    if (!flush(c) || c.out.len > MAX_PENDING) { kick(c); }
}

void broadcast(Client *from, u8[] msg)
{
    for (list.List *it = gClients.next; it != &gClients; it = it.next) {
        if (it.value != from) { sendTo(it.value, msg); }
    }
}

/* One line from c. false: c wants to leave. */
bool handleLine(Client *c, u8[] line)
{
    u8[] cmd, arg;
    if (!str.splitFirst(line, " ", &cmd, &arg)) {
        cmd = line;
        arg = "";
    }
    if (cmd.equals("/quit")) {
        return false;
    }
    if (cmd.equals("/who")) {
        String reply = str.format("* %d online:", gCount);
        for (list.List *it = gClients.next; it != &gClients; it = it.next) {
            Client *o = it.value;
            reply.appendFormat(" %s", o.name);
        }
        reply.append("\n");
        sendTo(c, reply);
        reply.free();
        return true;
    }
    if (cmd.equals("/nick")) {
        arg = arg.trim();
        if (arg.len == 0 || arg.len > 32) {
            sendTo(c, "* usage: /nick <name>, 1 to 32 bytes\n");
            return true;
        }
        String note = str.format("* %s is now %s\n", c.name, arg);
        c.name.clear();
        c.name.append(arg);
        broadcast(null, note);
        note.free();
        return true;
    }
    String msg = str.format("%s: %s\n", c.name, line);
    broadcast(c, msg);
    msg.free();
    return true;
}

/* Take the whole lines out of c.in. false: c wants to leave. */
bool handleInput(Client *c)
{
    i64 nl;
    while ((nl = c.in.data().find("\n")) != -1) {
        u8[] line = c.in.data()[0:nl].trimEnd("\r");
        bool more = handleLine(c, line);
        c.in.consume(nl + 1);
        if (!more) { return false; }
    }
    if (c.in.len > MAX_LINE) {
        sendTo(c, "* line too long, bye\n");
        return false;
    }
    return true;
}

/* Before closing: give the last messages up to a second to go out. */
void drain(Client *c)
{
    i64 until = date.monoMs() + 1000;
    while (!c.dead && c.out.len > 0 && flush(c)) {
        i64 left = until - date.monoMs();
        if (left <= 0 || coro.wait(c.fd, libc.POLLOUT, left) <= 0) { break; }
    }
}

/* One coroutine per client. */
void *serve(void *arg)
{
    Client *c = arg;
    addClient(c);
    sendTo(c, "* welcome! /nick <name>, /who, /quit\n");
    String hello = str.format("* %s joined\n", c.name);
    broadcast(c, hello);
    hello.free();

    while (!c.dead) {
        /* wait for input, or until the idle limit; with bytes still to
         * send, also for room to send them, checking every FLUSH_MS */
        i64 ms = gIdleMs - (date.monoMs() - c.lastSeen);
        i16 events = libc.POLLIN;
        if (c.out.len > 0) {
            events |= libc.POLLOUT;
            if (ms > FLUSH_MS) { ms = FLUSH_MS; }
        }
        if (ms <= 0) {
            sendTo(c, "* idle too long, bye\n");
            break;
        }
        i64 r = coro.waitIdle(c.fd, events, ms);
        if (r < 0) {                                  /* coro.stop(): the server is going down */
            sendTo(c, "* server shutting down\n");
            break;
        }
        if (c.out.len > 0 && !flush(c)) { break; }
        if (r == 0 || !(r & (libc.POLLIN | libc.POLLHUP | libc.POLLERR))) { continue; }

        i64 n = c.in.readFrom(c.fd);
        if (n == 0) { break; }                        /* the client closed */
        if (n < 0) {
            if (os.errno() == libc.EAGAIN) { continue; }
            break;
        }
        c.lastSeen = date.monoMs();
        if (!handleInput(c)) { break; }
    }

    drain(c);
    removeClient(c);
    String bye = str.format("* %s left\n", c.name);
    broadcast(null, bye);
    bye.free();
    coro.close(c.fd);
    clientFree(c);
    return null;
}

void *acceptor(void *unused)
{
    i32 fd;
    while ((fd = coro.accept(gListen)) != -1) {
        if (gCount >= MAX_CLIENTS) {
            net.send(fd, "* server full\n");
            coro.close(fd);
            continue;
        }
        net.setNoDelay(fd);                   /* chat lines are small: send them at once */
        Client *c = clientNew(fd);
        if (!coro.go(&serve, c)) {            /* no memory for its stack */
            coro.close(fd);
            clientFree(c);
        }
    }
    return null;
}

void *onSignal(void *unused)
{
    if (coro.waitIdle(signal.stopInit(), libc.POLLIN) > 0) {
        printf("signal %d: stopping\n", signal.stopSignal());
        coro.stop();
    }
    return null;
}

i32 main(i32 argc, u8 **argv)
{
    i32 port = argc > 1 ? (i32)cstr.toInt(argv[1]) : 7000;
    if (argc > 2) { gIdleMs = cstr.toInt(argv[2]) * 1000; }

    gClients.init();
    libc.signal(libc.SIGPIPE, libc.SIG_IGN);     /* a write to a closed peer: -1 EPIPE, not death */
    gListen = net.listen(port, "*", 128);
    if (gListen == -1) {
        printf("can't listen on port %d\n", port);
        return 1;
    }
    printf("chat server on port %d\n", net.localPort(gListen));

    coro.go(&acceptor);
    coro.go(&onSignal);
    coro.run();                               /* until every coroutine has ended */

    libc.close(gListen);
    printf("stopped\n");
    return 0;
}
```

Build and run:

```sh
ktm chat_coro.ktm -o chat_coro
./chat_coro 7000          # idle limit 5 minutes
./chat_coro 7000 30       # idle limit 30 seconds
ktm -jit -Memsafe chat_coro.ktm 7000   # in the JIT, with leak and double-free checks
```

Connect with `nc 127.0.0.1 7000`, set a name with `/nick ada`, and chat.

## 7. What was tested

Each of these was scripted (Python sockets against the server) and passed both compiled
and in the JIT under `-Memsafe`:

| Test | Result |
|------|--------|
| 3 clients chat; a line in two packets; two lines in one packet; `\r\n` | every line whole, in order |
| `/nick`, `/who`, `/quit`, client closing its socket | names update, `* bob left` broadcast |
| idle limit 2 s, client says nothing | `* idle too long, bye` after 2.0 s |
| a client with a 4 KiB receive buffer that never reads, while another sends 3000 × 1 KB lines | slow client kicked (`* slow left`); a reading client got **all 3000** lines |
| `SIGTERM` with clients connected | each got `* server shutting down`, exit code 0, `stopped` printed |

The slow-client test is the one that matters. Under guide 1's design, the same test
stalls every broadcast for the full send timeout.

## 8. Threads or coroutines?

|                          | Guide 1: thread per client           | Guide 2: coroutines                     |
|--------------------------|--------------------------------------|-----------------------------------------|
| Code style               | plain blocking code                  | plain code, but every wait is `coro.*`  |
| Shared state             | mutex everywhere                     | no locks (one thread)                   |
| Memory per client        | a thread (stack + malloc arena)      | a 256 KiB coroutine stack, reused       |
| Slow client              | stalls broadcasts                    | buffered, then kicked                   |
| Shutdown                 | kill the process                     | clean, built in                         |
| Uses several CPU cores   | yes                                  | no, one core                            |
| Blocking calls (DB, disk)| fine                                 | **freeze everyone**: avoid or offload   |

**Use coroutines** for network-bound servers with many connections: chat, proxies,
gateways, pub/sub, game lobbies. The `httpd` builder project is built this way.
**Use threads** when each request does blocking or CPU-heavy work. **Or mix them:** a
coroutine server that hands heavy jobs to a `thread.Pool`, though getting the result
back to the right coroutine takes a pipe or socket per job to wait on.

## 9. Before you put it in production

- **Limits**: `MAX_CLIENTS` caps the client count (the list itself has no limit). Also raise the fd limit (`ulimit -n`), since
  each client is one fd.
- **Per-IP limits**: `net.peerAddr(fd)` gives the address. Count connections per address
  before you `coro.go`.
- **Protocol**: lines are fine for chat. For binary data, use length-prefixed frames with
  `bytes.Buffer.appendBe32` / `bytes.be32` (see the library guide, *Byte buffers*).
- **Stack size**: each coroutine gets 256 KiB by default (`coro.go(&Fn, arg, size)` changes
  it). Keep big buffers on the heap, not in coroutine locals. A stack overflow hits a guard
  page and crashes rather than corrupting memory.
- **No TLS**: the stdlib has none. Put the server behind a TLS terminator (nginx, stunnel,
  HAProxy), or link OpenSSL yourself with `#link <ssl>` and `extern "c"`.
- **Logging**: `printf` to stdout is buffered. For a log you watch live, use stderr, or
  flush stdout after each line.
