# Guide 1: a multi-client TCP server with threads

This guide builds a small **chat server**. Many clients connect over TCP at the same time,
everything one client types goes to all the others, and the server keeps working when
clients join, leave or misbehave. It uses the simplest model there is: **one thread per
client**, with blocking reads and writes.

By the end you will have about 140 lines of ktm that:

- listen on a port and accept any number of clients (up to a limit),
- give each client its own thread,
- split the byte stream into lines correctly, even when a line arrives in pieces,
- share a list of clients between threads, safely, behind a mutex,
- handle a client that disconnects, sends junk, or stops reading.

Written and tested with **ktm v0.5.1** on Linux x86_64, both compiled and in the JIT.
The full program is [`chat_threads.ktm`](chat_threads.ktm), next to this file.

[Guide 2](02-coroutine-tcp-server.md) builds the same server on coroutines, in one thread,
and adds idle timeouts, output buffering and a clean shutdown.

---

## 1. Listen and accept

A TCP server is a listening socket plus a loop that accepts connections:

```c
import libc, net;

i32 main()
{
    i32 srv = net.listen(7000, "*", 128);   // port, bind address, backlog
    if (srv == -1) {
        printf("can't listen\n");
        return 1;
    }
    while (true) {
        i32 fd = net.accept(srv);           // blocks until a client connects
        if (fd == -1) { continue; }
        net.send(fd, "hello\n");
        libc.close(fd);
    }
    return 0;
}
```

- `net.listen(port, bindaddr, backlog)` returns the socket as an `i32`, or `-1`.
  `"*"` means every IPv4 address, `"::*"` every IPv6 address, `"127.0.0.1"` only local
  clients. Port `0` lets the kernel pick one, and `net.localPort(srv)` tells you which.
  `SO_REUSEADDR` is already set, so a restarted server can bind again at once.
- `net.accept(srv)` returns the new connection as a **blocking** socket. `-1` is
  not fatal: a client can give up while it waits in the queue, or you can run out of
  file descriptors. Log it and carry on.
- **Use `net.send`, not `libc.write`, on sockets.** Writing to a client that has gone
  raises `SIGPIPE`, which kills the whole server, as in C. `net.send` adds
  `MSG_NOSIGNAL`, so you get `-1` instead.

This server answers one client at a time. While it talks to one, nobody else gets
through. The rest of the guide fixes that.

## 2. A thread per client

The accept loop hands each connection to a new thread and goes straight back to
`accept`:

```c
void *clientMain(void *arg)
{
    i32 fd = (i32)(i64)arg;
    // ... talk to this client ...
    libc.close(fd);
    return null;
}

// in the accept loop:
libc.pthread_t *th;
if (libc.pthread_create(&th, null, &clientMain, (void *)(i64)fd) != 0) {
    libc.close(fd);                 // couldn't start a thread: drop this client
    continue;
}
libc.pthread_detach(th);
```

- ktm's threads are POSIX threads. The function has the type `void *F(void *)`, and the
  fd goes in as the argument, cast through `i64`.
- In ktm the thread handle is the pointer itself: declare `libc.pthread_t *th;`, pass
  `&th` to `pthread_create` and `th` to `pthread_detach`/`pthread_join`. Don't
  dereference it.
- `pthread_detach` matters. Nobody will `join` these threads, and a detached thread's
  resources are freed when it ends. Without it, every client that has come and gone
  leaks a thread.

## 3. Reading lines from a stream

TCP delivers a **stream of bytes, not messages**. One `read` can return half a line,
or three lines and the start of a fourth. Every protocol has to deal with this, and a
`bytes.Buffer` is the tool for it: read onto the end, take whole lines off the front,
and leave the rest for the next read.

```c
bytes.Buffer in = bytes.bufferNew();
defer in.free();

while (true) {
    i64 n = in.readFrom(fd);            // one read(2) onto the end of the buffer
    if (n <= 0) { break; }              // 0: client closed, -1: error

    i64 nl;
    while ((nl = in.data().find("\n")) != -1) {
        u8[] line = in.data()[0:nl].trimEnd("\r");   // telnet and nc on Windows send \r\n
        handleLine(fd, line);
        in.consume(nl + 1);             // drop the line and its \n
    }
    if (in.len > MAX_LINE) { break; }   // no newline in 1 KiB: not our protocol
}
```

- `in.data()` is a **view** (`u8[]`) of the unread bytes. Nothing is copied, and
  `line` is valid until the next `consume` or `readFrom`.
- `find`, `trimEnd`, `equals` and the slice `[0:nl]` work on any `u8[]`. Slices are
  bounds-checked.
- The `MAX_LINE` check is your protection against a client that sends megabytes with no
  newline. Without it, the buffer grows until memory runs out.

## 4. Shared state and a mutex

To broadcast, every thread needs the list of connected clients. It's kept in a
`list.List`, a circular doubly linked list of `void *` values, with each client's fd
stored as the value. Several threads change it at the same time, so every access goes
through one lock:

