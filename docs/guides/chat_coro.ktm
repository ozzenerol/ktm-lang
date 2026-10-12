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
