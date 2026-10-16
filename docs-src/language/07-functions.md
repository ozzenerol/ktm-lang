# Functions

Defining and calling functions, how arguments and results are passed, prototypes, default
arguments, parameters that know where they were called from, recursion, and how your names stay
apart from the libraries'. Variable arguments, overloading, function pointers and methods have
chapters of their own.

## Defining and calling

A function has a return type, a name, parameters in parentheses, and a body.

```ktm run
i64 square(i64 n) {
    return n * n;
}

f64 average(f64 a, f64 b) {
    return (a + b) / 2;
}

void greet(u8 *who) {
    printf("hello, %s\n", who);
}

void main() {
    printf("%d %.1f\n", square(12), average(3, 4));
    greet("ktm");
}
```

```output
144 3.5
hello, ktm
```

Each argument is converted to its parameter's type, as in an assignment: `average(3, 4)` passes two
`f64`s. A `return` value is converted to the return type the same way.

```ktm run
f64 half(i64 n) {
    return n;            // the i64 becomes an f64
}

void main() {
    printf("%.1f\n", half(3) / 2);
}
```

```output
1.5
```

A call with the wrong number of arguments is an error:

```ktm error
i64 add(i64 a, i64 b) { return a + b; }

void main() {
    add(1);
}
```

```output
error:
```

## Arguments are copies

Every argument is passed by value: the function gets a copy, and changing it changes nothing for the
caller. That holds for structs too.

```ktm run
struct Point { i64 x; i64 y; };

void tryToChange(i64 n, Point p) {
    n = 100;
    p.x = 100;
}

void main() {
    i64 n = 1;
    Point p = {1, 2};
    tryToChange(n, p);
    printf("%d %d\n", n, p.x);
}
```

```output
1 1
```

To let a function change a caller's variable, pass its address: see [Pointers](08-pointers.html).

```ktm run
struct Point { i64 x; i64 y; };

void moveRight(Point *p, i64 by) {
    p.x += by;
}

void main() {
    Point p = {1, 2};
    moveRight(&p, 10);
    printf("%d %d\n", p.x, p.y);
}
```

```output
11 2
```

`p.x` works through a pointer: ktm dereferences it for you. `p->x` means the same.

## Returning values

`return` ends the function and gives its result. A `void` function returns nothing, and can end
early with a bare `return;`.

```ktm run
void countdown(i64 from) {
    if (from < 0) {
        printf("nothing to count\n");
        return;
    }
    for (i64 i = from; i >= 0; i--) {
        printf("%d ", i);
    }
    printf("\n");
}

void main() {
    countdown(3);
    countdown(-1);
}
```

```output
3 2 1 0 
nothing to count
```

A struct is returned by value, as a copy:

```ktm run
struct Point { i64 x; i64 y; };

Point mid(Point a, Point b) {
    Point m = {(a.x + b.x) / 2, (a.y + b.y) / 2};
    return m;
}

void main() {
    Point a = {0, 0};
    Point b = {10, 4};
    Point m = mid(a, b);
    printf("%d %d\n", m.x, m.y);
}
```

```output
5 2
```

### Several results

Return a struct, or write the extra results through pointer parameters and return a success flag:

```ktm run
bool divide(i64 a, i64 b, i64 *quot, i64 *rem) {
    if (b == 0) { return false; }
    *quot = a / b;
    *rem = a % b;
    return true;
}

void main() {
    i64 q, r;
    if (divide(17, 5, &q, &r)) {
        printf("17 = 5 * %d + %d\n", q, r);
    }
    printf("ok: %d\n", divide(1, 0, &q, &r));
}
```

```output
17 = 5 * 3 + 2
ok: 0
```

For errors, ktm also has exceptions: see [Errors](15-errors.html).

## Order and prototypes

A function must be declared before it is called. Define it above its first call, or write a
prototype, a declaration without a body, and define the function later.

```ktm error
void main() {
    printf("%d\n", later(4));
}

i64 later(i64 n) {
    return n * 10;
}
```

```output
`later` has not been defined
```

With a prototype:

```ktm run
i64 later(i64 n);

void main() {
    printf("%d\n", later(4));
}

i64 later(i64 n) {
    return n * 10;
}
```

```output
40
```

Two functions that call each other need a prototype for one of them:

```ktm run
bool isEven(i64 n);

bool isOdd(i64 n) {
    if (n == 0) { return false; }
    return isEven(n - 1);
}

bool isEven(i64 n) {
    if (n == 0) { return true; }
    return isOdd(n - 1);
}

void main() {
    printf("%d %d\n", isEven(10), isOdd(7));
}
```

```output
1 1
```

- A prototype and its definition must agree on the return type and every parameter type.
- A prototype that is never defined is an error at link time. A function from a C library is
  declared with `extern "c"` instead: see [Calling C](26-calling-c.html).
- Inside a [module](22-modules-and-packages.html), functions see each other in any order, with no
  prototypes.

## Default arguments

Any parameter can have a default value, not only the last ones. Leave an argument out to get its
default; an empty place between commas skips one in the middle.