```c
list.List gClients;              // gClients.init() in main
libc.pthread_mutex_t gLock;      // libc.pthread_mutex_init(&gLock) in main

void broadcast(i32 from, u8[] msg)
{
    libc.pthread_mutex_lock(&gLock);
    for (list.List *it = gClients.next; it != &gClients; it = it.next) {
        i32 to = (i32)(i64)it.value;
        if (to != from) { net.send(to, msg); }
    }
    libc.pthread_mutex_unlock(&gLock);
}
```

How the list works:

- **The list is its head node.** A global `list.List gClients;` is the head, and
  `gClients.init()` makes it an empty list (a head linked to itself). `list.new()` would
  allocate one instead.
- **Walk it from `head.next` until you're back at the head.** That's the `for` loop above.
- **Values are `void *`**, so an fd goes in as `(void *)(i64)fd` and comes out as
  `(i32)(i64)it.value`.
- `gClients.append(v)` adds at the end. `it.remove()` unlinks a node and frees the
  **node**, but not the value. `gClients.count()` walks the list, so it is O(n), which is
  fine for a few dozen clients.

Removing a client walks the list for its fd:

```c
for (list.List *it = gClients.next; it != &gClients; it = it.next) {
    if ((i32)(i64)it.value == fd) {
        it.remove();          // fine here: we break before using `it` again
        break;
    }
}
```

When you remove *and keep walking*, take `it.next` before `it.remove()`, because the node
is freed.

The rules that keep this correct:

1. **Any read or write of `gClients` happens under the lock**, including the
   `count()` in `/who` and in the "server full" check.
2. **Add the client in the accept loop, before its thread starts.** That's also where
   "server full" can be refused cleanly.
3. **Remove the client before closing its fd.** Otherwise the kernel can reuse the fd
   number for a new connection while another thread still broadcasts to the old one, and a
   stranger gets the message.
4. **Never `return` with the lock held.** In this program every lock and unlock are a few
   lines apart, which makes that easy to check by eye.

### The slow-client problem

`net.send` on a blocking socket waits when the client's receive buffer is full, and
`broadcast` holds the lock while it waits. **One client that stops reading freezes
every other thread at its next broadcast.** That's the main weakness of this design. The
program limits the damage with a send timeout:

```c
net.setSendTimeout(fd, 2000);   // a blocked send gives up after 2 s with -1
```

A stalled client now costs everyone at most 2 seconds per message. [Guide 2](02-coroutine-tcp-server.md)
fixes this properly with a per-client output buffer.

## 5. The protocol

Lines from the client:

| Line      | Effect                                     |
|-----------|--------------------------------------------|
| `/quit`   | leave                                      |
| `/who`    | how many clients are online                |
| any text  | sent to everyone else as `<address>: text` |

When a client leaves, for any reason, the others see `* <address> left`.

## 6. The full program

```c
import bytes, cstr, libc, list, net, str;

#define MAX_CLIENTS 64
#define MAX_LINE    1024

/* Everything the threads share, and the lock that guards it: the list
 * holds each client's fd as its value. */
list.List gClients;
libc.pthread_mutex_t gLock;

/* Add fd to the client list: false when the server is full. */
bool addClient(i32 fd)
{
    bool ok = false;
    libc.pthread_mutex_lock(&gLock);
    if (gClients.count() < MAX_CLIENTS) {
        gClients.append((void *)(i64)fd);
        ok = true;
    }
    libc.pthread_mutex_unlock(&gLock);
    return ok;
}

void removeClient(i32 fd)
{
    libc.pthread_mutex_lock(&gLock);
    for (list.List *it = gClients.next; it != &gClients; it = it.next) {
        if ((i32)(i64)it.value == fd) {
            it.remove();                    /* frees the node, not the fd */
            break;
        }
    }
    libc.pthread_mutex_unlock(&gLock);
}

/* Send msg to every client except `from` (-1: to everyone). */
void broadcast(i32 from, u8[] msg)
{
    libc.pthread_mutex_lock(&gLock);
    for (list.List *it = gClients.next; it != &gClients; it = it.next) {
        i32 to = (i32)(i64)it.value;
        if (to != from) {
            net.send(to, msg);              /* -1 if that client left: its own thread cleans up */
        }
    }
    libc.pthread_mutex_unlock(&gLock);
}

/* Handle one line from a client. Returns false when the client wants to leave. */
bool handleLine(i32 fd, String *name, u8[] line)
{
    if (line.equals("/quit")) {
        return false;
    }
    if (line.equals("/who")) {
        libc.pthread_mutex_lock(&gLock);
        String reply = str.format("* %d online\n", gClients.count());
        libc.pthread_mutex_unlock(&gLock);
        net.send(fd, reply);
        reply.free();
        return true;
    }
    String msg = str.format("%s: %s\n", *name, line);
    defer msg.free();
    broadcast(fd, msg);
    return true;
}

/* One thread per client: read lines, broadcast them, clean up. */
void *clientMain(void *arg)
{
    i32 fd = (i32)(i64)arg;
    bytes.Buffer in = bytes.bufferNew();
    defer in.free();
    String name = net.peerAddr(fd);         /* until the client picks one */
    defer name.free();

    net.setSendTimeout(fd, 2000);           /* a stuck client can't stall a broadcast for long */
    net.send(fd, "welcome! type /quit to leave, /who to count users\n");

    bool open = true;
    while (open) {
        i64 n = in.readFrom(fd);
        if (n <= 0) { break; }              /* 0: the client closed, -1: an error */

        /* the buffer can hold several lines, or half of one */
        i64 nl;
        while (open && (nl = in.data().find("\n")) != -1) {
            u8[] line = in.data()[0:nl].trimEnd("\r");
            open = handleLine(fd, &name, line);
            in.consume(nl + 1);
        }
        if (in.len > MAX_LINE) {
            net.send(fd, "line too long, bye\n");
            break;
        }
    }

    removeClient(fd);
    String bye = str.format("* %s left\n", name);
    broadcast(-1, bye);
    bye.free();
    libc.close(fd);
    return null;
}

i32 main(i32 argc, u8 **argv)
{
    i32 port = argc > 1 ? (i32)cstr.toInt(argv[1]) : 7000;

    gClients.init();
    libc.pthread_mutex_init(&gLock);
    i32 srv = net.listen(port, "*", 128);
    if (srv == -1) {
        printf("can't listen on port %d\n", port);
        return 1;
    }
    printf("chat server on port %d\n", net.localPort(srv));

    while (true) {
        i32 fd = net.accept(srv);
        if (fd == -1) { continue; }         /* a client that gave up while queued, out of fds, ... */
        if (!addClient(fd)) {
            net.send(fd, "server full\n");
            libc.close(fd);
            continue;
        }
        libc.pthread_t *th;
        if (libc.pthread_create(&th, null, &clientMain, (void *)(i64)fd) != 0) {
            removeClient(fd);
            libc.close(fd);
            continue;
        }
        libc.pthread_detach(th);            /* nobody joins it: its resources go when it ends */
    }
    return 0;
}
```

