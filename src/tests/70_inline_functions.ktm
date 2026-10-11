import test;

/* `inline` on an ordinary ktm function definition used to turn every
 * call into a statement block that the IR could not lower ("Expr
 * Unhandled AST kind: AST_COMPOUND_STMT"). `inline` is only a hint, so
 * these must behave exactly like normal functions. */

struct Pt {
    i64 x, y, z;
};

i64 gHits = 0;

inline i64 twice(i64 x) { return x * 2; }
inline i64 fact(i64 n) { if (n <= 1) { return 1; } return n * fact(n - 1); }
inline i64 quad(i64 x) { return twice(twice(x)); }
inline i64 addDef(i64 a, i64 b = 10) { return a + b; }
pub inline i64 Pub(i64 x) { return x + 1; }
inline void bump(i64 n) { gHits += n; }
inline void tick() { gHits++; }
inline Pt makePt(i64 v) { Pt p; p.x = v; p.y = v * 2; p.z = v * 3; return p; }
inline f64 half(f64 f) { return f / 2.0; }
inline i64 proto(i64 x);
inline i64 proto(i64 x) { return x - 1; }

i32 main() {
    i64 correct = 0, tests = 12;

    printf("Test - inline functions: ");

    if (twice(21) == 42) { correct++; }
    i64 v = twice(5) + twice(6);
    if (v == 22) { correct++; }
    if (fact(5) == 120) { correct++; }
    if (quad(3) == 12) { correct++; }

    fn(i64 a) i64 fp = &twice;
    if (fp(8) == 16) { correct++; }

    if (addDef(1) == 11 && addDef(1, 2) == 3) { correct++; }
    if (Pub(41) == 42) { correct++; }

    bump(5);
    tick();
    tick();
    if (gHits == 7) { correct++; }

    Pt p = makePt(4);
    if (p.x == 4 && p.y == 8 && p.z == 12) { correct++; }

    if (half(5.0) == 2.5) { correct++; }
    if (proto(10) == 9) { correct++; }
    if (twice(proto(fact(3))) == 10) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
