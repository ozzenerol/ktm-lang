# Generics

Functions, structs and methods can take type parameters: one definition, written once, used
with many types. Each use is compiled for its types, producing the same code you would write
by hand, with no `void *` and no casts. This chapter covers generic functions, how the types
are worked out at a call, generic structs and their methods, methods with type parameters of
their own, generic bases, the library's generic containers, and the errors generics give.

## Generic functions

Type parameters go in `<...>` after the function's name; they can be used anywhere a type can
in the parameters, the return type and the body.

```ktm run
T max<T>(T a, T b) {
    return a > b ? a : b;
}

i32 main() {
    printf("%d\n", max(3, 7));
    printf("%.1f\n", max(1.5, 0.5));
    printf("%c\n", max('a', 'z'));
    return 0;
}
```

```output
7
1.5
z
```

Each type `max` is called with gets its own compiled copy, an instance: `max<i64>`, `max<f64>`
and `max<u8>` above. They are as fast as three hand-written functions.

A generic function can take pointers to its type parameter:

```ktm run
void swap<T>(T *a, T *b) {
    T t = *a;
    *a = *b;
    *b = t;
}

i32 main() {
    i64 x = 1, y = 2;
    swap(&x, &y);
    u8 *s = "left", *t = "right";
    swap(&s, &t);
    printf("%d %d %s %s\n", x, y, s, t);
    return 0;
}
```

```output
2 1 right left
```

And slices of it:

```ktm run
T sum<T>(T[] xs) {
    T total = 0;
    for (i64 i = 0; i < xs.len; i++) {
        total += xs[i];
    }
    return total;
}

i32 main() {
    i64 ints[3] = {1, 2, 3};
    f64 floats[2] = {0.5, 0.25};
    printf("%d %.2f\n", sum(ints), sum(floats));
    return 0;
}
```

```output
6 0.75
```

Several type parameters are separated by commas:

```ktm run
B convert<A, B>(A a, fn(A) B f) {
    return f(a);
}

f64 half(i64 x) {
    return (f64)x / 2.0;
}

i32 main() {
    printf("%.1f\n", convert(7, &half));
    return 0;
}
```

```output
3.5
```

## Working out the types

At a call, ktm works the type parameters out from the arguments: through plain values,
pointers, slices, `fn` types and generic structs. When the arguments disagree, or don't
mention a parameter at all, write the types after the name:

```ktm run
T max<T>(T a, T b) {
    return a > b ? a : b;
}

i32 main() {
    printf("%.1f\n", max<f64>(1, 2.5));
    printf("%d\n", max<i64>(-3, 2));
    return 0;
}
```

```output
2.5
2
```

With `max<f64>`, the `1` becomes `1.0`, as for any `f64` parameter. A type parameter that only
appears in the return type has to be written, since no argument says what it is:

```ktm run
T zero<T>() {
    T z;
    return z;
}

i32 main() {
    i64 n = zero<i64>();
    f64 f = zero<f64>();
    printf("%d %.1f\n", n, f);
    return 0;
}
```

```output
0 0.0
```

```ktm error
T zero<T>() {
    T z;
    return z;
}

i32 main() {
    i64 n = zero();
    return 0;
}
```

```output
can't work out the type arguments of `zero`: nothing says what `T` is
```

## Generic structs

A struct can take type parameters too. The type is always written with its arguments, nested
ones included: `Pair<u8 *, f64>`, `Stack<Stack<i64>>`.

```ktm run
struct Pair<K, V> {
    K key;
    V value;
};

i32 main() {
    Pair<u8 *, f64> pi = {"pi", 3.14};
    Pair<i64, bool> flag = {7, true};
    printf("%s=%.2f %d=%d\n", pi.key, pi.value, flag.key, flag.value);
    printf("%d %d\n", sizeof(Pair<u8 *, f64>), sizeof(Pair<u8, u8>));
    return 0;
}
```

```output
pi=3.14 7=1
16 2
```

Each set of arguments is its own struct type, laid out for its types: `Pair<u8, u8>` is 2
bytes.

A generic function can make and return one; `auto` keeps the full type:

```ktm run
struct Pair<K, V> {
    K key;
    V value;
};

Pair<K, V> pairOf<K, V>(K k, V v) {
    Pair<K, V> p = {k, v};
    return p;
}

i32 main() {
    auto e = pairOf("e", 2.72);
    printf("%s=%.2f\n", e.key, e.value);
    printf("%s\n", typeof(e));
    return 0;
}
```

```output
e=2.72
Pair<u8 *, f64>
```

`typeof(x)` is the type of `x` as text, which helps to see what an instance is.

A generic struct can hold another generic struct:

```ktm run
struct Box<T> {
    T value;
};

struct Shelf<T> {
    Box<T> left;
    Box<T> right;
};

i32 main() {
    Shelf<i64> s = {{1}, {2}};
    Box<Box<u8 *>> nested = {{"deep"}};
    printf("%d %d %s\n", s.left.value, s.right.value, nested.value.value);
    return 0;
}
```

```output
1 2 deep
```

## Methods of a generic struct

A method of a generic struct is written with the struct's type parameters after its name,
`Stack<T>.push`; inside, `T` is whatever the instance's `T` is.

```ktm run
import mem;

struct Stack<T> {
    T *items;
    i64 len;
    i64 cap;
};

void Stack<T>.push(T x) {
    if (self.len == self.cap) {
        self.cap = self.cap ? self.cap * 2 : 4;
        self.items = mem.realloc(self.items, sizeof(T) * self.cap);
    }
    self.items[self.len++] = x;
}

T Stack<T>.pop() {
    return self.items[--self.len];
}

bool Stack<T>.empty() {
    return self.len == 0;
}

void Stack<T>.free() {
    mem.free(self.items);
}

i32 main() {
    scoped Stack<i64> nums;
    for (i64 i = 1; i <= 5; i++) {
        nums.push(i * i);
    }
    while (!nums.empty()) {
        printf("%d ", nums.pop());
    }
    printf("\n");
    scoped Stack<u8 *> words;
    words.push("first");
    words.push("second");
    printf("%s\n", words.pop());
    return 0;
}
```

