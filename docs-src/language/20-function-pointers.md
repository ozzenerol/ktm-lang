# Function pointers

A function pointer holds the address of a function, so the function to call can be chosen at
run time, stored in a table or a struct, and passed to other functions. Its type is written
`fn(parameter types) return type`. This chapter covers the type, taking addresses and calling
through pointers, passing and returning functions, tables of them, pointers in structs,
pointers to function pointers, callbacks with context, and which conversions work.

## The type and calling through it

`fn(i64, i64) i64` is the type of a pointer to a function that takes two `i64`s and returns an
`i64`. `&name` is a function's address; calling the pointer calls the function:

```ktm run
i64 add(i64 a, i64 b) {
    return a + b;
}

i64 mul(i64 a, i64 b) {
    return a * b;
}

i32 main() {
    fn(i64, i64) i64 op = &add;
    printf("%d\n", op(2, 3));
    op = &mul;
    printf("%d\n", op(2, 3));
    return 0;
}
```

```output
5
6
```

- Parameter names are optional and ignored: `fn(i64 a, i64 b) i64` is `fn(i64, i64) i64`.
- `(*f)(x)` calls it too, as in C.
- The arguments are checked against the pointer's parameter types, like a direct call.

```ktm error
i64 twice(i64 x) {
    return x * 2;
}

i32 main() {
    fn(i64) i64 f = &twice;
    f(1, 2);
    return 0;
}
```

```output
Unexpected number of arguments 2 in call to f(), expected 1 args
```

A function returning nothing is `fn(...) void`, and one taking nothing is `fn() T`:

```ktm run
void hello() {
    printf("hello\n");
}

i64 answer() {
    return 42;
}

i32 main() {
    fn() void h = &hello;
    fn() i64 a = &answer;
    h();
    printf("%d\n", a());
    return 0;
}
```

```output
hello
42
```

A function pointer that holds nothing is `null`; calling it crashes, so check first when it may
be unset:

```ktm run
void run(fn() void hook) {
    if (hook != null) {
        hook();
    } else {
        printf("no hook\n");
    }
}

void hi() {
    printf("hi\n");
}

i32 main() {
    run(&hi);
    run(null);
    return 0;
}
```

```output
hi
no hook
```

## Passing a function

A function that takes a function pointer runs code its caller chooses:

```ktm run
i64 add(i64 a, i64 b) {
    return a + b;
}

i64 mul(i64 a, i64 b) {
    return a * b;
}

i64 fold(fn(i64, i64) i64 op, i64[] nums) {
    i64 acc = nums[0];
    for (i64 i = 1; i < nums.len; i++) {
        acc = op(acc, nums[i]);
    }
    return acc;
}

i32 main() {
    i64 nums[4] = {1, 2, 3, 4};
    printf("%d %d\n", fold(&add, nums), fold(&mul, nums));
    return 0;
}
```

```output
10 24
```

A predicate selects:

```ktm run
bool isEven(i64 n) {
    return n % 2 == 0;
}

bool isBig(i64 n) {
    return n > 10;
}

i64 count(i64[] xs, fn(i64) bool keep) {
    i64 n = 0;
    for (i64 i = 0; i < xs.len; i++) {
        if (keep(xs[i])) {
            n++;
        }
    }
    return n;
}

i32 main() {
    i64 xs[6] = {3, 12, 8, 21, 6, 15};
    printf("%d even, %d big\n", count(xs, &isEven), count(xs, &isBig));
    return 0;
}
```

```output
3 even, 3 big
```

## Tables of functions

An array of function pointers takes its size after the name, like any array:

```ktm run
i64 dbl(i64 x) {
    return x * 2;
}

i64 sq(i64 x) {
    return x * x;
}

i64 neg(i64 x) {
    return -x;
}

fn(i64) i64 ops[3] = {&dbl, &sq, &neg};
u8 *names[3] = {"double", "square", "negate"};

i32 main() {
    for (i64 i = 0; i < 3; i++) {
        printf("%s(5) = %d\n", names[i], ops[i](5));
    }
    return 0;
}
```

```output
double(5) = 10
square(5) = 25
negate(5) = -5
```