Build and run it:

```sh
ktm chat_threads.ktm -o chat_threads
./chat_threads 7000
# or without compiling:
ktm -jit chat_threads.ktm 7000
```

Then connect a few terminals with `nc 127.0.0.1 7000` and chat.

## 7. Testing it properly

Typing into `nc` doesn't test the hard cases. A short script does. This was the test for
this guide:

```python
import socket, time
def conn():
    s = socket.create_connection(("127.0.0.1", 7000)); s.settimeout(0.3); return s
def drain(s):
    time.sleep(0.3); out = b""
    try:
        while (d := s.recv(4096)): out += d
    except socket.timeout: pass
    return out.decode()

a, b, c = conn(), conn(), conn()
for s in (a, b, c): drain(s)
a.sendall(b"hello there\r\n")      # \r\n is trimmed
c.sendall(b"one\ntw")              # two lines in one packet...
time.sleep(0.1); c.sendall(b"o\n") # ...and one split across two
print(drain(b))                    # hello there, one, two: each whole, in order
```

Things worth checking on any server you write:

- a line split across two `send`s, and several lines in one,
- a client that disconnects mid-line, or with `kill -9` on `nc`,
- a client that never reads (the slow-client problem above),
- a client that sends a long stream with no newline,
- many clients at once: open `MAX_CLIENTS` sockets from the script, check `/who`, then one
  more, which should get `server full`,
- leaks: `ktm -jit -Memsafe chat_threads.ktm 7000` reports double frees as they happen
  and leaks at exit, in any thread.

## 8. When this design is right, and when it isn't

**Good for:** tens to a few hundred clients, servers where each request does blocking
work (a database, a file, an external program), and code that should read top to bottom.
Every thread is plain sequential code.
The `socks5` builder project is a real proxy built this way: one thread per client,
each relaying with its own `poll()` loop.

**Limits:**

- Each thread has its own stack (8 MiB of address space on Linux by default, though only
  the touched pages use memory), and glibc reserves a 64 MiB malloc arena for each thread
  that allocates. Under a virtual-memory limit (`ulimit -v`, some containers), `pthread_create`
  starts failing sooner than you'd expect: with `ulimit -v 4000000` this server ran out at about
  55 threads. The accept loop handles that by dropping the client, but tens of thousands of
  threads is too many.
- Shared state needs locks, and a lock held across a blocking call is how one bad client
  stalls everyone.
- There is no clean shutdown. Ctrl-C kills the process mid-write. A blocked thread can't
  easily be told to stop.
- `MAX_CLIENTS` is a policy, not a size: the list has no fixed capacity, and the cap only
  keeps the thread count sane.

**Variations:**

- **A thread pool**: `thread.poolNew(n)` and `thread.poolEnqueue(pool, arg, &Fn)` run jobs
  on `n` worker threads. This suits short request/response protocols (read one request,
  answer, close), where it caps the thread count. It doesn't suit long-lived connections:
  each one keeps a worker busy for as long as it is open.
- **Per-thread read timeouts**: `net.setRecvTimeout(fd, ms)` makes `readFrom` return `-1`
  (errno `EAGAIN`) after `ms` without data. That's a simple way to drop idle clients.

The next guide removes the threads, the locks and the slow-client stall: one thread
running one coroutine per client.
