import cstr, fs, libc, mem, proc, test;

/* Inlining (v0.7.0): a call to a small function of the program is
 * compiled as the function's body in the caller. It must behave exactly
 * like the call: arguments converted to the parameter types (narrowed,
 * widened, to bool, int to float), early returns, loops and nested calls
 * in the body, the result converted to the return type, a call inside
 * && / || or a loop condition, recursion (never inlined), a throw from a
 * function the inlined body calls caught by the caller's try. The
 * generated code for a small function's caller has no call to it. */

#define IN_SRC "./ktm-553-inl.ktm"
#define IN_ASM "./ktm-553-inl.s"

i64 narrow8(i8 x) { return x; }
i64 narrowU16(u16 x) { return x; }
i64 wide(i64 x) { return x; }
bool asBool(bool b) { return b; }
f64 half(f64 x) { return x / 2.0; }
i32 retNarrow(i64 x) { return x; }
f64 retFloat(i64 x) { return x; }

i64 sign(i64 x) {
    if (x < 0) { return -1; }
    if (x == 0) { return 0; }
    return 1;
}

i64 sumTo(i64 n) {
    i64 t = 0;
    for (i64 i = 1; i <= n; i++) { t += i; }
    return t;
}

i64 inc(i64 x) { return x + 1; }

/* read-only params read in a loop, passed computed values */
i64 stepSum(i64 n, i64 step) {
    i64 t = 0;
    for (i64 i = 0; i < n; i++) { t += step * 3 + i; }
    return t;
}
i64 twoIncs(i64 x) { return inc(inc(x)); }

i64 fact(i64 n) { return n <= 1 ? 1 : n * fact(n - 1); }

i64 gCalls = 0;
bool truthy(i64 x) { gCalls++; return x != 0; }

void bump(i64 *p) { *p += 10; }

i64 firstByte(u8 *s) { return s[0]; }

void fail(i64 code) { throw(code, "failed %d", code); }
i64 checked(i64 x) {
    if (x < 0) { fail(7); }
    return x * 2;
}

i64 shadow(i64 x) {
    i64 t = x * 3;
    return t;
}

bool eq(u8 *a, u8 *b) { return cstr.cmp(a, b) == 0; }

/* the assembly of a program calling small(); true if it has no call to it */
bool inlinedAway() {
    fs.writeAll(IN_SRC, cstr.view("i64 small(i64 x) { return x * 7 + 1; }\n"
        "i32 main() { return (i32)small(3); }\n"), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("rm -f " IN_ASM "; $(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -S "
               IN_SRC " -o " IN_ASM);
    u8 *s = fs.readAll(IN_ASM).take();
    /* the function is still there (main's body is not): no call or bl to it */
    bool ok = s && libc.strstr(s, "small") &&
              !libc.strstr(s, "\tcall    _K05small") && !libc.strstr(s, "\tcallq   _K05small") &&
              !libc.strstr(s, "\tbl  _K05small") && !libc.strstr(s, "\tbl  __K05small");
    if (!ok) { printf("\n%s\n", s); }
    mem.free(s);
    proc.shell("rm -f " IN_SRC " " IN_ASM);
    return ok;
}

i32 main() {
    printf("Test - inlining: ");
    i64 correct = 0, tests = 0;

    tests++; if (narrow8(0x181) == -127) { correct++; }
    tests++; if (narrowU16(0x1FFFF) == 0xFFFF) { correct++; }
    i8 small = -5;
    tests++; if (wide(small) == -5) { correct++; }
    tests++; if (asBool(200) && !asBool(0)) { correct++; }
    tests++; if (half(5) == 2.5) { correct++; }
    tests++; if (retNarrow(0x1FFFFFFFF) == -1) { correct++; }
    tests++; if (retFloat(3) == 3.0) { correct++; }
    tests++; if (sign(-9) == -1 && sign(0) == 0 && sign(4) == 1) { correct++; }
    tests++; if (sumTo(10) == 55) { correct++; }
    tests++; if (twoIncs(5) == 7) { correct++; }
    i64 base = 4;
    tests++; if (stepSum(base + 1, base * 2) == 130) { correct++; }
    tests++; if (fact(10) == 3628800) { correct++; }

    gCalls = 0;
    tests++; if (!(truthy(0) && truthy(1)) && gCalls == 1) { correct++; }
    gCalls = 0;
    tests++; if ((truthy(1) || truthy(1)) && gCalls == 1) { correct++; }

    i64 loops = 0;
    for (i64 i = 0; inc(i) <= 5; i++) { loops++; }
    tests++; if (loops == 5) { correct++; }

    i64 v = 1;
    bump(&v);
    bump(&v);
    tests++; if (v == 21) { correct++; }

    tests++; if (firstByte("A") == 65) { correct++; }

    i64 caught = 0;
    try {
        checked(-1);
    } catch (Error e) {
        caught = e.code;
    }
    tests++; if (caught == 7 && checked(4) == 8) { correct++; }

    i64 t = 100;
    tests++; if (shadow(2) == 6 && t == 100) { correct++; }

    i64 acc = 0;
    for (i64 i = 0; i < 1000; i++) { acc += sign(i - 500) + inc(i); }
    tests++; if (acc == 500499) { correct++; }

    tests++; if (inlinedAway()) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