A table indexed by an enum makes a small dispatcher:

```ktm run
enum Op { add, sub, mul };

i64 doAdd(i64 a, i64 b) {
    return a + b;
}

i64 doSub(i64 a, i64 b) {
    return a - b;
}

i64 doMul(i64 a, i64 b) {
    return a * b;
}

fn(i64, i64) i64 handlers[3] = {&doAdd, &doSub, &doMul};

i64 apply(Op op, i64 a, i64 b) {
    return handlers[op](a, b);
}

i32 main() {
    printf("%d %d %d\n", apply(Op.add, 7, 3), apply(Op.sub, 7, 3), apply(Op.mul, 7, 3));
    return 0;
}
```

```output
10 4 21
```

## In a struct

A field can be a function pointer; it is called like a method, but the function is whatever
the field holds (see [Methods](13-methods.html) for run-time dispatch built this way):

```ktm run
i64 dbl(i64 x) {
    return x * 2;
}

struct Calc {
    u8 *name;
    fn(i64) i64 op;
};

i32 main() {
    Calc c = {"doubler", &dbl};
    printf("%s: %d\n", c.name, c.op(5));
    return 0;
}
```

```output
doubler: 10
```

A default in the pointer type's parameter list fills an argument the call leaves out:

```ktm run
i64 dbl(i64 x) {
    return x * 2;
}

struct Calc {
    fn(i64 x = 21) i64 op;
};

i32 main() {
    Calc c = {&dbl};
    printf("%d %d\n", c.op(5), c.op());
    return 0;
}
```

```output
10 42
```

## Returning a function

A function can return a function pointer. A `*` after a return type belongs to that type, so
`fn(u8 *) u8 *` returns a `u8 *`:

```ktm run
i64 dbl(i64 x) {
    return x * 2;
}

i64 sq(i64 x) {
    return x * x;
}

fn(i64) i64 choose(bool square) {
    if (square) {
        return &sq;
    }
    return &dbl;
}

i32 main() {
    fn(i64) i64 f = choose(true);
    printf("%d %d\n", f(5), choose(false)(5));
    return 0;
}
```

```output
25 10
```

## Calling any expression

Anything whose type is a function pointer can be called: a cast, a call's result, an assignment:

```ktm run
import cstr;

i64 add(i64 a, i64 b) {
    return a + b;
}

i64 mul(i64 a, i64 b) {
    return a * b;
}

void *lookup(u8 *name) {
    if (!cstr.cmp(name, "add")) {
        return &add;
    }
    return &mul;
}

i32 main() {
    fn(i64, i64) i64 f;
    printf("%d\n", ((fn(i64, i64) i64)lookup("add"))(2, 3));
    printf("%d\n", (f = &mul)(4, 5));
    return 0;
}
```

```output
5
20
```

## A pointer to a function pointer

To point at a function-pointer variable, put the `fn` type in parentheses: `(fn(i64) i64) *`.

```ktm run
i64 dbl(i64 x) {
    return x * 2;
}

i64 sq(i64 x) {
    return x * x;
}

void replace((fn(i64) i64) *slot, fn(i64) i64 g) {
    *slot = g;
}

i32 main() {
    fn(i64) i64 f = &sq;
    printf("%d ", f(5));
    replace(&f, &dbl);
    printf("%d %s\n", f(5), typeof(&f));
    return 0;
}
```

```output
25 10 (fn(i64) i64) *
```

## Defaults and variable arguments

A function pointer's parameters can have defaults and take extras in every form a function
does (see [Variable arguments](19-varargs.html)):

```ktm run
i64 add(i64 a, i64 b) {
    return a + b;
}

i64 count(...args) {
    return args.len;
}

i32 main() {
    fn(i64 a, i64 b = 10) i64 k = &add;
    fn(...args) i64 c = &count;
    printf("%d %d %d\n", k(1), k(1, 2), c(4, 5, 6));
    return 0;
}
```

```output
11 3 3
```

## Methods and overloads

`&Type.method` is a method as a function pointer whose first parameter is the struct pointer
(see [Methods](13-methods.html)):

