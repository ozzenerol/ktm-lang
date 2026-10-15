# Errors

ktm reports errors with exceptions: `throw` stops what is running and jumps to the nearest
`try` that encloses it, any number of calls up, where `catch` handles the error. This chapter
covers the `Error` struct, throwing, catching, error codes, rethrowing, what happens on the
way up, errors the library throws, `@caller` parameters for saying where a problem is, and
the report an uncaught error prints.

## The Error struct

Every thrown error is an `Error`. Every program has the struct, from the prelude:

```ktm
struct Error {
    i64 code;       /* what went wrong: usually an enum member */
    u8 *message;    /* what to tell a person, "" when there is none */
    u8 *file;       /* where the throw is */
    i32 line;
};
```

The code is for the program to decide what to do; the message is for people. The compiler
fills in the file and line of the `throw`.

## Throwing and catching

`throw(code)` throws an error with a code and an empty message. `throw(code, format, ...)`
adds a message, formatted like `printf`. `try { ... } catch (Error e) { ... }` runs the block
and, if anything in it throws, the catch block with the error in `e`:

```ktm run
enum BankError { funds, locked };

void withdraw(i64 balance, i64 amount) {
    if (amount > balance) {
        throw(BankError.funds, "need %d, have %d", amount, balance);
    }
    printf("withdrew %d\n", amount);
}

i32 main() {
    try {
        withdraw(100, 30);
        withdraw(100, 500);
        printf("never printed\n");
    } catch (Error e) {
        printf("error %d: %s\n", e.code, e.message);
    }
    printf("carrying on\n");
    return 0;
}
```

```output
withdrew 30
error 0: need 500, have 100
carrying on
```

The code after the throw in the `try` block doesn't run; the program continues after the
`catch` block.

The error carries where it was thrown:

```ktm run
enum Err { bad };

i32 main() {
    try {
        throw(Err.bad, "something broke");
    } catch (Error e) {
        printf("%s:%d: %s\n", e.file, e.line, e.message);
    }
    return 0;
}
```

```output
example.ktm:5: something broke
```

Without a message, `e.message` is the empty string, never `null`:

```ktm run
enum Err { bad };

i32 main() {
    try {
        throw(Err.bad);
    } catch (Error e) {
        printf("code %d, message [%s]\n", e.code, e.message);
    }
    return 0;
}
```

```output
code 0, message []
```

The message's format is checked like `printf`'s: it must get as many arguments as it has
`%` conversions.

```ktm error
enum Err { bad };

i32 main() {
    throw(Err.bad, "need %d, have %d", 500);
    return 0;
}
```

```output
format expects 2 arguments but 1 was supplied
```

### Catching without the error

When the error itself doesn't matter, `catch` takes no binding:

```ktm run
enum Err { any };

i32 main() {
    try {
        throw(Err.any);
    } catch {
        printf("something went wrong\n");
    }
    return 0;
}
```

```output
something went wrong
```

## Error codes

The code is an integer. An enum per kind of failure keeps codes readable, and lets the catch
decide by comparing:

```ktm run
enum ParseError { empty, notNumber, tooBig };

i64 parse(u8[] s) {
    if (!s.len) {
        throw(ParseError.empty, "empty input");
    }
    i64 n = 0;
    for (i64 i = 0; i < s.len; i++) {
        if (s[i] < '0' || s[i] > '9') {
            throw(ParseError.notNumber, "bad digit '%c'", s[i]);
        }
        n = n * 10 + s[i] - '0';
        if (n > 1000) {
            throw(ParseError.tooBig, "%d is over 1000", n);
        }
    }
    return n;
}

void tryParse(u8[] s) {
    try {
        printf("%d\n", parse(s));
    } catch (Error e) {
        if (e.code == ParseError.empty) {
            printf("(nothing)\n");
        } else {
            printf("rejected: %s\n", e.message);
        }
    }
}

i32 main() {
    tryParse("42");
    tryParse("");
    tryParse("4x");
    tryParse("99999");
    return 0;
}
```

```output
42
(nothing)
rejected: bad digit 'x'
rejected: 9999 is over 1000
```

The code converts like an argument to an integer parameter: a float is truncated.

```ktm run
i32 main() {
    try {
        throw(42.75);
    } catch (Error e) {
        printf("%d\n", e.code);
    }
    return 0;
}
```

```output
42
```

The library's modules each have their own codes, `pub const`s named `ERR_...` in the module
(`json.ERR_DECODE`, `crypto.ERR_CRYPTO`); `ERR_BOUNDS` is in the prelude. They are large
numbers chosen so that different modules never share one. A library of your own can do the
same: one `ERR_` constant per kind of failure, with values unlikely to clash.

## Through any number of calls

