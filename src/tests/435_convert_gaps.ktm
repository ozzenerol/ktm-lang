import cstr, test;

/* Forms the converter emits that did not compile:
 * - a default on an fn-typed parameter, `fn(i64) i64 f = &D`: calling f
 *   in the body was "Function: f() not defined" (the old declarator
 *   form worked), and `(*f)(x)` could not be called;
 * - an enum member right after `(`, `(C.blue ^ 1)`, was taken for a
 *   cast to the enum;
 * - #if could not evaluate a prefix cast to a lowercase type,
 *   `#if (u64)7 % 4 == 3`: `u64` was taken for an undefined macro (0);
 * - a prefix cast to an fn type in parentheses, `((fn(i64) i64)raw)(6)`,
 *   was taken for `(fn(i64) i64) *`, a pointer to a function pointer,
 *   and `Call((fn(i64) i64)raw)` for a postfix cast. */

#define W ((u64)7 % 4)
#if (u64)7 % 4 == 3 && (u8)511 == 255 && (i8)255 == -1 && W == 3
const i64 CAST_IF = 1;
#else
const i64 CAST_IF = 0;
#endif

i64 D(i64 y) { return y * 2; }
i64 T(i64 y) { return y * 3; }
u8 *skip(u8 *s) { return s + 1; }
i64 count(...args) { return args.len; }

/* defaults on fn-typed parameters */
i64 A(i64 x, fn(i64) i64 f = &D) { return f(x); }
i64 B(i64 x, fn(i64 y) i64 f = &D) { return f(x); }
i64 C(fn(i64) i64 f = &D, i64 x = 5) { fn(i64) i64 g = f; return f(x) + g(1) + (*f)(1); }

/* enum members after `(` */
enum Color { red, blue };
enum Kind { x, y };
i64 idFn(i64 v) { return v; }

/* casts to fn types */
i64 call(fn(i64) i64 f, i64 v) { return f(v); }
fn(i64) i64 back(void *p) { return (fn(i64) i64)p; }
struct Cb { fn(i64) i64 cb; };
fn(i64) i64 gOp = &D;

i32 main() {
    printf("Test - converter gaps: fn defaults, enum after (, #if casts, fn casts: ");
    i64 correct = 0, tests = 0;

    tests++; if (A(3) == 6 && A(3, &T) == 9 && B(4) == 8) { correct++; }
    tests++; if (C() == 14 && C(&T, 1) == 9) { correct++; }

    i64 e1 = (Color.blue ^ 1);
    i64 e2 = (Kind.y + 1) * 2;
    i64 e3 = idFn((Kind.y));
    i64 seen = 0;
    if ((Color.blue == 1)) { seen++; }
    tests++; if (e1 == 0 && e2 == 4 && e3 == 1 && seen == 1) { correct++; }

    i64 arr[W];
    tests++; if (CAST_IF == 1 && sizeof(arr) == 24) { correct++; }

    void *raw = &D;
    void *sp = &skip;
    void *cp = &count;
    void *pp = &gOp;
    Cb c;
    c.cb = &D;
    tests++;
    if (((fn(i64) i64)raw)(6) == 12 && !cstr.cmp(((fn(u8 *) u8 *)sp)("xy"), "y") &&
            ((fn(...args) i64)cp)(1, 2, 3) == 3) {
        correct++;
            }
    tests++;
    if ((*((fn(i64) i64) *)pp)(7) == 14 && call((fn(i64) i64)raw, 8) == 16 &&
            back(raw)(9) == 18 && c.cb(((fn(i64) i64)raw)(2)) == 8) {
        correct++;
            }
    (fn(i64) i64) *q = ((fn(i64) i64) *)pp;
    tests++; if ((*q)(1) == 2 && ((fn(i64) i64)raw == raw)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
