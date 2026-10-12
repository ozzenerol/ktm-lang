import str, test;

/* Function overloading (v0.6.0): several functions of one name with
 * different parameter types, the call picks the one its arguments fit
 * best (exact > a conversion in the same family > int to float; no
 * defaulted parameter and no `...` extras win a tie). Methods overload
 * too, and a derived type's method hides every one of its base's.
 * `&f` takes the overload of the type it goes to. */

u8 *kind(i64 x) { return "i64"; }
u8 *kind(i32 x) { return "i32"; }
u8 *kind(f64 x) { return "f64"; }
u8 *kind(u8[] s) { return "u8[]"; }
u8 *kind(u8 *s) { return "u8 *"; }
u8 *kind(bool b) { return "bool"; }

enum Color { red, green };
enum Size { small, big };

struct Point { i64 x; i64 y; };

u8 *kind(Color c) { return "Color"; }
u8 *kind(Size s) { return "Size"; }
u8 *kind(Point p) { return "Point"; }
u8 *kind(Point *p) { return "Point *"; }

/* count and defaults */
i64 add(i64 a) { return a; }
i64 add(i64 a, i64 b) { return a + b; }
i64 add(i64 a, i64 b, i64 c = 100) { return a + b + c + 1000; }
f64 add(f64 a, f64 b) { return a + b; }

/* `T ...name` loses a tie to fixed parameters */
u8 *many(i64 a, i64 b) { return "two"; }
u8 *many(i64 ...rest) { return "rest"; }

/* only a float takes it: int to float */
f64 half(f64 x) { return x / 2.0; }
i64 half(u8[] s) { return s.len / 2; }

/* a call compiled while `early` was the only one */
i64 early(i64 x) { return x + 1; }
i64 callEarly() { return early(1); }
i64 early(u8[] s) { return s.len; }

/* prototypes first, bodies after main */
i64 later(i64 x);
i64 later(u8[] s);

i64 recurse(i64 n) { return n <= 0 ? 0 : n + recurse(n - 1); }
i64 recurse(u8[] s) { return recurse(s.len); }

struct Shape { i64 n; };
struct Square : Shape { i64 side; };
void Shape.set(i64 v) { self.n = v; }
void Shape.set(u8[] s) { self.n = s.len * 10; }
void Shape.set(i64 a, i64 b) { self.n = a * b; }
void Square.set(f64 v) { self.n = (i64)(v * 100.0); }

i64 apply(fn(i64) i64 f, i64 x) { return f(x); }
i64 apply(fn(u8[]) i64 f, u8[] s) { return f(s); }

i64 len(i64 x) { return x * 3; }
i64 len(u8[] s) { return s.len; }

fn(i64) i64 pickLen() { return &len; }

bool eq(u8 *a, u8 *b) {
    i64 i = 0;
    while (a[i] && a[i] == b[i]) { i++; }
    return a[i] == b[i];
}

i32 main() {
    printf("Test - overloading: ");
    i64 correct = 0, tests = 0;

    i32 small32 = 3;
    u8 *raw = "raw";
    String owned = str.from("owned");
    defer owned.free();
    Point p = {1, 2};

    tests++; if (eq(kind(5), "i64")) { correct++; }
    tests++; if (eq(kind(small32), "i32")) { correct++; }
    tests++; if (eq(kind((i32)5), "i32")) { correct++; }
    tests++; if (eq(kind(2.5), "f64")) { correct++; }
    tests++; if (eq(kind("lit"), "u8[]")) { correct++; }
    tests++; if (eq(kind(raw), "u8 *")) { correct++; }
    tests++; if (eq(kind(owned), "u8[]")) { correct++; }
    tests++; if (eq(kind(true), "bool")) { correct++; }
    tests++; if (eq(kind(Color.green), "Color")) { correct++; }
    tests++; if (eq(kind(Size.big), "Size")) { correct++; }
    tests++; if (eq(kind(p), "Point")) { correct++; }
    tests++; if (eq(kind(&p), "Point *")) { correct++; }

    tests++; if (add(4) == 4) { correct++; }
    tests++; if (add(4, 5) == 9) { correct++; }
    tests++; if (add(1, 2, 3) == 1006) { correct++; }
    tests++; if (add(1.5, 2.0) == 3.5) { correct++; }
    tests++; if (add(1, 2.5) == 3.5) { correct++; }

    tests++; if (eq(many(1, 2), "two")) { correct++; }
    tests++; if (eq(many(1, 2, 3), "rest")) { correct++; }
    tests++; if (eq(many(), "rest")) { correct++; }

    tests++; if (half(5) == 2.5) { correct++; }
    tests++; if (half("abcd") == 2) { correct++; }

    tests++; if (callEarly() == 2 && early("abc") == 3) { correct++; }
    tests++; if (later(4) == 40 && later("ab") == 2) { correct++; }
    tests++; if (recurse(3) == 6 && recurse("abcd") == 10) { correct++; }

    Shape s;
    s.set(7);
    tests++; if (s.n == 7) { correct++; }
    s.set("abc");
    tests++; if (s.n == 30) { correct++; }
    s.set(3, 4);
    tests++; if (s.n == 12) { correct++; }
    Shape.set(&s, 9);
    tests++; if (s.n == 9) { correct++; }
    Square q;
    q.set(5);
    tests++; if (q.n == 500) { correct++; }

    fn(i64) i64 f = &len;
    fn(u8[]) i64 g;
    g = &len;
    tests++; if (f(2) == 6 && g("abcde") == 5) { correct++; }
    tests++; if (apply(&len, 3) == 9 && apply(&len, "xy") == 2) { correct++; }
    tests++; if (pickLen()(5) == 15) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}

i64 later(i64 x) { return x * 10; }
i64 later(u8[] s) { return s.len; }
