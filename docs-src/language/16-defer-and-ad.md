# defer and ad

`defer` runs a statement when its block is left, however it is left: the end of the block, a
`return`, a `break`, or an error passing through. `ad` in front of a declaration frees the
variable the same way. Both keep the clean-up next to the code that needs it, so it can't be
forgotten on one of the ways out. This chapter covers `defer`, the order defers run in, what
they see, and `ad`.

## defer

`defer statement;` registers the statement to run when the enclosing `{ }` block is left:

```ktm run
i32 main() {
    defer printf("last\n");
    printf("first\n");
    printf("second\n");
    return 0;
}
```

```output
first
second
last
```

A block after `defer` defers all of it:

```ktm run
import mem;

void work() {
    u8 *buf = mem.alloc(64);
    defer {
        mem.free(buf);
        printf("freed\n");
    }
    printf("working\n");
}

i32 main() {
    work();
    return 0;
}
```

```output
working
freed
```

The usual pattern is to defer the release right after acquiring, so the two sit together:

```ktm run
import mem;

i64 sumSquares(i64 n) {
    i64 *squares = mem.alloc(n * sizeof(i64));
    defer mem.free(squares);
    for (i64 i = 0; i < n; i++) {
        squares[i] = i * i;
    }
    i64 total = 0;
    for (i64 i = 0; i < n; i++) {
        total += squares[i];
    }
    return total;
}

i32 main() {
    printf("%d\n", sumSquares(5));
    return 0;
}
```

```output
30
```

## Every way out

A deferred statement runs at the end of its block, on a `return`, on `break` and `continue`, on
a `goto` out of the block, and when a thrown error passes through:

```ktm run
enum Err { negative };

i64 check(i64 n) {
    defer printf("  check(%d) done\n", n);
    if (n < 0) {
        throw(Err.negative);
    }
    if (n == 0) {
        return 0;
    }
    return n * 2;
}

i32 main() {
    printf("%d\n", check(5));
    printf("%d\n", check(0));
    try {
        check(-1);
    } catch {
        printf("caught\n");
    }
    return 0;
}
```

```output
  check(5) done
10
  check(0) done
0
  check(-1) done
caught
```

With several early returns, one `defer` replaces a clean-up before each of them:

```ktm run
import mem;

i64 firstEven(i64[] input) {
    i64 *copy = mem.alloc(input.len * sizeof(i64));
    defer {
        mem.free(copy);
        printf("(copy freed)\n");
    }
    for (i64 i = 0; i < input.len; i++) {
        copy[i] = input[i];
        if (copy[i] % 2 == 0) {
            return copy[i];
        }
    }
    return -1;
}

i32 main() {
    i64 a[4] = {3, 5, 8, 9};
    i64 b[2] = {1, 3};
    printf("%d\n", firstEven(a));
    printf("%d\n", firstEven(b));
    return 0;
}
```

```output
(copy freed)
8
(copy freed)
-1
```

## The block is the scope

A defer belongs to the `{ }` block it is written in, not to the function. In a loop body it
runs at the end of every iteration, `continue` included:

```ktm run
i32 main() {
    for (i64 i = 0; i < 3; i++) {
        defer printf("end of %d\n", i);
        if (i == 1) {
            continue;
        }
        printf("body %d\n", i);
    }
    return 0;
}
```

```output
body 0
end of 0
end of 1
body 2
end of 2
```

A plain block limits it too:

```ktm run
i32 main() {
    printf("before\n");
    {
        defer printf("inner block left\n");
        printf("inside\n");
    }
    printf("after\n");
    return 0;
}
```

```output
before
inside
inner block left
after
```

`break` leaves the loop's body, so its defers run first:

```ktm run
i32 main() {
    for (i64 i = 0; i < 10; i++) {
        defer printf("done with %d\n", i);
        if (i == 2) {
            break;
        }
    }
    printf("loop over\n");
    return 0;
}
```

