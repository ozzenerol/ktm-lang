import mem, test;

/* Generic structs (v0.9.0): `struct Vec<T>` and its methods
 * `T Vec<T>.get(...)`, an instance per argument list (`Vec<i64>`,
 * `Vec<Point>`, `Vec<u8 *>`, `Vec<Vec<i64>>`), each its own type with
 * its own layout; two parameters, a struct naming itself, a generic
 * base, type aliases of instances, `ad` with an instance's free(). */

struct Vec<T> { T *data; i64 len; i64 cap; };

void Vec<T>.push(T x) {
    if (self.len == self.cap) {
        self.cap = self.cap ? self.cap * 2 : 4;
        self.data = mem.realloc(self.data, sizeof(T) * self.cap);
    }
    self.data[self.len++] = x;
}
T Vec<T>.get(i64 i) { return self.data[i]; }
void Vec<T>.free() { mem.free(self.data); }

struct Pair<K, V> { K key; V value; };
V Pair<K, V>.second() { return self.value; }

struct Node<T> { T value; Node<T> *next; };

struct Base<T> { T v; };
struct Derived<T> : Base<T> { i64 extra; };
T Base<T>.get() { return self.v; }

struct Point { i64 x; i64 y; };
type Ints = Vec<i64>;

i64 gFreed = 0;
struct Res<T> { T id; };
void Res<T>.free() { gFreed += self.id; }

i32 main() {
    printf("Test - generic structs: ");
    i64 correct = 0, tests = 0;

    Vec<i64> a = {null, 0, 0};
    for (i64 i = 0; i < 100; i++) { a.push(i * 2); }
    tests++; if (a.len == 100 && a.get(99) == 198) { correct++; }

    Vec<f64> f = {null, 0, 0};
    f.push(1.5);
    f.push(2.25);
    tests++; if (f.get(1) == 2.25) { correct++; }

    Vec<Point> pts = {null, 0, 0};
    Point p = {3, 4};
    pts.push(p);
    tests++; if (pts.get(0).x == 3 && pts.get(0).y == 4) { correct++; }

    Vec<u8 *> words = {null, 0, 0};
    words.push("hi");
    tests++; if (words.get(0)[1] == 'i') { correct++; }

    Vec<Vec<i64>> nested = {null, 0, 0};
    nested.push(a);
    tests++; if (nested.get(0).get(10) == 20) { correct++; }

    tests++; if (sizeof(Vec<i64>) == 24 && sizeof(Pair<u8, i64>) == 16 &&
                 sizeof(Pair<i32, i32>) == 8) { correct++; }

    Pair<u8 *, f64> pr = {"pi", 3.25};
    tests++; if (pr.second() == 3.25 && pr.key[0] == 'p') { correct++; }

    Node<i64> n2 = {2, null};
    Node<i64> n1 = {1, &n2};
    tests++; if (n1.next.value == 2 && n1.next.next == null) { correct++; }

    Derived<i64> d;
    d.v = 7;
    d.extra = 1;
    tests++; if (d.get() == 7 && sizeof(Derived<i64>) == 16) { correct++; }

    Ints alias = {null, 0, 0};
    alias.push(5);
    tests++; if (alias.get(0) == 5) { correct++; }

    {
        ad Res<i64> r = {40};
        ad Res<i64> s = {2};
    }
    tests++; if (gFreed == 42) { correct++; }

    a.free(); f.free(); pts.free(); words.free(); nested.free(); alias.free();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
