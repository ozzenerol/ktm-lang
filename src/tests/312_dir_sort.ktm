#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* Dir sorted names that start with a number by that number only: names
 * with the same number came out in any order, a number too big for an
 * i64 overflowed, and its own quicksort went quadratic (and n deep) on
 * equal keys. Entries whose Stat failed, like a dangling symlink, were
 * left out. Everything happens in a fresh mkdtemp directory. */

extern "c" u8 *mkdtemp(u8 *template);
extern "c" i32 symlink(u8 *target, u8 *linkpath);
extern "c" i32 dup(i32 fd);
extern "c" i32 dup2(i32 fd, i32 fd2);
extern "c" i32 fflush(void *stream);

u8 gOut[256];
i32 gSaved;

void capture() {
    libc.fflush(null);
    gSaved = dup(1);
    i32 fd = libc.open(gOut, libc.O_WRONLY|libc.O_CREAT|libc.O_TRUNC, 0x1A4);
    dup2(fd, 1);
    libc.close(fd);
}

u8 *captured() {
    libc.fflush(null);
    dup2(gSaved, 1);
    libc.close(gSaved);
    u8 *got = fs.readAll(gOut);
    fs.remove(gOut);
    return got;
}

void touch(u8 *dir, u8 *name) {
    u8 *p = str.formatTo(null, "%s/%s", dir, name);
    fs.writeAll(p, "", 0);
    mem.free(p);
}

i32 main() {
    i64 correct = 0, tests = 6, n, i;
    u8 root[64], *d, *big, *out, *p, name[32];
    u8 *names[17] = {"7_j", "7_c", "7_a", "7_h", "7_e", "7_b", "7_i", "7_d",
                                     "7_g", "7_f", "18446744073709551617", "3", "2.5", "2.10",
                                     "02", "B", "a"};
    printf("Test - Dir sort order: ");

    str.copy(root, "./ktm-test-312-XXXXXX");
    if (!mkdtemp(root)) {
        PrintResult(0, tests);
        printf("====\n");
        return 0;
    }
    str.formatTo(gOut, "%s/out.txt", root);
    d = str.formatTo(null, "%s/d", root);
    big = str.formatTo(null, "%s/big", root);
    fs.makeDir(d);
    fs.makeDir(big);
    for (i = 0; i < 17; i++) { touch(d, names[i]); }
    p = str.formatTo(null, "%s/dangling", d);
    symlink("nowhere", p);
    mem.free(p);

    capture();
    n = Dir(d);
    out = captured();
    if (n == 18) { correct++; }
    else { printf("count %d\n", n); }
    if (out && !str.cmp(out,
            "02\n2.10\n2.5\n3\n7_a\n7_b\n7_c\n7_d\n7_e\n7_f\n7_g\n7_h\n7_i\n7_j\n"
            "18446744073709551617\nB\na\ndangling\n")) { correct++; }
    else { printf("got:\n%s\n", out); }
    mem.free(out);

    /* Many names with the same leading number */
    for (i = 0; i < 3000; i++) {
        str.formatTo(name, "1_%d", i);
        touch(big, name);
    }
    capture();
    n = Dir(big);
    out = captured();
    if (n == 3000) { correct++; }
    if (out && !str.cmpLen(out, "1_0\n1_1\n1_10\n1_100\n1_1000\n1_1001\n", 33)) {
        correct++;
    }
    if (out && str.len(out) > 6 && !str.cmp(out + str.len(out) - 6, "\n1_999\n"+1)) {
        correct++;
    }
    mem.free(out);

    /* An empty directory */
    p = str.formatTo(null, "%s/empty", root);
    fs.makeDir(p);
    capture();
    n = Dir(p);
    out = captured();
    if (n == 0 && out && !str.cmp(out, "")) { correct++; }
    mem.free(out);
    mem.free(p);

    mem.free(d);
    mem.free(big);
    p = str.formatTo(null, "rm -rf '%s'", root);
    proc.shell(p);
    mem.free(p);

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
