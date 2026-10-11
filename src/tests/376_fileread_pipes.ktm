import fs, libc, mem, str, test;

/* FileRead only read regular files, up to their size: a FIFO or a pipe
 * gave null, and so did /proc files, which report size 0. Now anything
 * but a directory is read until end of file, and *_size is its length. */

extern "c" i32 mkfifo(u8 *path, u32 mode);
extern "c" i32 pipe(i32 *fds);

#define FIFO_PATH "__376_fifo"
#define REG_PATH  "__376_regular"
#define DIR_PATH  "__376_dir"
const i64 BIG_LEN = 10000; /* more than one 4096-byte read */

i32 fifoFd = -1;

/* BIG_LEN bytes, 'a' to 'z' over and over */
u8 *bigText() {
    u8 *s = mem.alloc(BIG_LEN + 1);
    for (i64 i = 0; i < BIG_LEN; i++) { s[i] = 'a' + i % 26; }
    s[BIG_LEN] = '\0';
    return s;
}

bool isBigText(u8 *s, i64 len) {
    if (!s || len != BIG_LEN || str.len(s) != BIG_LEN) { return false; }
    for (i64 i = 0; i < BIG_LEN; i++) {
        if (s[i] != 'a' + i % 26) { return false; }
    }
    return true;
}

/* writes the text to the FIFO (opening it waits for the reader), or to
 * the pipe's write end when fifo_fd is set, then closes it */
void *writer(void *arg) {
    u8 *text = bigText();
    i32 fd = fifoFd;
    if (fd < 0) { fd = libc.open((u8 *)arg, libc.O_WRONLY, 0); }
    if (fd >= 0) {
        libc.write(fd, text, BIG_LEN);
        libc.close(fd);
    }
    mem.free(text);
    return null;
}

void cleanup() {
    libc.unlink(FIFO_PATH);
    libc.unlink(REG_PATH);
    libc.rmdir(DIR_PATH);
}

i32 main() {
    printf("Test - FileRead pipes and /proc: ");
    i64 correct = 0, tests = 0, len;
    u8 *s;
    libc.pthread_t *th;
    cleanup();

    /* /proc: size 0, but has content (Linux only) */
#ifdef IS_MACOS
    tests++; correct++;
#else
    len = -1;
    s = fs.readAll("/proc/self/status", &len);
    tests++;
    if (s && len > 0 && len == str.len(s) && !str.cmpLen(s, "Name:", 5)) { correct++; }
    else { printf("/proc/self/status: %s, len %d ", s, len); }
    mem.free(s);
#endif

    /* a FIFO, filled by another thread */
    tests++;
    if (mkfifo(FIFO_PATH, 420) == 0) {
        libc.pthread_create(&th, null, &writer, FIFO_PATH);
        len = -1;
        s = fs.readAll(FIFO_PATH, &len);
        libc.pthread_join(th);
        if (isBigText(s, len)) { correct++; } else { printf("fifo: len %d ", len); }
        mem.free(s);
    } else { printf("mkfifo failed "); }

    /* a pipe, through /dev/fd/N */
    i32 fds[2];
    tests++;
    if (libc.pipe(fds) == 0) {
        u8 *path = str.formatTo(null, "/dev/fd/%d", fds[0]);
        fifoFd = fds[1];
        libc.pthread_create(&th, null, &writer, null);
        len = -1;
        s = fs.readAll(path, &len);
        libc.pthread_join(th);
        libc.close(fds[0]);
        if (isBigText(s, len)) { correct++; } else { printf("pipe: len %d ", len); }
        mem.free(s);
        mem.free(path);
    } else { printf("pipe failed "); }

    /* regular files still read by size, an empty one as "" */
    u8 *text = bigText();
    fs.writeAll(REG_PATH, text, BIG_LEN);
    s = fs.readAll(REG_PATH, &len);
    tests++; if (isBigText(s, len)) { correct++; } else { printf("regular: len %d ", len); }
    mem.free(s);
    mem.free(text);
    fs.writeAll(REG_PATH, "", 0);
    len = -1;
    s = fs.readAll(REG_PATH, &len);
    tests++; if (s && len == 0 && s[0] == '\0') { correct++; } else { printf("empty file "); }
    mem.free(s);

    /* a directory or a missing file: null and size 0 */
    libc.mkdir(DIR_PATH);
    len = -1;
    s = fs.readAll(DIR_PATH, &len);
    tests++; if (!s && len == 0) { correct++; } else { printf("directory read "); }
    len = -1;
    s = fs.readAll("__376_missing", &len);
    tests++; if (!s && len == 0) { correct++; } else { printf("missing file read "); }

    cleanup();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
