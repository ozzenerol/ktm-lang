import cstr, fs, libc, mem, proc, test;

/* ktm syntax v0.2: `const` marks a DECLARATION read-only, it is not a
 * type qualifier like C's. An initializer is required (except on
 * parameters). A const of integer type whose initializer is an integer
 * constant expression is itself a compile-time constant: usable in
 * array sizes, case labels and other const initializers. Assigning to
 * a const (`=`, a compound assignment, `++`/`--`), including a const
 * parameter, is a compile error, and so is `const` anywhere but a
 * declaration (a cast, a struct field, a return type, after the type). */

const i64 MAX_USERS = 4;
const u8 *VERSION = "0.2.0";
pub const f64 PI = 3.14159;
const i64 A = 10;
const i64 B = A * 2;        /* chain: a const built from another const */

i64 gUsers[MAX_USERS];     /* array size from a const */

i64 takeConst(const i64 n) {
    return n + 1;             /* reading a const param works */
}

i64 classifyUser(i64 n) {
    switch (n) {
        case MAX_USERS: return 1;   /* case label from a const */
        default:        return 0;
    }
}

i64 getTwo() { return 2; }
i64 getSeven() { return 7; }

/* a const whose initializer is NOT a compile-time constant: read-only,
 * but not itself usable as one (same as an ordinary variable there) */
i64 divBySeven() {
    const i64 n = getSeven();
    return 14 / n;
}

i64 forConstSum() {
    i64 total = 0;
    for (const i64 i = 0; i < 3; ) {
        total += i;
        break;
    }
    return total;
}

#define DIAG_SRC "./ktm-414-const.ktm"
#define DIAG_OUT "./ktm-414-const.out"

u8 *diagnose(u8 *source) {
    proc.shell("rm -f " DIAG_SRC " " DIAG_OUT);
    fs.writeAll(DIAG_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " DIAG_SRC " -o /dev/null > " DIAG_OUT " 2>&1");
    u8 *out = fs.readAll(DIAG_OUT).take();
    proc.shell("rm -f " DIAG_SRC " " DIAG_OUT);
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

/* ktm exits non-zero, reports exactly `errors` "error:"s and the
 * text `want` appears `wants` times. */
bool fails(u8 *source, i64 errors, u8 *want, i64 wants) {
    u8 *out = diagnose(source);
    bool ok = out && count(out, "error:") == errors && count(out, want) == wants;
    if (!ok) { printf("\n%s\n----\n%s\n", source, out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - syntax: const: ");
    i64 correct = 0, tests = 0;

    /* values: plain, pointer, pub, chain */
    tests++; if (MAX_USERS == 4) { correct++; }
    tests++; if (!cstr.cmp(VERSION, "0.2.0")) { correct++; }
    tests++; if (PI > 3.14 && PI < 3.15) { correct++; }
    tests++; if (B == 20) { correct++; }

    /* array size from a const */
    tests++; if (sizeof(gUsers) == MAX_USERS * sizeof(i64)) { correct++; }

    /* case label from a const */
    tests++; if (classifyUser(4) == 1 && classifyUser(5) == 0) { correct++; }

    /* const param: reading it works */
    tests++; if (takeConst(9) == 10) { correct++; }

    /* local const: any initializer, read-only afterwards */
    {
        const i64 n = getTwo() * 2;
        tests++; if (n == 4) { correct++; }
    }

    /* a const whose initializer is NOT a compile-time constant still
     * runs correctly (it must not be folded to 0): */
    tests++; if (divBySeven() == 2) { correct++; }

    /* `const` in a for-loop's init-statement, like a normal local */
    tests++; if (forConstSum() == 0) { correct++; }

    /* rule 1: const needs an initializer */
    tests++;
    if (fails("const i64 X;\ni32 main() { return 0; }\n",
                        1, "const 'X' needs an initializer", 1)) {
        correct++;
                        }

    /* rule 1: a const param does not need one (no error at all) */
    tests++;
    if (fails("i64 f(const i64 n) { return n; }\ni32 main() { return f(1); }\n",
                        0, "error:", 0)) {
        correct++;
                        }

    /* rule 2: every assignment form to a const global */
    tests++;
    if (fails(
"const i64 X = 1;
void f() {
  X = 2; X += 1; X -= 1; X *= 2; X /= 2; X %= 2;
  X <<= 1; X >>= 1; X &= 1; X ^= 1; X |= 1;
  X++; ++X; X--; --X;
}
i32 main() { return 0; }
", 15, "cannot assign to const 'X'", 15)) {
        correct++;
}

    /* rule 2: assigning to a const parameter */
    tests++;
    if (fails("void f(const i64 n) { n = 1; }\ni32 main() { return 0; }\n",
                        1, "cannot assign to const 'n'", 1)) {
        correct++;
                        }

    /* rule 4: const on a cast */
    tests++;
    if (fails("i32 main() { i64 x = (const i64)1; return 0; }\n",
                        1, "const is only allowed on declarations", 1)) {
        correct++;
                        }

    /* rule 4: const on a struct field */
    tests++;
    if (fails("struct C { const i64 x; };\ni32 main() { return 0; }\n",
                        1, "const is only allowed on declarations", 1)) {
        correct++;
                        }

    /* rule 4: const on a function's return type */
    tests++;
    if (fails("const i64 f() { return 1; }\ni32 main() { return 0; }\n",
                        1, "const is only allowed on declarations", 1)) {
        correct++;
                        }

    /* rule 4: const after the type */
    tests++;
    if (fails("i64 const x = 1;\ni32 main() { return 0; }\n",
                        1, "const is only allowed on declarations", 1)) {
        correct++;
                        }

    /* a const with a runtime (non-constant) initializer is not usable as
     * a compile-time constant: same errors as a plain variable there */
    tests++;
    if (fails(
"i64 f() { return 7; }
i32 main() {
  const i64 n = f();
  switch (0) { case n: break; default: break; }
  return 0;
}
", 1, "error:", 1)) {
        correct++;
}

    tests++;
    if (fails(
"i64 f() { return 7; }
i32 main() {
  const i64 n = f();
  i64 a[n];
  return a[0];
}
", 2, "Invalid array subscript", 1)) {
        correct++;
}

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