```ktm run
void box(i64 w = 2, i64 h = 3, u8 *fill = "#") {
    printf("%dx%d of %s\n", w, h, fill);
}

void main() {
    box(5, 1, "*");
    box(5);
    box(, 9);
    box(, , "@");
    box();
}
```

```output
5x1 of *
5x3 of #
2x9 of #
2x3 of @
2x3 of #
```

A default can be any expression. It is computed at each call that uses it:

```ktm run
i64 gNext = 100;

i64 nextId() {
    gNext++;
    return gNext;
}

void create(u8 *name, i64 id = nextId()) {
    printf("%s gets id %d\n", name, id);
}

void main() {
    create("a");
    create("b", 7);
    create("c");
}
```

```output
a gets id 101
b gets id 7
c gets id 102
```

Defaults written on a prototype carry over to the definition, which doesn't repeat them:

```ktm run
void connect(u8 *host, i64 port = 80);

void main() {
    connect("example.com");
    connect("example.com", 8080);
}

void connect(u8 *host, i64 port) {
    printf("%s:%d\n", host, port);
}
```

```output
example.com:80
example.com:8080
```

Repeating a default with the same value is fine; a different value is an error.

A default must fit its parameter as an argument would. A literal is text, not a `String`, so a
`String` parameter can't default to one. Take a `u8[]`, which a literal can be:

```ktm error
void openHistory(String path = ".history") {
}

i32 main() {
    openHistory();
    return 0;
}
```

```output
default value of parameter `path` is a `u8[9]`, not a `String`
```

```ktm run
void openHistory(u8[] path = ".history") {
    printf("history in %.*s\n", path.len, path.ptr);
}

i32 main() {
    openHistory();
    openHistory("/tmp/h");
    return 0;
}
```

```output
history in .history
history in /tmp/h
```

## Where a call comes from

A parameter marked `@caller("file")`, `@caller("line")` or `@caller("func")` is filled in at every
call with where that call is written. Callers never pass it. A logging or checking function can then
say where it was called from.

```ktm run
void trace(@caller("file") u8 *file, @caller("line") i64 line,
           @caller("func") u8 *func, u8 *what) {
    printf("%s:%d in %s: %s\n", file, line, func, what);
}

void load() {
    trace("loading");
}

void main() {
    trace("start");
    load();
}
```

```output
example.ktm:11 in main: start
example.ktm:7 in load: loading
```

- `"file"` is the source file's path as ktm was given it, `"func"` the function the call is in
  (`Type.method` for a method, `module.name` in a module). Both are `u8 *`; `"line"` is an integer.
- They can go anywhere in the parameter list.
- A call through a function pointer passes them like any other argument.

A check function that reports its caller's line:

```ktm run
void expect(bool ok, u8 *what, @caller("line") i64 line) {
    if (!ok) {
        printf("line %d: expected %s\n", line, what);
    }
}

void main() {
    i64 n = 3;
    expect(n > 0, "a positive n");
    expect(n % 2 == 0, "an even n");
}
```

```output
line 10: expected an even n
```

## Recursion

A function can call itself. Each call has its own parameters and locals.

```ktm run
i64 fib(i64 n) {
    if (n < 2) { return n; }
    return fib(n - 1) + fib(n - 2);
}

i64 gcd(i64 a, i64 b) {
    return b == 0 ? a : gcd(b, a % b);
}

void main() {
    for (i64 i = 0; i < 10; i++) {
        printf("%d ", fib(i));
    }
    printf("\n%d\n", gcd(84, 36));
}
```

```output
0 1 1 2 3 5 8 13 21 34 
12
```

Recursion that never ends overflows the stack. The program then stops with a crash report saying
so, and which function kept calling itself; see [Safety and debugging](29-safety-and-debugging.html).

## inline

The compiler inlines small functions by itself, with exactly the behaviour of a call. `inline` is
accepted, as a hint only: an `inline` function is still an ordinary function, which can recurse and
have its address taken.

```ktm run
inline i64 twice(i64 x) {
    return x * 2;
}

void main() {
    fn(i64 x) i64 f = &twice;
    printf("%d %d\n", twice(21), f(4));
}
```

```output
42 8
```

## Your names don't clash with the libraries'

The standard library lives in modules, used with a prefix (`date.now()`), and the C library's names
are separate from yours. So your functions can take any name.

```ktm run
import date;

i64 now() { return 42; }

void free(u8 *what) {
    printf("my free(%s)\n", what);
}

void main() {
    printf("%d %d\n", now(), date.now() > 0);
    free("thing");
}
```

```output
42 1
my free(thing)
```

The library keeps using C's `free` for its own memory; yours is only called where you call it.

## More on functions

- [Variable arguments](19-varargs.html): any number of arguments, as a slice.
- [Overloading](18-overloading.html): several functions with one name and different parameters.
- [Function pointers](20-function-pointers.html): `fn(i64) i64` types, callbacks and tables.
- [Methods](13-methods.html): functions that belong to a struct.
- [Generics](17-generics.html): functions with type parameters.