```output
done with 0
done with 1
done with 2
loop over
```

## Order

Several defers in a block run in reverse: the last one registered runs first. Inner blocks
finish before outer ones.

```ktm run
i32 main() {
    defer printf("outer\n");
    {
        defer printf("1st registered, runs last\n");
        defer printf("2nd registered, runs first\n");
    }
    printf("between\n");
    return 0;
}
```

```output
2nd registered, runs first
1st registered, runs last
between
outer
```

Reverse order is what nested resources need: what was acquired last is released first.

```ktm run
void open(u8 *what) {
    printf("open %s\n", what);
}

void close(u8 *what) {
    printf("close %s\n", what);
}

i32 main() {
    open("database");
    defer close("database");
    open("transaction");
    defer close("transaction");
    printf("work\n");
    return 0;
}
```

```output
open database
open transaction
work
close transaction
close database
```

## What a defer sees

The deferred statement runs later, as written: it sees the values variables have when the
block ends, not when the `defer` was written.

```ktm run
i32 main() {
    i64 x = 1;
    {
        defer printf("x is %d\n", x);
        x = 2;
    }
    return 0;
}
```

```output
x is 2
```

`return` evaluates its value before the defers run, so a function can return something its
defer then frees, as long as it isn't a pointer into what is freed:

```ktm run
import mem;

i64 first(i64 n) {
    i64 *buf = mem.alloc(n * sizeof(i64));
    defer mem.free(buf);
    buf[0] = 42;
    return buf[0];
}

i32 main() {
    printf("%d\n", first(4));
    return 0;
}
```

```output
42
```

## Only if reached

A `defer` is registered when execution reaches it. One written after an early return doesn't
run on that return:

```ktm run
void f(bool early) {
    if (early) {
        return;
    }
    defer printf("deferred\n");
    printf("late part\n");
}

i32 main() {
    f(true);
    f(false);
    return 0;
}
```

```output
late part
deferred
```

So a defer that frees something goes after the check that it was acquired:

```ktm run
import mem;

bool fill(i64 n) {
    if (n <= 0) {
        return false;
    }
    u8 *buf = mem.alloc(n);
    defer {
        mem.free(buf);
        printf("freed %d\n", n);
    }
    printf("filled %d\n", n);
    return true;
}

i32 main() {
    fill(0);
    fill(16);
    return 0;
}
```

```output
filled 16
freed 16
```

## What a deferred statement can't do

A deferred statement can't leave itself with `return`, `break`, `continue` or `goto` (a loop
inside it keeps its own `break`):

```ktm error
i32 main() {
    for (i64 i = 0; i < 2; i++) {
        defer {
            break;
        }
    }
    return 0;
}
```

```output
`break` can't leave a deferred statement
```

- A `goto` can't jump past a `defer` into the rest of its block.
- A throw from a deferred statement goes on like any other throw.
- Returning from `main` runs its defers; `os.exit()` and a crash don't.
- Switching between coroutines doesn't leave a block, so it runs no defers.
- Only functions that use `defer` pay for it.

## ad

`ad` in front of a declaration means `defer name.free();` right after it: the variable is freed
when its block ends.

```ktm run
import str;

i32 main() {
    ad String name = str.from("Ada");
    name.append(" Lovelace");
    printf("%.*s\n", name.len, name.ptr);
    return 0;
}
```

```output
Ada Lovelace
```

It works for a `String`, any slice the library allocated, and any struct with a `free()` method
that takes no arguments, or a pointer to such a struct:

```ktm run
import str;

struct Conn { i64 fd; };

void Conn.free() {
    printf("closing %d\n", self.fd);
}

i32 main() {
    ad String name = str.from("server");
    ad u8[][] words = str.split("a b c", " ");
    ad Conn c = {7};
    printf("%.*s has %d words\n", name.len, name.ptr, words.len);
    return 0;
}
```

```output
server has 3 words
closing 7
```