```output
25 16 9 4 1 
second
```

Only the methods an instance uses are compiled for it.

## Methods with type parameters of their own

A method can have type parameters of its own, after its name as for a function. It works on a
plain struct, and on a generic one next to the struct's parameters:

```ktm run
struct Counter { i64 n; };

i64 Counter.addAll<V>(V[] xs) {
    for (i64 i = 0; i < xs.len; i++) {
        self.n += (i64)xs[i];
    }
    return self.n;
}

i32 main() {
    Counter c = {0};
    u8 bytes[3] = {1, 2, 3};
    f64 floats[2] = {1.5, 2.5};
    printf("%d\n", c.addAll(bytes));
    printf("%d\n", c.addAll(floats));
    return 0;
}
```

```output
6
9
```

On a generic struct, a method can map one instance to another:

```ktm run
struct Pair<T> {
    T a;
    T b;
};

Pair<U> Pair<T>.map<U>(fn(T) U f) {
    Pair<U> p = {f(self.a), f(self.b)};
    return p;
}

f64 half(i64 x) {
    return (f64)x / 2.0;
}

u8 *size(f64 x) {
    return x >= 1.0 ? "big" : "small";
}

i32 main() {
    Pair<i64> p = {1, 5};
    Pair<u8 *> s = p.map(&half).map(&size);
    printf("%s %s\n", s.a, s.b);
    return 0;
}
```

```output
small big
```

- The method's own parameters are worked out from its arguments, as for a function; `self`
  takes no part, and the struct's parameters come from the value it is called on.
- Write them when nothing says what they are: `c.zero<i64>()` for a method whose type
  parameter is only in its return type.
- Its own parameter names can't repeat the struct's: `Pair<T>.f<T>` is an error.
- Its name is its own: not a field's, not a plain method's, not another generic method's.

## Generic functions over generic structs

A function can take a generic struct and work out its parameters from it:

```ktm run
struct Box<T> {
    T value;
};

T unbox<T>(Box<T> *b) {
    return b.value;
}

void fill<T>(Box<T> *b, T v) {
    b.value = v;
}

i32 main() {
    Box<f64> b = {2.5};
    Box<u8 *> s;
    fill(&s, "packed");
    printf("%.1f %s\n", unbox(&b), unbox(&s));
    return 0;
}
```

```output
2.5 packed
```

## A generic base

A generic struct can extend another, generic or not, and get its methods (see
[Methods](13-methods.html)):

```ktm run
struct List<T> {
    T items[4];
    i64 len;
};

struct Counted<T> : List<T> {
    i64 adds;
};

void List<T>.add(T x) {
    self.items[self.len++] = x;
}

void Counted<T>.addCounted(T x) {
    self.add(x);
    self.adds++;
}

i32 main() {
    Counted<i64> c;
    c.addCounted(10);
    c.addCounted(20);
    printf("%d %d %d %d\n", c.len, c.items[0], c.items[1], c.adds);
    return 0;
}
```

```output
2 10 20 2
```

## The library's generic containers

The containers are generic: `vec.Vec<T>`, `map.Map<K, V>`, `set.Set<T>`, `list.List<T>` (see
[the library](../std/index.html#vec)). Their constructors take the types:

```ktm run
import map, vec;

i32 main() {
    scoped vec.Vec<u8 *> names = vec.new<u8 *>();
    names.push("ann");
    names.push("bob");
    scoped map.Map<u8 *, i64> ages = map.new<u8 *, i64>();
    ages.set("ann", 41);
    for (i64 i = 0; i < names.len; i++) {
        u8 *n = names.get(i);
        printf("%s %d\n", n, ages.getOr(n, -1));
    }
    return 0;
}
```

```output
ann 41
bob -1
```

`json.decode<T>` is generic too: it decodes into whatever struct `T` is (see
[Attributes](23-attributes.html)).

## Errors are checked per instance

A generic body is checked for each set of types it is used with. A body that works for numbers
fails for a struct, at the operator that doesn't apply, with a note naming the instance and the
call that asked for it:

```ktm error
struct Point { i64 x, y; };

T max<T>(T a, T b) {
    return a > b ? a : b;
}

i32 main() {
    Point a = {1, 2};
    Point b = {3, 4};
    max(a, b);
    return 0;
}
```

```output
max<Point>
```

There are no constraints on type parameters: the instance either compiles or doesn't.

## In modules and libraries

A generic declared `pub` in a module is used as `module.name`, like any declaration:
`coll.Stack<i64>`, `coll.pairOf(1, 2)` (see [Modules and packages](22-modules-and-packages.html)).
A library built with `ktm -lib` keeps its generics as source, and each program compiles the
instances it uses.

## Not in ktm

- Constraints on type parameters.
- Generic enums, and numbers as parameters (`Array<T, 16>`).
- A different body for one type (specialisation).
- Several generic methods of one name, or a generic method next to a plain one of its name.
- The address of a generic method: wrap the call in a function.

## Rules

- `T name<T>(...)` is a generic function; `struct Name<T> { ... }` a generic struct;
  `R Name<T>.method(...)` a method of one; `R Name.method<U>(...)` a method with its own
  type parameters.
- Each set of type arguments is compiled into its own instance, when first used.
- A call works the types out from the arguments; write `f<T>(...)` when it can't.
- A generic struct type is always written with its arguments.
- The body is checked per instance; there are no constraints.