A throw leaves every function between it and the `try`: none of them returns normally, and
the rest of their code doesn't run. Their `defer` statements do run, innermost first, on the
way up (see [defer and scoped](16-defer-and-scoped.html)):

```ktm run
enum Err { bad };

void level3() {
    throw(Err.bad, "deep down");
}

void level2() {
    defer printf("level2 cleans up\n");
    level3();
    printf("not printed\n");
}

void level1() {
    defer printf("level1 cleans up\n");
    level2();
}

i32 main() {
    try {
        level1();
    } catch (Error e) {
        printf("caught: %s\n", e.message);
    }
    return 0;
}
```

```output
level2 cleans up
level1 cleans up
caught: deep down
```

The nearest enclosing `try` catches; the outer ones don't see the error:

```ktm run
enum Err { inner };

i32 main() {
    try {
        try {
            throw(Err.inner, "first");
        } catch (Error e) {
            printf("inner catch: %s\n", e.message);
        }
        printf("after the inner try\n");
    } catch (Error e) {
        printf("outer catch\n");
    }
    return 0;
}
```

```output
inner catch: first
after the inner try
```

A throw inside a `catch` block goes to the next `try` out:

```ktm run
enum Err { first, second };

i32 main() {
    try {
        try {
            throw(Err.first);
        } catch {
            throw(Err.second, "while handling the first");
        }
    } catch (Error e) {
        printf("%d: %s\n", e.code, e.message);
    }
    return 0;
}
```

```output
1: while handling the first
```

## Rethrowing

`throw(e)` throws the caught error on, unchanged: the same code, message, file and line. A
handler that only deals with some errors passes the others up this way:

```ktm run
enum Err { notFound, denied };

void open(u8 *path) {
    if (path[0] != '/') {
        throw(Err.notFound, "%s: no such file", path);
    }
    throw(Err.denied, "%s: permission denied", path);
}

void openOrSkip(u8 *path) {
    try {
        open(path);
    } catch (Error e) {
        if (e.code == Err.notFound) {
            printf("skipping: %s\n", e.message);
            return;
        }
        throw(e);
    }
}

i32 main() {
    try {
        openOrSkip("notes.txt");
        openOrSkip("/etc/shadow");
    } catch (Error e) {
        printf("fatal: %s (line %d)\n", e.message, e.line);
    }
    return 0;
}
```

```output
skipping: notes.txt: no such file
fatal: /etc/shadow: permission denied (line 7)
```

The line is 7, the original `throw` in `open`, not the `throw(e)`.

To add context on the way up, throw a new error whose message includes the old one:

```ktm run
enum Err { io, config };

void readFile(u8 *path) {
    throw(Err.io, "%s: no such file", path);
}

void loadConfig() {
    try {
        readFile("app.conf");
    } catch (Error e) {
        throw(Err.config, "loading the configuration: %s", e.message);
    }
}

i32 main() {
    try {
        loadConfig();
    } catch (Error e) {
        printf("%d: %s\n", e.code, e.message);
    }
    return 0;
}
```

```output
1: loading the configuration: app.conf: no such file
```

## The message belongs to the catch

`e.message` is freed when the `catch` block ends. To keep it longer, copy it:

```ktm run
import cstr, mem;

enum Err { bad };

i32 main() {
    u8 *kept = null;
    try {
        throw(Err.bad, "worth keeping %d", 42);
    } catch (Error e) {
        kept = cstr.dup(e.message);
    }
    printf("%s\n", kept);
    mem.free(kept);
    return 0;
}
```

```output
worth keeping 42
```

## Leaving a try early

`return`, `break`, `continue` and `goto` may leave a `try` block at any depth. A `try` left
that way stops catching at once:

```ktm run
enum Err { late };

i64 find(i64[] items, i64 want) {
    for (i64 i = 0; i < items.len; i++) {
        try {
            if (items[i] == want) {
                return i;
            }
            if (items[i] < 0) {
                break;
            }
        } catch {
        }
    }
    return -1;
}

i32 main() {
    i64 items[4] = {3, 7, -1, 9};
    try {
        printf("%d %d\n", find(items, 7), find(items, 9));
        throw(Err.late);
    } catch {
        printf("caught here, not in find\n");
    }
    return 0;
}
```

```output
1 -1
caught here, not in find
```

## Errors the library throws

Library functions throw too: an index out of range, text that isn't valid JSON, a network
failure. Each module documents its codes:

```ktm run
import json, vec;

struct Config { i64 port; };

i32 main() {
    vec.Vec<i64> v = vec.new<i64>();
    try {
        v.get(5);
    } catch (Error e) {
        printf("%d: %s\n", e.code == ERR_BOUNDS, e.message);
    }
    try {
        Config c = json.decode<Config>("{\"port\": \"eighty\"}");
    } catch (Error e) {
        printf("%d: %s\n", e.code == json.ERR_DECODE, e.message);
    }
    v.free();
    return 0;
}
```

