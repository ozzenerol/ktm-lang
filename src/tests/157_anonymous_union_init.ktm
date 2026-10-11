import fs, libc, mem, proc, str, test;

/* A union takes one positional initialiser item, for its first member,
 * as in C. An anonymous union inside a class took one item per member:
 * `W w = {1,2,3}` set t=1, i=2, then f=3.0 over i and left z unset.
 * Union variables and union-typed fields could not take a brace list
 * at all. */

#define UNION_INIT_FILE "__157_anonymous_union_init.ktm"
#define UNION_INIT_OUT  "__157_anonymous_union_init.out"

struct W157 { u8 t; union { i32 i; f32 f; }; i64 z; };
struct D157 : W157 { i64 e; };
union U157 { i32 i; f32 f; };
struct X157 { u8 t; U157 u; i64 z; };
struct P157 { i64 x, y; };
union V157 { P157 p; i64 q; };
/* The union's first member is an anonymous class: its fields take the
 * items. A later anonymous class takes none. */
struct N157 { i64 a; union { struct { i32 lo, hi; }; i64 q; }; i64 z; };
struct M157 { i64 a; union { i64 q; struct { i32 lo, hi; }; }; i64 z; };
/* A union in a union. */
struct UU157 { i64 a; union { union { i16 s; i64 l; }; i32 m; }; i64 z; };
/* The second member is larger than the first: the rest is zero. */
struct B157 { i64 a; union { i32 s; i64 big; }; i64 z; };
struct Fl157 { union { f32 f; i32 i; }; i64 z; };

W157 g157W = {1,2,3};
D157 g157D = {1,2,3,4};
U157 g157U = {5};
X157 g157X = {1,{2},3};
V157 g157V = {{7,8}};
N157 g157N = {1,2,3,4};
M157 g157M = {1,2,3};
UU157 g157Uu = {1,-2,3};
B157 g157B = {1,-1,3};
Fl157 g157Fl = {2.5,9};
W157 g157Wa[2] = {{1,2,3},{4,5,6}};

bool testGlobals(void) {
    bool ok = true;
    if (g157W.t != 1 || g157W.i != 2 || g157W.z != 3) { ok = false; }
    if (g157D.t != 1 || g157D.i != 2 || g157D.z != 3 || g157D.e != 4) { ok = false; }
    if (g157U.i != 5) { ok = false; }
    if (g157X.t != 1 || g157X.u.i != 2 || g157X.z != 3) { ok = false; }
    if (g157V.p.x != 7 || g157V.p.y != 8) { ok = false; }
    if (g157N.a != 1 || g157N.lo != 2 || g157N.hi != 3 || g157N.z != 4) { ok = false; }
    if (g157M.a != 1 || g157M.q != 2 || g157M.z != 3) { ok = false; }
    if (g157Uu.a != 1 || g157Uu.l != 0xFFFE || g157Uu.z != 3) { ok = false; }
    if (g157B.a != 1 || g157B.big != 0xFFFFFFFF || g157B.z != 3) { ok = false; }
    if (g157Fl.f != 2.5 || g157Fl.z != 9) { ok = false; }
    if (g157Wa[0].i != 2 || g157Wa[0].z != 3) { ok = false; }
    if (g157Wa[1].t != 4 || g157Wa[1].i != 5 || g157Wa[1].z != 6) { ok = false; }
    if (sizeof(W157) != 16 || sizeof(B157) != 24) { ok = false; }
    return ok;
}

/* Leave non-zero bytes on the stack for the next call's locals. */
i64 dirty(void) {
    i64 junk[32];
    i64 k, sum = 0;
    for (k = 0; k < 32; k++) { junk[k] = -1; }
    for (k = 0; k < 32; k++) { sum += junk[k]; }
    return sum;
}

bool checkLocals(void) {
    W157 w = {1,2,3};
    D157 d = {1,2,3,4};
    U157 u = {5};
    X157 x = {1,{2},3};
    V157 v = {{7,8}};
    N157 n = {1,2,3,4};
    M157 m = {1,2,3};
    UU157 uu = {1,-2,3};
    B157 b = {1,-1,3};
    Fl157 fl = {2.5,9};
    W157 wa[2] = {{1,2,3},{4,5,6}};
    bool ok = true;
    if (w.t != 1 || w.i != 2 || w.z != 3) { ok = false; }
    if (d.t != 1 || d.i != 2 || d.z != 3 || d.e != 4) { ok = false; }
    if (u.i != 5) { ok = false; }
    if (x.t != 1 || x.u.i != 2 || x.z != 3) { ok = false; }
    if (v.p.x != 7 || v.p.y != 8) { ok = false; }
    if (n.a != 1 || n.lo != 2 || n.hi != 3 || n.z != 4) { ok = false; }
    if (m.a != 1 || m.q != 2 || m.z != 3) { ok = false; }
    if (uu.a != 1 || uu.l != 0xFFFE || uu.z != 3) { ok = false; }
    if (b.a != 1 || b.big != 0xFFFFFFFF || b.z != 3) { ok = false; }
    if (fl.f != 2.5 || fl.z != 9) { ok = false; }
    if (wa[0].i != 2 || wa[0].z != 3) { ok = false; }
    if (wa[1].t != 4 || wa[1].i != 5 || wa[1].z != 6) { ok = false; }
    return ok;
}

bool testLocals(void) {
    dirty();
    return checkLocals();
}

void cleanupUnionInitTest(void) {
    fs.remove(UNION_INIT_FILE);
    fs.remove(UNION_INIT_OUT);
    fs.remove("./a.out");
}

/* `source` must fail to compile with `msg` at `loc`. */
bool compileFailsWith(u8 *source, u8 *msg, u8 *loc) {
    u8 *out, *first;
    i64 res;
    bool ok;

    cleanupUnionInitTest();
    if (!fs.writeAll(UNION_INIT_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " UNION_INIT_FILE " > " UNION_INIT_OUT " 2>&1");
    out = fs.readAll(UNION_INIT_OUT);
    if (!out) {
        cleanupUnionInitTest();
        return false;
    }
    ok = res == 1;
    first = libc.strstr(out, "error: ");
    if (!first || str.cmpLen(first + 7, msg, str.len(msg))) { ok = false; }
    else if (!libc.strstr(first, loc)) { ok = false; }
    if (!ok) { printf("status %d\n%s\n", res, out); }
    mem.free(out);
    cleanupUnionInitTest();
    return ok;
}

bool testTooManyForClass(void) {
    return compileFailsWith(
"struct W { u8 t; union { i32 i; f32 f; }; i64 z; };
void main() { W w = {1,2,3,4}; }
", "More initialisers than class fields for class: W", "2:27");
}

bool testTooManyForUnion(void) {
    return compileFailsWith(
"union U { i32 i; f32 f; };
U u = {1,2};
void main() {}
", "More initialisers than class fields for class: U", "2:11");
}

i32 main() {
    printf("Test - Anonymous union initialiser: ");
    i64 tests = 4;
    i64 correct = 0;

    if (testGlobals()) { correct++; }
    else { printf("Failed - TestGlobals()\n"); }

    if (testLocals()) { correct++; }
    else { printf("Failed - TestLocals()\n"); }

    if (testTooManyForClass()) { correct++; }
    else { printf("Failed - TestTooManyForClass()\n"); }

    if (testTooManyForUnion()) { correct++; }
    else { printf("Failed - TestTooManyForUnion()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
