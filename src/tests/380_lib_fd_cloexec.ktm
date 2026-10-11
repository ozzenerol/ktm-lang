import fs, libc, mem, net, proc, str, test;

/* The fds the library opens (sockets from the Net* helpers, accepted
 * connections, fs.readAll/fs.writeAll's file (also while fs.readAll reads a
 * FIFO to its end), Shlurp's temporary file) were
 * inherited by every program started with System, Sh, Shlurp or exec:
 * a child kept a listening socket or a client connection open. They are
 * close-on-exec now. Checked with F_GETFD, and by asking a shell started
 * with Shlurp which of the fds it has. */

extern "c" i32 mkfifo(u8 *path, u32 mode);
extern "c" u32 alarm(u32 seconds);

#define FIFO "./ktm-380.fifo"
#define USOCK "./ktm-380.sock"
const i64 BIG = (256 * 1024);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool closeOnExec(i32 fd) {
    i32 fl = libc.fcntl(fd, libc.F_GETFD);
    return fl != -1 && (fl & libc.FD_CLOEXEC) != 0;
}

/* Does a shell started by Shlurp have fd open? (`[ -e /dev/fd/N ]` opens
 * nothing, so the child can't mistake one of its own fds for ours) */
bool childHas(i32 fd) {
    u8 *out = proc.shellOutput("[ -e /dev/fd/%d ] && echo yes", fd);
    bool has = out && !str.cmp(out, "yes");
    mem.free(out);
    return has;
}

void kind(i32 fd, u8 *what) {
    u8 buf[128];
    if (fd < 0) {
        check(false, what);
        return;
    }
    str.formatTo(buf, "%s: FD_CLOEXEC", what);
    check(closeOnExec(fd), buf);
    str.formatTo(buf, "%s: not inherited", what);
    check(!childHas(fd), buf);
}

u8 *gBig;
void *writeFifo(void *arg)
{// Blocks in fs.writeAll until main drains the fifo
    fs.writeAll(FIFO, gBig, BIG, libc.O_WRONLY);
    return null;
}

void fileWriteFd() {
    i32 rd, probe, n, i;
    libc.pthread_t *th;
    u8 buf[4096];
    i64 got = 0;

    libc.unlink(FIFO);
    if (mkfifo(FIFO, 0x1A4) != 0) {
        check(false, "mkfifo");
        return;
    }
    /* Non-blocking so this open doesn't wait for a writer */
    rd = libc.open(FIFO, libc.O_RDONLY | libc.O_NONBLOCK | libc.O_CLOEXEC);
    check(rd != -1, "open the fifo for reading");
    /* The fd fs.writeAll will get: the lowest free one */
    probe = libc.open("/dev/null", libc.O_RDONLY | libc.O_CLOEXEC);
    libc.close(probe);
    gBig = mem.alloc(BIG);
    mem.set(gBig, 'x', BIG);
    libc.pthread_create(&th, null, &writeFifo);

    /* fs.writeAll has the fifo open once that fd exists; the pipe then fills
     * (it holds far less than BIG) and its write waits with the fd open.
     * (poll can't tell: on macOS it misses data already in a fifo.) */
    for (i = 0; i < 500 && libc.fcntl(probe, libc.F_GETFD) == -1; i++) { libc.poll(null, 0, 10); }
    check(libc.fcntl(probe, libc.F_GETFD) != -1, "FileWrite opened the fifo");
    check(closeOnExec(probe), "FileWrite: FD_CLOEXEC");
    check(!childHas(probe), "FileWrite: not inherited");

    /* Drain to EOF before the join, with blocking reads: EOF comes when
     * fs.writeAll has written everything and closed its end. (alarm in Main
     * ends the test if this ever stalls.) */
    libc.fcntl(rd, libc.F_SETFL, libc.fcntl(rd, libc.F_GETFL) & ~libc.O_NONBLOCK);
    while ((n = libc.read(rd, buf, sizeof(buf))) > 0) { got += n; }
    libc.pthread_join(th);
    check(got == BIG, "the whole buffer arrived");
    libc.close(rd);
    libc.unlink(FIFO);
    mem.free(gBig);
}