```output
1: vec.Vec.get: index 5 of 0
1: 1:10: port: expected an integer, got a string
```

A slice index out of range throws `ERR_BOUNDS` as well (see
[Arrays and slices](10-arrays-and-slices.html)):

```ktm run
i32 main() {
    i64 nums[3] = {1, 2, 3};
    i64[] s = nums;
    i64 i = 3;
    try {
        printf("%d\n", s[i]);
    } catch (Error e) {
        printf("%s\n", e.message);
    }
    return 0;
}
```

```output
index 3 out of range for length 3
```

## Saying where: @caller

A helper that checks something and throws reports the helper's own line, which is rarely the
interesting one. A parameter marked `@caller("file")`, `@caller("line")` or `@caller("func")`
is filled in by every call with where that call is written, so the helper can say where it
was called from (see [Functions](07-functions.html)):

```ktm run
enum Check { failed };

void ensure(bool ok, u8 *what, @caller("file") u8 *file, @caller("line") i64 line) {
    if (!ok) {
        throw(Check.failed, "%s:%d: %s", file, line, what);
    }
}

i64 divide(i64 a, i64 b) {
    ensure(b != 0, "divide by zero");
    return a / b;
}

i32 main() {
    try {
        printf("%d\n", divide(10, 2));
        printf("%d\n", divide(1, 0));
    } catch (Error e) {
        printf("%s\n", e.message);
    }
    return 0;
}
```

```output
5
example.ktm:10: divide by zero
```

The caller never passes these parameters, and they can't have default values.

## Uncaught errors

An error that no `try` catches, in any thread, ends the program with exit status 1. ktm prints
it on stderr: its code, its message, where it was thrown, and the calls that led there.

```ktm run
i64 balance = 100;

void withdraw(i64 amount) {
    if (amount > balance) {
        throw(0, "need %d, have %d", amount, balance);
    }
    balance -= amount;
}

i32 main() {
    withdraw(30);
    withdraw(500);
    printf("not printed\n");
    return 0;
}
```

```output
uncaught error 0: need 500, have 70 (example.ktm:5)
  at example.ktm:5 in withdraw
  called from example.ktm:12 in main
```

- What the program printed before comes first (its output is flushed).
- A code whose bytes are all printable characters is shown as text, so a code like
  `0x6B6F6F42` prints as `'Book'`; any other is shown as a number.
- The calls are those of the last `throw`: an error caught and thrown on with `throw(e)` shows
  where it was thrown on, while the first line keeps the file and line of its first throw.
- The calls are listed the way a crash lists them (see
  [Safety and debugging](29-safety-and-debugging.html)), compiled and in the JIT alike.

## Threads and coroutines

Each thread has its own chain of `try` blocks: a throw is caught only by a `try` of its own
thread, and an uncaught error in any thread ends the whole program. A `try` may span a
coroutine's `yield`. See [Threads and threadlocal](27-threads-and-threadlocal.html).

## When to throw

Throw for what the caller can't reasonably check first and usually can't fix on the spot: a
file that is missing, input that isn't valid, a connection that failed. For an outcome the
caller expects and branches on every time ("not in the map", "end of input"), return a value:
a `bool`, `-1`, or `null`.

```ktm run
bool findIndex(i64[] items, i64 want, i64 *at) {
    for (i64 i = 0; i < items.len; i++) {
        if (items[i] == want) {
            *at = i;
            return true;
        }
    }
    return false;
}

i32 main() {
    i64 items[3] = {4, 8, 15};
    i64 at;
    if (findIndex(items, 8, &at)) {
        printf("found at %d\n", at);
    }
    if (!findIndex(items, 99, &at)) {
        printf("not there\n");
    }
    return 0;
}
```

```output
found at 1
not there
```

## Rules

- `throw(code)` and `throw(code, fmt, ...)` throw an `Error`; the format's argument count is
  checked like `printf`'s, the code converts to an integer.
- `try { } catch (Error e) { }` catches errors thrown anywhere inside, any number of calls
  down; `catch { }` catches without binding the error.
- The nearest enclosing `try` catches. A throw in a `catch` block goes to the next one out.
- `throw(e)` rethrows the error unchanged.
- `e.message` is never `null` and is freed when the catch block ends.
- Defers run on the way up; `return`, `break`, `continue` and `goto` may leave a `try`.
- `@caller("file")`, `("line")`, `("func")` parameters are filled in by each call.
- An uncaught error prints its code, message, place and calls on stderr and exits with 1.
- Each thread has its own `try` chain.
