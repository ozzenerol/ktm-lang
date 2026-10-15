# Overloading

Several functions can share one name when their parameter types differ. A call picks the one
its arguments fit best, at compile time. This chapter covers declaring overloads, how a call
chooses, integer literals and casts, enums, defaults and ties, taking the address of an
overload, overloaded methods, and the errors.

## Declaring overloads

Write each version as an ordinary function with the same name and different parameters, in
type or in number:

```ktm run
void show(i64 n) {
    printf("number %d\n", n);
}

void show(f64 x) {
    printf("float %.2f\n", x);
}

void show(u8 *s) {
    printf("text %s\n", s);
}

void show(i64 a, i64 b) {
    printf("pair %d, %d\n", a, b);
}

i32 main() {
    show(42);
    show(2.5);
    show("hello");
    show(1, 2);
    return 0;
}
```

```output
number 42
float 2.50
text hello
pair 1, 2
```

Overloads usually do the same job for different inputs:

```ktm run
i64 area(i64 side) {
    return side * side;
}

i64 area(i64 width, i64 height) {
    return width * height;
}

f64 area(f64 radius) {
    return 3.14159 * radius * radius;
}

i32 main() {
    printf("%d %d %.2f\n", area(4), area(3, 5), area(1.0));
    return 0;
}
```

```output
16 15 3.14
```

## How a call picks

Every overload the arguments can be converted to is a candidate. Each argument fits its
parameter in one of three ways, from best to worst:

1. exactly: the same type, or an array or string literal for a slice of its element type;
2. in the same family: another integer width or signedness, a `String` for a `u8[]`, any
   pointer for a `void *`;
3. as an integer for a float.

The overload that fits every argument at least as well as any other, and at least one better,
wins. A conversion that would only produce a warning (a pointer given for an integer) doesn't
make a candidate.

```ktm run
void take(i64 n) {
    printf("i64\n");
}

void take(f64 x) {
    printf("f64\n");
}

i32 main() {
    i32 small = 3;
    f32 single = 1.5;
    take(small);
    take(single);
    return 0;
}
```

```output
i64
f64
```

`small` is an `i32`: it fits `i64` in the same family, but `f64` only as an integer for a float,
so `take(i64)` wins. `single` fits `f64` in its family.

A slice parameter takes arrays and string literals exactly, and a `String` in its family:

```ktm run
import str;

void describe(u8[] s) {
    printf("%d bytes\n", s.len);
}

void describe(i64 n) {
    printf("the number %d\n", n);
}

i32 main() {
    u8 word[5] = {'h', 'e', 'l', 'l', 'o'};
    scoped String owned = str.from("owned text");
    describe("literal");
    describe(word);
    describe(owned);
    describe(9);
    return 0;
}
```

```output
7 bytes
5 bytes
10 bytes
the number 9
```

## Integer literals are i64

An integer literal is an `i64`, so it fits an `i64` parameter exactly and picks it over a
narrower one. Cast to choose the other:

```ktm run
void f(i32 x) {
    printf("i32\n");
}

void f(i64 x) {
    printf("i64\n");
}

i32 main() {
    f(5);
    f((i32)5);
    i32 small = 5;
    f(small);
    return 0;
}
```

```output
i64
i32
i32
```

With only narrower overloads, a literal fits each of them equally well, and the call is
ambiguous until an argument is cast:

```ktm error
void f(i32 x) {
}

void f(i16 x) {
}

i32 main() {
    f(5);
    return 0;
}
```

```output
the call `f(i64)` is ambiguous: `f(i32)`, `f(i16)` fit as well; cast an argument to pick one
```

## Enums are their own type

An enum member fits a parameter of its enum exactly, so an enum overload and an integer one
can live together:

```ktm run
enum Color { red, green };

void paint(Color c) {
    printf("color %d\n", c);
}

void paint(i64 n) {
    printf("number %d\n", n);
}

i32 main() {
    paint(Color.green);
    paint(7);
    return 0;
}
```

```output
color 1
number 7
```

## Defaults and ties

When two overloads fit equally well, the one that needs no default arguments and no `...`
extras wins:

```ktm run
void send(u8 *msg) {
    printf("plain: %s\n", msg);
}

void send(u8 *msg, i64 retry = 3) {
    printf("retry %d: %s\n", retry, msg);
}

i32 main() {
    send("a");
    send("b", 5);
    return 0;
}
```

```output
plain: a
retry 5: b
```

## When nothing fits

A call that no overload takes is an error that lists them:

```ktm error
void h(i64 x) {
}

void h(u8[] s) {
}

i32 main() {
    h(1.5, 2);
    return 0;
}
```

```output
no overload of `h` takes (f64, i64); there are `h(i64)`, `h(u8[])`
```

Two functions with the same parameters but different return types aren't overloads, they are
a conflict:

```ktm error
i64 g(i64 x) {
    return x;
}

f64 g(i64 x) {
    return 1.0;
}

i32 main() {
    return 0;
}
```

```output
conflicting types for `g`
```

## Overloaded methods

Methods overload the same way, and so do a module's functions:

```ktm run
import str;

struct Counter { i64 n; };

void Counter.add(i64 by) {
    self.n += by;
}

void Counter.add(u8[] word) {
    self.n += word.len;
}

i32 main() {
    Counter c = {0};
    c.add(3);
    c.add("four");
    scoped String s = str.from("owned");
    c.add(s);
    printf("%d\n", c.n);
    return 0;
}
```

```output
12
```

A derived struct's method hides every overload of that name in its base (see
[Methods](13-methods.html)).

## Taking the address of an overload

`&name` picks the overload whose type the destination asks for: a typed variable, a parameter,
a field (see [Function pointers](20-function-pointers.html)):

```ktm run
i64 size(i64 n) {
    return n;
}

i64 size(u8[] s) {
    return s.len;
}

i64 apply(fn(u8[]) i64 f, u8[] s) {
    return f(s);
}

i32 main() {
    fn(i64) i64 byNumber = &size;
    printf("%d %d\n", byNumber(7), apply(&size, "four"));
    return 0;
}
```

```output
7 4
```

With nothing to pick by, it is an error:

```ktm error
i64 size(i64 n) {
    return n;
}

i64 size(u8[] s) {
    return s.len;
}

i32 main() {
    auto p = &size;
    return 0;
}
```

```output
`&size` needs the function type to pick one
```

## Overloads and generics

A generic function and plain overloads can share a name; a plain overload that fits exactly
is preferred to an instance:

```ktm run
void print<T>(T x) {
    printf("something of %d bytes\n", sizeof(T));
}

void print(u8 *s) {
    printf("text: %s\n", s);
}

i32 main() {
    print("hi");
    print(3.5);
    print(7);
    return 0;
}
```

```output
text: hi
something of 8 bytes
something of 8 bytes
```

## Rules

- Functions and methods of one name are overloads when their parameter types differ; return
  types alone don't make an overload.
- A call takes the overload every argument fits at least as well as with any other: exactly,
  then the same family, then integer for float. Warning-only conversions don't count.
- On a tie, the overload without defaults or `...` extras wins; otherwise the call is
  ambiguous and must cast.
- Integer literals are `i64`; enum members fit their enum exactly.
- `&name` of an overloaded function needs a destination type.
- An `extern "c"` function has one C symbol and can't be overloaded; `...args` can't be spread
  into an overloaded function.
- Overloads with `@caller` parameters must all start with the same ones.
- Messages show an overload with its parameter types: `show(u8[])`.