/* fs.readAll's fd: the FIFO fd that isn't `except` */
i32 fifoFd(i32 except) {
    libc.cStat st;
    i32 fd;
    for (fd = 3; fd < 256; fd++) {
        if (fd != except && libc.fstat(fd, &st) == 0 && (st.st_mode & libc.S_IFMT) == libc.S_IFIFO) {
            return fd;
        }
    }
    return -1;
}

u8 *gRead;
i64 gReadLen;
void *readFifo(void *arg)
{// Blocks in fs.readAll (reading to EOF) until main closes its end
    gRead = fs.readAll(FIFO, &gReadLen);
    return null;
}

void fileReadFd() {
    i32 wr = -1, fd = -1, i;
    libc.pthread_t *th;

    libc.unlink(FIFO);
    if (mkfifo(FIFO, 0x1A4) != 0) {
        check(false, "mkfifo 2");
        return;
    }
    libc.pthread_create(&th, null, &readFifo);
    /* A non-blocking open for writing works once fs.readAll has it open */
    for (i = 0; i < 500 && wr == -1; i++) {
        wr = libc.open(FIFO, libc.O_WRONLY | libc.O_NONBLOCK | libc.O_CLOEXEC);
        if (wr == -1) { libc.poll(null, 0, 10); }
    }
    check(wr != -1, "FileRead opened the fifo");
    /* Never leave the thread waiting in open(): a blocking open for writing
     * returns as soon as it has the fifo open */
    if (wr == -1) { wr = libc.open(FIFO, libc.O_WRONLY | libc.O_CLOEXEC); }
    /* Nothing written yet: fs.readAll waits in its read with the fd open */
    for (i = 0; i < 200 && fd == -1; i++) {
        fd = fifoFd(wr);
        if (fd == -1) { libc.poll(null, 0, 10); }
    }
    check(fd != -1 && closeOnExec(fd), "FileRead: FD_CLOEXEC");
    check(fd != -1 && !childHas(fd), "FileRead: not inherited");
    libc.write(wr, "fifo data", 9);
    libc.close(wr);
    libc.pthread_join(th);
    check(gRead && gReadLen == 9 && !str.cmp(gRead, "fifo data"),
            "FileRead got the data");
    mem.free(gRead);
    libc.unlink(FIFO);
}

i32 main() {
    i32 plain, srv, cli, acc, usrv, ucli, uacc, udp, port;
    printf("Test - library fds are close-on-exec: ");
    alarm(60);   /* a hang ends the test (SIGALRM) instead of the suite */

    /* The check works: an fd opened without O_CLOEXEC is inherited */
    plain = libc.open("/dev/null", libc.O_RDONLY);
    check(plain != -1 && !closeOnExec(plain), "plain open has no FD_CLOEXEC");
    check(childHas(plain), "the child sees an inheritable fd");
    libc.close(plain);

    srv = net.listen(0, "127.0.0.1", 4);
    kind(srv, "NetCreateServer");
    port = net.localPort(srv);
    cli = net.dial("127.0.0.1", port);
    kind(cli, "NetConnect");
    acc = net.accept(srv);
    kind(acc, "NetAccept");

    libc.unlink(USOCK);
    usrv = net.unixListen(USOCK, 4);
    kind(usrv, "NetCreateUnixServerSocket");
    ucli = net.unixDial(USOCK);
    kind(ucli, "NetCreateUnixClientSocket");
    uacc = net.accept(usrv);
    kind(uacc, "NetAccept on a unix socket");

    udp = net.udpSocket(0, "127.0.0.1");
    kind(udp, "NetCreateUdpSocket");

    libc.close(udp);
    libc.close(uacc); libc.close(ucli); libc.close(usrv);
    libc.unlink(USOCK);
    libc.close(acc); libc.close(cli); libc.close(srv);

    fileWriteFd();
    fileReadFd();

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
