# Variable arguments

A function can take any number of extra arguments after its fixed ones. Inside the function
they are a slice, with no `va_list` to walk. There are three kinds: typed extras (`T ...name`),
extras of any type (`any ...name`) and untyped 64-bit slots for format functions
(`...name`). This chapter covers each, passing extras on to another function, and C's own `...`.

## Typed extras

`T ...name` as the last parameter takes any number of `T`s, zero included. Each is checked and
converted like a normal argument. Inside, `name` is a `T[]`:

```ktm run
i64 sum(i64 ...nums) {
    i64 total = 0;
    for (i64 i = 0; i < nums.len; i++) {
        total += nums[i];
    }
    return total;
}

i32 main() {
    printf("%d %d %d\n", sum(), sum(5), sum(1, 2, 3, 4));
    return 0;
}
```

```output
0 5 10
```

The extras are converted to `T`, so integers given for `f64 ...` become floats:

```ktm run
f64 avg(f64 ...xs) {
    if (!xs.len) {
        return 0;
    }
    f64 total = 0;
    for (i64 i = 0; i < xs.len; i++) {
        total += xs[i];
    }
    return total / xs.len;
}

i32 main() {
    printf("%.2f %.2f %.2f\n", avg(1, 2, 4), avg(2.5), avg());
    return 0;
}
```

```output
2.33 2.50 0.00
```

An argument of the wrong type is refused like for any parameter:

```ktm error
i64 sum(i64 ...nums) {
    return nums.len;
}

i32 main() {
    return sum(1, "two");
}
```

```output
argument 2 of `sum`: expected i64, got u8[4]
```

`T` is a number type or a pointer type. Structs travel as pointers:

```ktm run
struct Point { i64 x, y; };

Point centroid(Point * ...ps) {
    Point c = {0, 0};
    for (i64 i = 0; i < ps.len; i++) {
        c.x += ps[i].x;
        c.y += ps[i].y;
    }
    c.x /= ps.len;
    c.y /= ps.len;
    return c;
}

i32 main() {
    Point a = {0, 0}, b = {4, 0}, c = {2, 6};
    Point m = centroid(&a, &b, &c);
    printf("(%d, %d)\n", m.x, m.y);
    return 0;
}
```

```output
(2, 2)
```

```ktm error
struct Point { i64 x, y; };

void take(Point ...ps) {
}

i32 main() {
    return 0;
}
```

```output
must be numbers or pointers (or `any ...ps`), got Point
```

For values of mixed or struct types, use `any ...name` (below).

### Using the slice

- `name.len` is how many extras were passed; `name[i]` is one of them, bounds-checked.
- `name` can be sliced, looped over and passed to a `T[]` parameter.
- `name` itself can't be assigned.

```ktm run
i64 largest(i64[] xs) {
    i64 best = xs[0];
    for (i64 i = 1; i < xs.len; i++) {
        if (xs[i] > best) {
            best = xs[i];
        }
    }
    return best;
}

i64 maxOf(i64 first, i64 ...rest) {
    if (!rest.len) {
        return first;
    }
    i64 m = largest(rest);
    return m > first ? m : first;
}

i32 main() {
    printf("%d %d\n", maxOf(3), maxOf(3, 9, 4));
    return 0;
}
```

```output
3 9
```

## Fixed and default parameters first

The extras come after every fixed parameter, defaults included. To give extras while keeping a
default, leave that argument empty:

```ktm run
void tag(u8 *label, u8 *sep = ", ", u8 * ...items) {
    printf("%s:", label);
    for (i64 i = 0; i < items.len; i++) {
        printf("%s%s", i ? sep : " ", items[i]);
    }
    printf("\n");
}

i32 main() {
    tag("fruit", , "apple", "pear");
    tag("path", "/", "usr", "lib", "ktm");
    tag("empty");
    return 0;
}
```

```output
fruit: apple, pear
path: usr/lib/ktm
empty:
```

## Passing them on

`...name` as the last argument of a call passes a function's extras on to another function
that takes the same kind. It must be the only extra argument of that call:

```ktm run
i64 sum(i64 ...nums) {
    i64 t = 0;
    for (i64 i = 0; i < nums.len; i++) {
        t += nums[i];
    }
    return t;
}

i64 sumTwice(i64 ...nums) {
    return 2 * sum(...nums);
}

i32 main() {
    printf("%d\n", sumTwice(1, 2, 3));
    return 0;
}
```

```output
12
```

A slice can be spread the same way, so part of the extras can be passed on:

```ktm run
f64 avg(f64 ...xs) {
    f64 total = 0;
    for (i64 i = 0; i < xs.len; i++) {
        total += xs[i];
    }
    return xs.len ? total / xs.len : 0;
}

f64 avgWithoutFirst(f64 ...xs) {
    return avg(...xs[1:]);
}

i32 main() {
    printf("%.2f\n", avgWithoutFirst(100, 1, 2));
    return 0;
}
```

```output
1.50
```

Spreading costs nothing: the extras travel as a count and a pointer to the array the caller
built.

## Extras of any type

