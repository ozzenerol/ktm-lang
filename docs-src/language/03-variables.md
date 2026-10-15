# Variables

Declaring local and global variables, the zero they start with, letting the compiler pick the type,
`static` locals, constants, and where a name is visible.

## Declaring a variable

A declaration is a type, a name and, optionally, a first value. A local can be declared anywhere in
a block, and is visible from there to the end of the block.

```ktm run
void main() {
    i64 count = 3;
    f64 ratio = 0.25;
    u8 *name = "ktm";
    bool ready = true;
    printf("%d %.2f %s %d\n", count, ratio, name, ready);
}
```

```output
3 0.25 ktm 1
```

Several variables of one type can share a declaration. Each `*` belongs to the name after it, as in
C.

```ktm run
void main() {
    i64 a = 1, b = 2, c;
    c = a + b;
    u8 *name = "ktm", letter = 'h';    // name is a u8 *, letter a u8
    printf("%d %s %c\n", c, name, letter);
}
```

```output
3 ktm h
```

## Locals start zeroed

A local declared without a value is zero: `0`, `0.0`, `false`, `null`, an empty `String` or slice,
a struct or array with every byte 0. You never read garbage left on the stack.

```ktm run
struct Report { i64 lines; f64 ratio; u8 *name; };

void main() {
    i64 n;
    f64 f;
    bool b;
    u8 *p;
    Report r;
    i64 nums[4];
    printf("%d %.1f %d %d\n", n, f, b, p == null);
    printf("%d %.1f %d\n", r.lines, r.ratio, r.name == null);
    printf("%d %d %d %d\n", nums[0], nums[1], nums[2], nums[3]);
}
```

```output
0 0.0 0 1
0 0.0 1
0 0 0 0
```

It is zero again each time its declaration runs, so a variable declared inside a loop starts fresh on
every pass:

```ktm run
void main() {
    for (i64 i = 0; i < 3; i++) {
        i64 total;
        total += i;
        printf("pass %d: total %d\n", i, total);
    }
}
```

```output
pass 0: total 0
pass 1: total 1
pass 2: total 2
```

One exception: an array bigger than 4 KiB with no `String` or slice in it, such as `u8 buf[65536];`,
is a buffer you are about to fill, and is left as it is so that its memory isn't written for nothing.
Write `u8 buf[65536] = {0};` when you need it zeroed.

## Global variables

A variable declared outside every function is a global: it lives for the whole program, and every
function below its declaration sees it. A global without a value is zero.

```ktm run
i64 gTotal = 100, gCount;
u8 *gName = "server";

void bump() { gCount++; }

void main() {
    bump();
    bump();
    printf("%s: %d of %d\n", gName, gCount, gTotal);
}
```

```output
server: 2 of 100
```

A global's first value can be any expression, function calls included. These run in the order the
globals are written, before `main`:

```ktm run
i64 triple(i64 n) {
    printf("computing %d\n", n);
    return n * 3;
}

i64 gA = triple(1);
i64 gB = triple(gA);

void main() {
    printf("main sees %d and %d\n", gA, gB);
}
```

```output
computing 1
computing 3
main sees 3 and 9
```

A global can also be one copy per thread: see [threadlocal](27-threads-and-threadlocal.html).

## auto

`auto` takes the type of the first value.

```ktm run
void main() {
    auto count = 3;
    auto price = 9.99;
    auto label = "total";
    auto half = count / 2.0;
    auto big = count * 1000000000000;
    printf("%s %s %s %s %s\n", typeof(count), typeof(price), typeof(label), typeof(half), typeof(big));
}
```

```output
i64 f64 u8[6] f64 i64
```

It saves the most typing with long type names, such as generic containers, and in a range-based
`for`:

```ktm run
import map;

void main() {
    auto ages = map.new<u8[], i64>();
    ages.set("ada", 36);
    ages.set("linus", 54);
    i64 nums[] = {1, 2, 3};
    for (auto x : nums) {
        printf("%d ", x);
    }
    printf("| ada is %d\n", ages.getOr("ada", 0));
    ages.free();
}
```

```output
1 2 3 | ada is 36
```

An `auto` variable needs a first value to take its type from:

```ktm error
void main() {
    auto x;
}
```

```output
error:
```

## static locals

A `static` local keeps its value between calls: it is created once, like a global, but only its
function can see it.

```ktm run
i64 nextId() {
    static i64 id = 0;
    id++;
    return id;
}

void main() {
    nextId();
    nextId();
    printf("%d\n", nextId());
}
```

```output
3
```

```ktm run
void report(u8 *what) {
    static i64 calls;
    calls++;
    printf("call %d: %s\n", calls, what);
}

void main() {
    report("start");
    report("middle");
    report("end");
}
```

```output
call 1: start
call 2: middle
call 3: end
```

On a function or a global, `static` means something else: the name stays private to its file. See
[Modules and packages](22-modules-and-packages.html).

## Constants

`const` makes a declaration read-only. With a value known at compile time, it is a compile-time
constant: it can size an array, label a `case`, or build another constant.

```ktm run
const i64 MAX_USERS = 4;
const i64 DOUBLE_MAX = MAX_USERS * 2;
const f64 RATE = 0.5;
const u8 *GREETING = "hello";

i64 gUsers[MAX_USERS];

i64 classify(i64 n) {
    switch (n) {
        case MAX_USERS: return 1;
        default: return 0;
    }
}

void main() {
    printf("%d %d %.1f %s\n", MAX_USERS, DOUBLE_MAX, RATE, GREETING);
    printf("%d %d\n", classify(4), sizeof(gUsers));
}
```

```output
4 8 0.5 hello
1 32
```

Assigning to a constant is an error, with `=`, `+=` or `++`:

```ktm error
const i64 LIMIT = 10;

void main() {
    LIMIT = 11;
}
```

```output
error:
```

A local can be `const` too, and so can a parameter, which then can't be assigned inside the
function:

```ktm run
i64 twice(const i64 n) {
    return n * 2;
}

void main() {
    const i64 start = 21;
    printf("%d\n", twice(start));
}
```

```output
42
```

- A `const` variable needs a value; a parameter gets its value from the call.
- `const` goes only at the start of a declaration. It isn't part of a type as in C: `u8 const *p`,
  a `const` field, a `const` return type or a cast to `const` are errors.
- Prefer `const` to `#define` for numbers: it has a type, and editors know what it is.

## Scope and shadowing

A name is visible from its declaration to the end of its block. An inner block can declare a name
that an outer one has; inside the inner block, the name means the inner variable.

```ktm run
i64 gLevel = 1;

void main() {
    printf("global: %d\n", gLevel);
    i64 gLevel = 2;
    printf("local: %d\n", gLevel);
    {
        i64 gLevel = 3;
        printf("inner block: %d\n", gLevel);
    }
    printf("local again: %d\n", gLevel);
}
```

```output
global: 1
local: 2
inner block: 3
local again: 2
```

Declaring the same name twice in one block is an error:

```ktm error
void main() {
    i64 x = 1;
    i64 x = 2;
}
```

```output
error:
```

A local may even have the name of an imported module. For its scope, the name means the local:

```ktm run
import str;

void main() {
    u8[] str = "a local called str";
    printf("%s, %d bytes\n", str, str.len);
}
```

```output
a local called str, 18 bytes
```

A global can't: `i64 str;` at file scope would hide the module for the whole file, and is an error.
