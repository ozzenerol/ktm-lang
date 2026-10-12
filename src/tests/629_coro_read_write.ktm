import coro, date, libc, net, os, test;

extern "c" i32 socketpair(i32 domain, i32 type, i32 protocol, i32 *sv);

/* coro.read / coro.write (v0.11.0): read what is there (waiting when
 * nothing is), write all of it (waiting while the fd is full), 0 at end
 * of file, -1 with ETIMEDOUT on a timeout and EPIPE (no SIGPIPE) for a
 * peer that closed; on sockets and on pipes */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s (errno %d)\n", what, os.errno()); }
}

const i64 BIG = 4 * 1024 * 1024;
i32 gS[2];
u8 *gOut;
u8 *gIn;
i64 gSent = -2, gGot = 0, gEof = -2;

void *bigWriter(void *unused) {
    gSent = coro.write(gS[0], gOut[0:BIG]);
    libc.shutdown(gS[0], libc.SHUT_WR);
    return null;
}

void *bigReader(void *unused) {
    u8 buf[65536];
    while (true) {
        i64 n = coro.read(gS[1], buf[0:65536]);
        if (n <= 0) {
            gEof = n;
            break;
        }
        libc.memcpy(gIn + gGot, buf, n);
        gGot += n;
    }
    return null;
}

i64 gTimeout = -2, gTimeoutErr = 0, gElapsed = 0;

void *timesOut(void *unused) {
    u8 buf[16];
    i64 t0 = date.monoMs();
    gTimeout = coro.read(gS[1], buf[0:16], 50);
    gTimeoutErr = os.errno();
    gElapsed = date.monoMs() - t0;
    return null;
}

i32 gP[2];
i64 gPipeW = -2, gPipeR = -2;
u8 gPipeBuf[16];

void *pipeWriter(void *unused) {
    gPipeW = coro.write(gP[1], "via pipe");
    return null;
}

void *pipeReader(void *unused) {
    gPipeR = coro.read(gP[0], gPipeBuf[0:16]);
    return null;
}

i64 gEpipe = -2, gEpipeErr = 0;

void *toClosed(void *unused) {
    u8 chunk[65536];
    libc.memset(chunk, 'x', 65536);
    for (i64 i = 0; i < 64 && gEpipe != -1; i++) {
        gEpipe = coro.write(gS[0], chunk[0:65536]);
        gEpipeErr = os.errno();
    }
    return null;
}

i32 main() {
    printf("Test - coro.read and coro.write: ");

    gOut = libc.malloc(BIG);
    gIn = libc.malloc(BIG);
    for (i64 i = 0; i < BIG; i++) { gOut[i] = (u8)(i * 7 + i / 251); }
    check(socketpair(libc.AF_UNIX, libc.SOCK_STREAM, 0, gS) == 0, "a pair");
    net.setNonBlocking(gS[0]);
    net.setNonBlocking(gS[1]);
    coro.go(&bigWriter);
    coro.go(&bigReader);
    coro.run();
    check(gSent == BIG, "write sends all 4 MB, waiting while the socket is full");
    check(gGot == BIG && libc.memcmp(gOut, gIn, BIG) == 0, "read gets every byte in order");
    check(gEof == 0, "read returns 0 at end of file");
    libc.close(gS[0]);
    libc.close(gS[1]);

    socketpair(libc.AF_UNIX, libc.SOCK_STREAM, 0, gS);
    net.setNonBlocking(gS[1]);
    coro.go(&timesOut);
    coro.run();
    check(gTimeout == -1 && gTimeoutErr == libc.ETIMEDOUT, "read times out with -1 and ETIMEDOUT");
    check(gElapsed >= 40 && gElapsed < 1000, "after about its 50 ms");

    /* a peer that closed: EPIPE, the program goes on */
    net.setNonBlocking(gS[0]);
    libc.close(gS[1]);
    coro.go(&toClosed);
    coro.run();
    check(gEpipe == -1 && (gEpipeErr == libc.EPIPE || gEpipeErr == libc.ECONNRESET),
          "write to a closed peer: -1 with EPIPE, no SIGPIPE");
    libc.close(gS[0]);

    check(libc.pipe(gP) == 0, "a pipe");
    net.setNonBlocking(gP[0]);
    net.setNonBlocking(gP[1]);
    coro.go(&pipeReader);
    coro.go(&pipeWriter);
    coro.run();
    check(gPipeW == 8 && gPipeR == 8 && libc.memcmp(gPipeBuf, "via pipe", 8) == 0, "both work on a pipe");
    libc.close(gP[0]);
    libc.close(gP[1]);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