```ktm run
struct Light { bool on; };

void Light.toggle() {
    self.on = !self.on;
}

i32 main() {
    Light l = {false};
    fn(Light *) void f = &Light.toggle;
    f(&l);
    printf("%d\n", l.on);
    return 0;
}
```

```output
1
```

For an overloaded function, `&name` picks the overload the destination's type asks for (see
[Overloading](18-overloading.html)).

## Callbacks with context

ktm has no closures: a function pointer is only an address. A callback that needs data of its
own gets it through an extra `void *` the caller passes along:

```ktm run
void each(i64[] xs, fn(i64, void *) void f, void *ctx) {
    for (i64 i = 0; i < xs.len; i++) {
        f(xs[i], ctx);
    }
}

struct Stats {
    i64 sum;
    i64 count;
};

void collect(i64 x, void *ctx) {
    Stats *s = ctx;
    s.sum += x;
    s.count++;
}

i32 main() {
    i64 xs[4] = {2, 4, 6, 8};
    Stats st = {0, 0};
    each(xs, &collect, &st);
    printf("sum %d of %d\n", st.sum, st.count);
    return 0;
}
```

```output
sum 20 of 4
```

## Callbacks with real types

A `void *` parameter on one side matches any pointer parameter on the other. So a callback can
take the types it really gets, and be passed where a `void *` one is expected, without a cast:

```ktm run
struct Item { i64 key; };

i64 itemLess(Item *a, Item *b) {
    return a.key < b.key;
}

i32 main() {
    Item a = {1}, b = {2};
    fn(void *, void *) i64 cmp = &itemLess;
    printf("%d %d\n", cmp(&a, &b), cmp(&b, &a));
    return 0;
}
```

```output
1 0
```

The `ctx` callback above can take its real type the same way:

```ktm run
struct Stats { i64 sum; };

void each(i64[] xs, fn(i64, void *) void f, void *ctx) {
    for (i64 i = 0; i < xs.len; i++) {
        f(xs[i], ctx);
    }
}

void add(i64 x, Stats *s) {
    s.sum += x;
}

i32 main() {
    i64 xs[3] = {1, 2, 3};
    Stats st = {0};
    each(xs, &add, &st);
    printf("%d\n", st.sum);
    return 0;
}
```

```output
6
```

A `void *` and `null` convert to and from a function pointer without a cast:

```ktm run
i64 dbl(i64 x) {
    return x * 2;
}

i32 main() {
    void *raw = &dbl;
    fn(i64) i64 back = raw;
    fn(i64) i64 none = null;
    printf("%d %d %d\n", back(4), raw == &dbl, none == null);
    return 0;
}
```

```output
8 1 1
```

## What doesn't convert

Any other mismatch is an error that names both types:

```ktm error
i64 t(i64 x) {
    return x;
}

i32 main() {
    fn(u8 *) i64 op = &t;
    return 0;
}
```

```output
cannot convert fn(i64) i64 to fn(u8*) i64
```

These need a cast:

- a different return type;
- different integer, struct, slice or function-pointer parameters;
- a pointer to a function pointer: `(fn(Item *) i64) *` is not `(fn(void *) i64) *`.

C's declarator form, `i64 (*f)(i64)`, is an error that shows the `fn(...)` spelling to use:

```ktm error
i64 dbl(i64 x) {
    return x * 2;
}

i32 main() {
    i64 (*f)(i64) = &dbl;
    return 0;
}
```

```output
a C function-pointer declarator: write `fn(i64) i64 f`
```

## Rules

- `fn(T1, T2) R` is a pointer to a function taking `T1, T2` and returning `R`; parameter names
  in it are optional.
- `&name` is a function's address, `&Type.method` a method's; calling the pointer calls the
  function, and the arguments are checked.
- Function pointers can be variables, array elements, fields, parameters and return values;
  `(fn(...) R) *` points at one.
- Defaults and extras work in the pointer type as in a function.
- `void *` parameters match any pointer parameter; `void *` and `null` convert to and from any
  function pointer. Everything else needs a cast.
- There are no closures: pass a context pointer along with the function.
- `fn` is a keyword.
