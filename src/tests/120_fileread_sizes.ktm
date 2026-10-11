import fs, libc, mem, test;

/* fs.readAll asked read() for the whole file length on every call, even
 * after the buffer was full, so the EOF probe claimed bytes past the end
 * of the buffer. A real kernel returns 0 without looking, but qemu-user
 * checks the range and fails with EFAULT when it crosses the end of the
 * heap, and fs.readAll then returned null. That only shows under qemu with
 * an unlucky heap layout (39_arg_count.ktm under the arm64 JIT caught it);
 * this test pins down that the bounded read loop returns every byte. */

#define FILEREAD_TEST_FILE "__120_fileread.bin"

bool readBackMatches(i64 len) {
    u8 *buf = mem.alloc(len + 1);
    for (i64 i = 0; i < len; i++) {
        buf[i] = 'a' + i % 26;
    }
    buf[len] = '\0';
    if (!fs.writeAll(FILEREAD_TEST_FILE, buf[0:len], libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        mem.free(buf);
        return false;
    }

    i64 size = -1;
    String got_read = fs.readAll(FILEREAD_TEST_FILE);
    size = got_read.len;
    u8 *got = got_read.take();
    bool ok = got != null && size == len && libc.memcmp(got, buf, len) == 0 &&
                        got[len] == '\0';
    if (!ok) {
        printf("FileRead of %d bytes: got %p, size %d\n", len, got, size);
    }
    mem.free(got);
    mem.free(buf);
    fs.remove(FILEREAD_TEST_FILE);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    i64 sizes[6] = {0, 1, 99, 4096, 4097, 1 << 20};
    printf("Test - FileRead returns the whole file: ");
    for (i64 i = 0; i < 6; i++) {
        tests++;
        if (readBackMatches(sizes[i])) { correct++; }
    }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
