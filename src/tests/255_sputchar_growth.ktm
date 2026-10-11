import test;

import mem, str;

/* SPutChar grew a full buffer with MAlloc(i<<1+1), which ktm parses as
 * i<<(1+1): from an empty 1-byte buffer (i=0) it allocated 0 bytes and
 * wrote the char and its NUL past the block. It now always has room for
 * the char and the NUL (valgrind checks the AOT binary). */

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - SPutChar growth: ");

    /* from a 1-byte buffer, one char at a time */
    u8 *buf = mem.alloc(1), *dst = buf;
    buf[0] = '\0';
    bool ok = true;
    for (i64 i = 0; i < 1000; ++i) {
        SPutChar(&dst, 'a' + i % 26, &buf);
        if (dst - buf != i + 1 || mem.size(buf) < i + 2 || *dst != '\0') { ok = false; }
    }
    for (i64 i = 0; i < 1000; ++i) { if (buf[i] != 'a' + i % 26) { ok = false; } }
    tests++; if (ok && str.len(buf) == 1000) { correct++; }
    mem.free(buf);

    /* from buffers of every small size, and with a buffer that has room */
    ok = true;
    for (i64 size = 1; size < 10; ++size) {
        buf = mem.alloc(size);
        buf[0] = '\0';
        dst = buf;
        for (i64 i = 0; i < 20; ++i) { SPutChar(&dst, 'x', &buf); }
        if (str.len(buf) != 20 || dst != buf + 20) { ok = false; }
        mem.free(buf);
    }
    tests++; if (ok) { correct++; }

    /* without _buf it just writes */
    u8 fixed[8];
    dst = fixed;
    SPutChar(&dst, 'h', null);
    SPutChar(&dst, 'i', null);
    tests++; if (!str.cmp(fixed, "hi")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
