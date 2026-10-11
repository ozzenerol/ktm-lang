#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* A class field can hold a class by value only once that class is
 * complete. Outside modules, `class B; class A { B b; i64 x; };` with B
 * defined later compiled silently with B taken as 0 bytes, so
 * sizeof(A) was 8 and `x` shared its bytes with `b`; `class A { A a; }`
 * gave a size of 0. Now both are the error modules already gave, for
 * classes, unions and arrays of them. A pointer to an incomplete class,
 * or an array of such pointers, is fine. */

#define AT_SRC "./ktm-431-incomplete.ktm"
#define AT_OUT "./ktm-431-incomplete.out"

u8 *diagnose(u8 *source) {
    proc.shell("rm -f " AT_SRC " " AT_OUT);
    fs.writeAll(AT_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " AT_SRC " -o /dev/null > " AT_OUT " 2>&1");
    u8 *out = fs.readAll(AT_OUT);
    proc.shell("rm -f " AT_SRC " " AT_OUT);
    return out;
}

i64 count(u8 *s, u8 *what) {
    i64 n = 0;
    while (s && (s = libc.strstr(s, what))) {
        n++;
        s++;
    }
    return n;
}

/* ktm prints no warnings, `errors` errors and want (if any) for body */
bool prints(u8 *body, i64 errors, u8 *want=null) {
    u8 *src = str.format("%s\ni32 main() { return 0; }\n", body);
    u8 *out = diagnose(src);
    bool ok = out && count(out, "warning:") == 0 &&
                        count(out, "error:") == errors && (!want || libc.strstr(out, want));
    if (!ok) { printf("\n%s\n----\n%s\n", body, out); }
    mem.free(out);
    mem.free(src);
    return ok;
}

/* runtime: a pointer to a class completed later, and a field of a
 * class completed before, get the right layout */
struct Later;
struct Holder { Later *p; i64 x; };
struct Later { i64 y, z; };
struct Outer { Later l; i64 x; };

i32 main() {
    printf("Test - a field can't hold an incomplete class by value: ");
    i64 correct = 0, tests = 0;

    /* pointers, arrays of pointers, complete classes, empty classes and
     * function pointer types naming an incomplete class: no diagnostic */
    tests++;
    if (prints(
"struct B;
union U;
struct A { B *b; B **pp; B *arr[3]; U *u; fn(B *) i64 f; i64 x; };
struct B { i64 y, z; };
struct C { B b; B arr[2]; i64 x; };
struct E {};
struct F { E e; i64 x; };
union V { B b; i64 x; };", 0)) { correct++; }

    /* the case from the report */
    tests++;
    if (prints("struct B;\nstruct A { B b; i64 x; };\nstruct B { i64 y, z; };", 1,
                         "field `b` of A holds a B, which is not complete here: use a pointer")) {
        correct++;
                         }

    /* never completed, an array of it, a union, inside a union */
    tests++;
    if (prints("struct B;\nstruct A { i64 x; B b; };", 1,
                         "field `b` of A holds a B, which is not complete here")) {
        correct++;
                         }
    tests++;
    if (prints("struct B;\nstruct A { B arr[2]; };\nstruct B { i64 y; };", 1,
                         "field `arr` of A holds a B, which is not complete here")) {
        correct++;
                         }
    tests++;
    if (prints("union U;\nstruct A { U u; };\nunion U { i64 y; f64 z; };", 1,
                         "field `u` of A holds a U, which is not complete here")) {
        correct++;
                         }
    tests++;
    if (prints("struct B;\nunion W { B b; i64 x; };\nstruct B { i64 y; };", 1,
                         "field `b` of W holds a B, which is not complete here")) {
        correct++;
                         }

    /* a class holding itself by value */
    tests++;
    if (prints("struct A { i64 x; A a; };", 1,
                         "field `a` of A holds a A, which is not complete here")) {
        correct++;
                         }

    /* the layouts that are still allowed */
    Later l;
    Holder h;
    h.p = &l;
    h.x = 7;
    l.y = 1;
    l.z = 2;
    tests++; if (sizeof(Holder) == 16 && h.p.z == 2 && h.x == 7) { correct++; }
    Outer o;
    tests++; if (sizeof(Outer) == 24 && (u8 *)&o.x - (u8 *)&o == 16) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
