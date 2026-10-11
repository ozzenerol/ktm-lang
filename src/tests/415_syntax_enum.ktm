import test;

import fs, libc, mem, proc, str;

/* ktm syntax v0.2: scoped enums. `enum Name [: IntType] { member [=
 * constexpr], ... };` at file scope. Members are integer constants, only
 * reachable as `Name.member`; the underlying type defaults to i32 and
 * sizeof/alignof follow it; an enum value converts to any integer type
 * implicitly, but an integer (or another enum) needs a cast to become one. */

enum Color { red, green, blue };
enum Status : u16 { ok = 200, notFound = 404, gone };
pub enum Mode { read = 1, write = 2, both = read | write };

struct Pixel { Color c; };

Color nextColor(Color c) { return (Color)((c + 1) % 3); }

Color gColors[2] = {Color.green, Color.blue};

#define AT_SRC "./ktm-415-enum.ktm"
#define AT_OUT "./ktm-415-enum.out"

/* Compile `source`, which must FAIL to compile, and return ktm's output. */
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

/* `body`, wrapped as a whole program, must fail to compile with `want`
 * somewhere in ktm's output. */
bool fails(u8 *body, u8 *want) {
    u8 *src = str.format("%s\ni32 main() { return 0; }\n", body);
    u8 *out = diagnose(src);
    bool ok = out && libc.strstr(out, "error:") && libc.strstr(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", body, out); }
    mem.free(out);
    mem.free(src);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - scoped enums: ");

    /* default, explicit and mixed values; `: u16`; bitwise-or of earlier
     * members used unqualified inside the braces */
    tests++;
    if (Color.red == 0 && Color.green == 1 && Color.blue == 2 &&
            Status.ok == 200 && Status.notFound == 404 && Status.gone == 405 &&
            Mode.read == 1 && Mode.write == 2 && Mode.both == 3) {
        correct++;
            } else { printf("\n  enum member values\n"); }

    /* sizeof/alignof follow the underlying type; typeof names the enum */
    tests++;
    Color c = Color.green;
    if (sizeof(Color) == 4 && sizeof(Status) == 2 &&
            alignof(Status) == 2 && !str.cmp(typeof(c), "Color")) {
        correct++;
            } else { printf("\n  sizeof/alignof/typeof\n"); }

    /* switch on an enum, with enum case labels */
    tests++;
    i64 hit = -1;
    switch (c) {
        case Color.red:   hit = 0; break;
        case Color.green: hit = 1; break;
        case Color.blue:  hit = 2; break;
    }
    if (hit == 1) { correct++; }
    else { printf("\n  switch on an enum\n"); }

    /* an enum member is a compile-time constant: array size */
    tests++;
    i64 sizes[Color.blue + 1];
    i64 nSizes = 0;
    for (i64 i = 0; i < Color.blue + 1; i++) { sizes[i] = i; nSizes++; }
    if (nSizes == 3 && sizes[Color.blue] == 2) { correct++; }
    else { printf("\n  enum member as an array size\n"); }

    /* a struct field of enum type */
    tests++;
    Pixel p;
    p.c = Color.red;
    if (p.c == Color.red) { correct++; }
    else { printf("\n  struct field of enum type\n"); }

    /* a parameter and a return value of enum type */
    tests++;
    if (nextColor(Color.red) == Color.green &&
            nextColor(Color.blue) == Color.red) {
        correct++;
            } else { printf("\n  enum parameter and return value\n"); }

    /* an integer needs a cast to become an enum */
    tests++;
    Color d = (Color)2;
    if (d == Color.blue) { correct++; }
    else { printf("\n  cast from an integer to an enum\n"); }

    /* an enum converts to any integer type implicitly; arithmetic and
     * comparisons with integers work */
    tests++;
    i32 asInt = c;
    i64 plusOne = c + 1;
    if (asInt == 1 && plusOne == 2 && c < Color.blue && c != Color.red) {
        correct++;
    } else { printf("\n  enum -> integer, implicit\n"); }

    /* initialiser lists, local and global, same as an i32 array would */
    tests++;
    Color arr[3] = {Color.red, Color.green, Color.blue};
    if (arr[0] == Color.red && arr[1] == Color.green && arr[2] == Color.blue &&
            gColors[0] == Color.green && gColors[1] == Color.blue) {
        correct++;
            } else { printf("\n  initialiser list of enum elements\n"); }

    /* a ternary with both branches the same enum keeps that enum's type;
     * mixed enum/integer branches give an integer, like other arithmetic */
    tests++;
    Color t1 = nSizes > 0 ? Color.blue : Color.red;
    i64 t2 = nSizes > 0 ? Color.blue : 7;
    if (t1 == Color.blue && !str.cmp(typeof(t1), "Color") && t2 == 2) {
        correct++;
    } else { printf("\n  ternary with enum branches\n"); }

    /* plain assignment and a cast-reassignment stay fine; ++, -- and a
     * compound assignment on an enum are errors (checked below) */
    tests++;
    c = Color.green;
    c = (Color)(c + 1);
    if (c == Color.blue) { correct++; }
    else { printf("\n  plain enum assignment and cast-reassignment\n"); }

    /* compile errors */

    tests++;
    if (fails("enum Empty { };", "has no members")) { correct++; }
    else { printf("\n  empty enum\n"); }

    tests++;
    if (fails("enum Dup { a, b, a };", "already has a member")) { correct++; }
    else { printf("\n  duplicate enum member\n"); }

    tests++;
    if (fails("enum Small : u8 { big = 300 };", "does not fit")) { correct++; }
    else { printf("\n  enum member value too large for its type\n"); }

    tests++;
    if (fails("enum C415a { x, y };\ni32 g() { return C415a.z; }",
                        "no member")) {
        correct++;
                        } else { printf("\n  unknown enum member\n"); }

    tests++;
    if (fails("enum C415b { x, y };\ni32 g() { C415b v = 1; return v; }",
                        "Cannot convert `i64` to enum `C415b` without a cast")) {
        correct++;
                        } else { printf("\n  integer to enum needs a cast\n"); }

    tests++;
    if (fails("enum C415c { x, y };\ni32 g(C415c v) { return v; }\n"
                        "i32 h() { return g(1); }",
                        "Cannot convert `i64` to enum `C415c` without a cast")) {
        correct++;
                        } else { printf("\n  integer argument to an enum parameter needs a cast\n"); }

    tests++;
    if (fails("enum C415d { x, y };\nenum C415e { p, q };\n"
                        "i32 g() { C415d v = C415e.p; return v; }",
                        "Cannot convert `C415e` to enum `C415d` without a cast")) {
        correct++;
                        } else { printf("\n  one enum assigned to another needs a cast\n"); }

    /* returning an integer from a function returning an enum: an error,
     * same as assignment */
    tests++;
    if (fails("enum C415f { x, y };\nC415f g() { return 2; }",
                        "Cannot convert `i64` to enum `C415f` without a cast")) {
        correct++;
                        } else { printf("\n  integer return value needs a cast\n"); }

    /* two different enums comparing against each other */
    tests++;
    if (fails("enum C415g { x, y };\nenum C415h { p, q };\n"
                        "i32 g() { C415g a = C415g.x; C415h b = C415h.p; "
                        "if (a == b) return 1; return 0; }",
                        "Cannot compare enum `C415g` with enum `C415h`")) {
        correct++;
                        } else { printf("\n  comparing two different enums\n"); }

    /* a real class mismatch still gets the old, generic message, not an
     * enum-flavoured one */
    tests++;
    if (fails("struct C415i { i64 x; };\nstruct C415j { i64 x; };\n"
                        "i32 take(C415i a) { return a.x; }\n"
                        "i32 g() { C415j b; return take(b); }",
                        "Incompatible function argument, expected 'C415i' got 'C415j'")) {
        correct++;
                        } else { printf("\n  a real class mismatch keeps its own message\n"); }

    /* enum E {}; struct E {}; (and the reverse) both redefine the name */
    tests++;
    if (fails("enum C415k { x };\nstruct C415k { i64 v; };",
                        "`C415k` has already been defined")) {
        correct++;
                        } else { printf("\n  struct redefining an enum name\n"); }

    tests++;
    if (fails("struct C415l { i64 v; };\nenum C415l { x };",
                        "`C415l` has already been defined")) {
        correct++;
                        } else { printf("\n  enum redefining a struct name\n"); }

    /* ++, --, and a compound assignment on an enum are errors, like
     * C++'s `enum class` */
    tests++;
    if (fails("enum C415m { x, y };\n"
                        "i32 g() { C415m v = C415m.x; v++; return 0; }",
                        "Cannot do arithmetic assignment on enum `C415m`")) {
        correct++;
                        } else { printf("\n  ++ on an enum\n"); }

    tests++;
    if (fails("enum C415n { x, y };\n"
                        "i32 g() { C415n v = C415n.x; --v; return 0; }",
                        "Cannot do arithmetic assignment on enum `C415n`")) {
        correct++;
                        } else { printf("\n  -- on an enum\n"); }

    tests++;
    if (fails("enum C415o { x, y };\n"
                        "i32 g() { C415o v = C415o.x; v += 1; return 0; }",
                        "Cannot do arithmetic assignment on enum `C415o`")) {
        correct++;
                        } else { printf("\n  += on an enum\n"); }

    /* assigning to a member itself: `Name.member = x` */
    tests++;
    if (fails("enum C415p { x, y };\ni32 g() { C415p.x = 1; return 0; }",
                        "Cannot assign to enum member `C415p.x`")) {
        correct++;
                        } else { printf("\n  assigning to an enum member\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
