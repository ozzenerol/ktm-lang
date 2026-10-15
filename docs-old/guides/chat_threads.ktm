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
