import test;

import libc, mem;

/* timeval.tv_usec was an i32 everywhere, but Linux's is a long: the upper
 * half of a stack timeval was whatever was in memory, so setsockopt
 * SO_RCVTIMEO (and select, utimes) got a garbage microsecond count and
 * failed. macOS has an int plus 4 bytes of padding. */

#ifdef IS_LINUX
const i64 TEST_SO_RCVTIMEO = 20;
#else
const i64 TEST_SO_RCVTIMEO = 0x1006;
#endif

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

void stale(libc.timeval *tv)
{// What an uninitialised stack variable may hold
    mem.set(tv, 0xAB, sizeof(libc.timeval));
}

i32 main() {
    libc.timeval tv, got;
    u32 len = sizeof(libc.timeval);
    i32 fd;
    bool set;
    u8 buf[4];
    printf("Test - timeval tv_usec: ");

    check(sizeof(libc.timeval) == 16, "sizeof(timeval)");
    check((u8 *)(&tv.tv_usec) - (u8 *)(&tv) == 8, "tv_usec offset");
#ifdef IS_LINUX
    check(sizeof(tv.tv_usec) == 8, "tv_usec is a long on Linux");
#else
    Check(sizeof(tv.tv_usec) == 4, "tv_usec is an int on macOS");
#endif

    /* gettimeofday fills the whole field */
    stale(&tv);
    check(libc.gettimeofday(&tv, null) == 0, "gettimeofday");
    check(tv.tv_usec >= 0 && tv.tv_usec < 1000000, "gettimeofday tv_usec range");

    /* A receive timeout from a stale timeval */
    fd = libc.socket(libc.AF_INET, libc.SOCK_DGRAM, 0);
    check(fd != -1, "socket");
    stale(&tv);
    tv.tv_sec = 0;
    tv.tv_usec = 200000;
    set = libc.setsockopt(fd, libc.SOL_SOCKET, TEST_SO_RCVTIMEO, &tv, sizeof(libc.timeval)) == 0;
    check(set, "setsockopt SO_RCVTIMEO with a stale timeval");
    stale(&got);
    check(libc.getsockopt(fd, libc.SOL_SOCKET, TEST_SO_RCVTIMEO, &got, &len) == 0,
            "getsockopt SO_RCVTIMEO");
    check(got.tv_sec == 0 && got.tv_usec >= 190000 && got.tv_usec <= 210000,
            "the timeout reads back as 0.2 s");
    /* Unbound UDP socket, nothing to read: recv gives up after the timeout */
    check(set && libc.recv(fd, buf, 4, 0) == -1, "recv times out"); // or blocks forever
    libc.close(fd);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
