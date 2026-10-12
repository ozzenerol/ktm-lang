import test;

/* Generic functions (v0.9.0): `T max<T>(T a, T b)`, called with the type
 * arguments written (`max<f64>(1, 2)`) or worked out from the arguments
 * (through pointers, slices, fn types and generic struct instances);
 * one generic calling another; a generic returning an instance. */

T max<T>(T a, T b) { return a > b ? a : b; }
T sum<T>(T[] s) { T t = 0; for (auto v : s) { t += v; } return t; }
bool any<T>(T[] s, fn(T) bool pred) { for (auto v : s) { if (pred(v)) { return true; } } return false; }
T deref<T>(T *p) { return *p; }
void swap<T>(T *a, T *b) { T t = *a; *a = *b; *b = t; }
T maxOf3<T>(T a, T b, T c) { return max(max(a, b), c); }

struct Box<T> { T v; };
Box<T> boxOf<T>(T v) { Box<T> b; b.v = v; return b; }
T unbox<T>(Box<T> *b) { return b.v; }

struct Pair<K, V> { K key; V value; };
Pair<K, V> pairOf<K, V>(K k, V v) { Pair<K, V> p; p.key = k; p.value = v; return p; }

T zero<T>() { return 0; }

bool big(i64 x) { return x > 10; }

i32 main() {
    printf("Test - generic functions: ");
    i64 correct = 0, tests = 0;

    tests++; if (max(3, 7) == 7 && max(1.5, 0.5) == 1.5) { correct++; }
    tests++; if (max<f64>(1, 2.5) == 2.5 && max<i64>(9, 2) == 9) { correct++; }
    i64 arr[4] = {1, 2, 30, 4};
    f64 fl[2] = {0.5, 0.25};
    tests++; if (sum(arr[0:4]) == 37 && sum(fl[0:2]) == 0.75) { correct++; }
    tests++; if (any(arr[0:4], &big) && !any(arr[0:2], &big)) { correct++; }
    i64 x = 5, y = 6;
    swap(&x, &y);
    tests++; if (x == 6 && y == 5 && deref(&x) == 6) { correct++; }
    tests++; if (maxOf3(4, 9, 2) == 9) { correct++; }
    Box<u8 *> b = boxOf("box");
    tests++; if (unbox(&b)[0] == 'b') { correct++; }
    Pair<u8 *, i64> p = pairOf("k", 42);
    tests++; if (p.value == 42 && p.key[0] == 'k') { correct++; }
    tests++; if (zero<i64>() == 0 && zero<f64>() == 0.0) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