`any ...name` takes extras of any type, like Go's `...any`. Inside, `name` is a
`reflect.Value[]`: each extra's type, and the address of a copy made at the call, which lives
until the calling function returns. A string literal or an array comes as a slice. It needs
`import reflect;` (see [Reflection](24-reflection.html)).

```ktm run
import reflect;

struct Point { i64 x, y; };

void show(any ...args) {
    for (i64 i = 0; i < args.len; i++) {
        reflect.Value v = args[i];
        printf("%.*s", v.type.name.len, v.type.name.ptr);
        if (v.type.kind == reflect.Kind.int) {
            printf(" %d", *(i64 *)v.ptr);
        }
        printf("\n");
    }
}

i32 main() {
    Point p = {1, 2};
    show(42, "text", 2.5, p, &p);
    return 0;
}
```

```output
i64 42
u8[]
f64
Point
Point *
```

A function that formats whatever it gets can switch on the kind:

```ktm run
import reflect;

void describe(any ...args) {
    for (i64 i = 0; i < args.len; i++) {
        reflect.Value v = args[i];
        if (v.type.kind == reflect.Kind.int) {
            printf("int %d", *(i64 *)v.ptr);
        } else if (v.type.kind == reflect.Kind.float) {
            printf("float %.1f", *(f64 *)v.ptr);
        } else if (v.type.kind == reflect.Kind.slice) {
            u8[] s = *(u8[] *)v.ptr;
            printf("text %.*s", s.len, s.ptr);
        } else {
            printf("a %.*s", v.type.name.len, v.type.name.ptr);
        }
        printf(i + 1 < args.len ? ", " : "\n");
    }
}

i32 main() {
    describe(1, 2.5, "three", true);
    return 0;
}
```

```output
int 1, float 2.5, text three, a bool
```

- `...name` passes them on to another `any` function.
- A `String` returned by a call is refused as an `any` extra, since nothing would free it:
  put it in a variable first.
- The library takes `any ...` where Go takes `...any`: SQL parameters, log fields.

## Untyped extras, for formats

`...name` without a type takes each extra as a 64-bit slot, the way format functions want
them. `name` is a `u64[]`: a number as it is, a float as its bits, a pointer as its address.
`cstr.formatArgs` formats them like `printf`:

```ktm run
import cstr, mem;

void logf(u8 *fmt, ...args) {
    u8 *msg = cstr.formatArgs(null, fmt, args);
    printf("[log] %s\n", msg);
    mem.free(msg);
}

i32 main() {
    logf("%d files in %s, %.1f%% full", 3, "/tmp", 42.5);
    return 0;
}
```

```output
[log] 3 files in /tmp, 42.5% full
```

Nothing checks these extras against the format: the function's own code decides what each
slot is, so a wrong count or type shows only at run time.

A `String` or a `u8[]` goes to such a function as it goes to `printf`, when its format
parameter is called `fmt` (right before `...args`): with a literal format, `%s` prints its
bytes (a `u8[]` needs no terminating zero).

```ktm run
import cstr, mem, str;

void reply(u8 *fmt, ...args) {
    u8 *msg = cstr.formatArgs(null, fmt, args);
    printf("> %s\n", msg);
    mem.free(msg);
}

i32 main() {
    ad String user = str.from("ada");
    u8[] cmd = "GET key";
    reply("%s ran %s", user, cmd[0:3]);
    return 0;
}
```

```output
> ada ran GET
```

To read a slot by hand, an integer is the slot itself and a float is read back through a
pointer:

```ktm run
void show(...args) {
    printf("%d slots: %d and %.2f\n", args.len, args[0], *(f64 *)&args[1]);
}

i32 main() {
    show(7, 2.5);
    return 0;
}
```

```output
2 slots: 7 and 2.50
```

## C's ...

A plain `...` with no name is C's variadic convention, for declaring C functions that take one
(see [Calling C](26-calling-c.html)):

```ktm run
extern "c" i32 snprintf(u8 *buf, u64 size, u8 *fmt, ...);

i32 main() {
    u8 buf[32];
    snprintf(buf, 32, "%d-%s", 7, "seven");
    printf("%s\n", buf);
    return 0;
}
```

```output
7-seven
```

## Which kind to use

| Kind | Inside | Use it for |
| --- | --- | --- |
| `T ...name` | a `T[]` | many numbers or pointers of one type: numbers to add, paths to join |
| `any ...name` | a `reflect.Value[]` | values of any type, looked at one by one |
| `...name` | a `u64[]` of slots | formats passed on to `cstr.formatArgs` |
| `...` (no name) | nothing | declaring a C function |

## Rules

- The extras parameter is the last one; fixed and default parameters come first, and an empty
  argument keeps a default.
- `T ...xs`: `T` is a number or pointer type; each extra is checked and converted to `T`;
  `xs` is a `T[]` that can't be assigned.
- `any ...xs`: `xs` is a `reflect.Value[]` of copies made at the call; it needs `import reflect;`.
- `...xs`: `xs` is a `u64[]` of 64-bit slots, not checked against any format.
- `f(...xs)` passes extras (or a slice) on; it is the only extra argument of that call, and
  can't go to an overloaded function.