The library's containers all have `free()`, so `ad` is the usual way to hold them:

```ktm run
import map, set, vec;

i32 main() {
    ad vec.Vec<i64> v = vec.new<i64>();
    ad map.Map<u8 *, i64> m = map.new<u8 *, i64>();
    ad set.Set<i64> s = set.new<i64>();
    v.push(1);
    v.push(2);
    m.set("a", 1);
    s.add(1);
    printf("%d %d %d\n", v.len, m.len, s.len);
    return 0;
}
```

```output
2 1 1
```

Like a `defer`, an `ad` variable is freed on every way out of its block, a thrown error
included:

```ktm run
struct Res { u8 *name; };

void Res.free() {
    printf("released %s\n", self.name);
}

enum Err { bad };

void use() {
    ad Res r = {"lock"};
    throw(Err.bad, "failed while holding it");
}

i32 main() {
    try {
        use();
    } catch (Error e) {
        printf("caught: %s\n", e.message);
    }
    return 0;
}
```

```output
released lock
caught: failed while holding it
```

### Without a value

An `ad` variable can be declared without a value and filled in later. Until then it is empty
(every local starts zeroed), and freeing an empty `String`, slice or container does nothing:

```ktm run
import str;

i32 main() {
    ad String s;
    if (s.len == 0) {
        s = str.from("filled later");
    }
    printf("%.*s\n", s.len, s.ptr);
    return 0;
}
```

```output
filled later
```

A struct marked `@json` or `@yaml` that has no `free()` of its own is freed with the
`freeDecoded()` those attributes generate (see [Attributes](23-attributes.html)), so a value
decoded later is freed too:

```ktm run
import json;

@json struct User {
    String name;
    i64 age;
};

i32 main() {
    ad User u;
    u = json.decode<User>("{\"name\": \"ada\", \"age\": 36}");
    printf("%.*s is %d\n", u.name.len, u.name.ptr, u.age);
    return 0;
}
```

```output
ada is 36
```

An `ad` pointer with no value is `null`, and is freed only if it holds something by the end of
its block.

### ad and returning

Returning an `ad` variable is an error: it would be freed as the function returns. A function
that hands a value to its caller declares it without `ad`, and the caller decides:

```ktm error
import str;

String make() {
    ad String s = str.from("x");
    return s;
}

i32 main() {
    return 0;
}
```

```output
is declared with `ad`, so it is freed when this function returns
```

```ktm run
import str;

String greeting(u8[] who) {
    String s = str.format("hello, %.*s", who.len, who.ptr);
    return s;
}

i32 main() {
    ad String g = greeting("ada");
    printf("%.*s\n", g.len, g.ptr);
    return 0;
}
```

```output
hello, ada
```

A type with no `free()` can't be used with `ad`:

```ktm error
struct Point { i64 x, y; };

i32 main() {
    ad Point p = {1, 2};
    return 0;
}
```

```output
has no `free()` that takes no arguments
```

### ad rules

- `ad T x = value;` is `T x = value; defer x.free();`.
- It takes a `String`, a slice, or a struct (or pointer to one) with a `free()` that takes no
  arguments; an `@json`/`@yaml` struct without one uses `freeDecoded()`.
- Without a value the variable starts empty and is still freed at the end.
- Returning an `ad` variable is an error; a copy of it shares what it points to and is freed
  with it.
- `ad` goes on a local variable, not a field. It isn't a reserved word: a variable can be
  called `ad`.

## Rules

- `defer statement;` or `defer { ... }` runs when its enclosing block is left, by any way:
  the end, `return`, `break`, `continue`, `goto`, or a throw passing through.
- Defers of a block run last first; inner blocks before outer ones.
- A defer is registered when reached, and runs with the values of the moment it runs.
- `return` evaluates its value before the defers run.
- A deferred statement can't `return`, `break`, `continue` or `goto` out of itself.
- `os.exit()` and crashes don't run defers; returning from `main` does.
