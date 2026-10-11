#include "testhelper.ktm"

import libc, mem, str;

/* Taking the address of a function this object doesn't define - a C
 * library function declared `extern "c"`, or a libktm function only
 * prototyped in std.ktmh - was compiled to a PC-relative `leaq sym(%rip)`
 * (`adrp`/`add` on arm64). For a function in a shared library that can't
 * be linked into a position-independent executable: compiled, `&labs`
 * failed with "relocation R_X86_64_PC32 against symbol `labs' can not be
 * used when making a PIE object". The address now comes from the GOT. */

extern "c" i64 labs(i64 x);
extern "c" i32 strcmp(u8 *a, u8 *b);
extern "c" u64 strlen(u8 *s);

fn(i64 x) i64 gInit = &labs;   /* in data: a plain relocation */
fn(i64 x) i64 gRun;

i64 later(i64 x);                /* prototyped here, defined below */

i64 apply(fn(i64 x) i64 f, i64 v) { return f(v); }

i32 main() {
    printf("Test - address of an extern function: ");
    i64 tests = 0;
    i64 correct = 0;

    fn(i64 x) i64 f = &labs;
    gRun = &labs;
    tests++;
    if (f(-3) == 3 && gRun(-4) == 4 && apply(&labs, -5) == 5 && gInit(-6) == 6) {
        correct++;
    } else { printf("Failed - labs\n"); }

    tests++;
    if (f == gRun && f == &labs && gInit == f) { correct++; }
    else { printf("Failed - the same address\n"); }

    fn(u8 *a, u8 *b) i32 cmp = &libc.strcmp;
    fn(u8 *s) u64 len = &libc.strlen;
    i32 r = cmp("a", "b");
    tests++;
    if (r < 0 && cmp("b", "b") == 0 && len("hello") == 5) { correct++; }
    else { printf("Failed - strcmp/strlen\n"); }

    /* libktm functions, a ktm one and an asm-bound one */
    fn(u8 *s) u64 hlen = &str.len;
    fn(u64 size) void *alloc = &mem.alloc;
    u8 *p = alloc(16);
    tests++;
    if (hlen("four") == 4 && p != null) { correct++; }
    else { printf("Failed - libktm\n"); }
    mem.free(p);

    /* Defined in this file after a prototype */
    tests++;
    if (apply(&later, 20) == 21) { correct++; }
    else { printf("Failed - prototyped\n"); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}

i64 later(i64 x) { return x + 1; }
