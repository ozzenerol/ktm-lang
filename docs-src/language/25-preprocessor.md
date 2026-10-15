# The preprocessor

The preprocessor runs before the compiler proper: it defines named values with `#define`,
keeps or drops code with `#if`, pastes files in with `#include`, and links C libraries with
`#link`. It is C's preprocessor, simplified: a macro names one value, and there are no
function-like macros. This chapter covers `#define`, how a macro's value is evaluated,
conditional compilation, macros from the command line, the predefined macros, `#include` and
`#link`.

## #define

`#define NAME value` names a number, a string, or an expression of other macros. The name can
then be used wherever its value could:

```ktm run
#define APP_NAME "demo"
#define VERSION 3
#define BUFFER (VERSION * 1024)
#define RATIO (VERSION / 2.0)

i32 main() {
    printf("%s v%d, %d bytes, %.1f\n", APP_NAME, VERSION, BUFFER, RATIO);
    return 0;
}
```

```output
demo v3, 3072 bytes, 1.5
```

A macro has the type of its value: `f64` if any operand is a float, otherwise `i64`.
Comparisons and `&&`, `||`, `!` give an `i64` 0 or 1, and a cast to a built-in type sets the type:

```ktm run
#define HALF ((f64)5 / 2)
#define BIG (1 << 40)
#define OK (3 > 2 && !0)

i32 main() {
    printf("%.1f %d %d\n", HALF, BIG, OK);
    printf("%s %s\n", typeof(HALF), typeof(OK));
    return 0;
}
```

```output
2.5 1099511627776 1
f64 i64
```

### A macro is a value, not text

A macro's value is worked out once, as a whole. Using it inside a larger expression uses that
value, so the parentheses C needs around a macro's body aren't needed:

```ktm run
#define TWO_PLUS_ONE 2 + 1

i32 main() {
    printf("%d\n", TWO_PLUS_ONE * 10);
    return 0;
}
```

```output
30
```

In C, the same macro would paste `2 + 1 * 10` and give 12.

- Names in a macro's value must be other macros; variables, structs and functions are unknown
  to it.
- A line ending in `\` goes on to the next.

```ktm run
#define WIDTH 80
#define HEIGHT 24
#define CELLS WIDTH * \
              HEIGHT

i32 main() {
    printf("%d\n", CELLS);
    return 0;
}
```

```output
1920
```

### No function-like macros

A macro can't take parameters. `#define SQ(x) ((x) * (x))` is an error; write a function, which
the compiler inlines:

```ktm error
#define SQ(x) ((x) * (x))

i32 main() {
    return 0;
}
```

```output
function-like macros are not supported
```

```ktm run
i64 sq(i64 x) {
    return x * x;
}

i32 main() {
    printf("%d\n", sq(7));
    return 0;
}
```

```output
49
```

With a space before the parenthesis, `#define CALC (2 * 21)` is an ordinary macro.

### Macros or constants

For a number used in code, a `const` is usually better: it has a declared type and follows the
scope rules of variables (see [Variables](03-variables.html)). A macro is what `#if` needs, and
what a `-D` from the command line sets.

```ktm run
const i64 MAX_USERS = 100;
#define MAX_GROUPS 10

i32 main() {
    i64 slots[MAX_GROUPS];
    printf("%d %d\n", MAX_USERS, sizeof(slots) / sizeof(i64));
    return 0;
}
```

```output
100 10
```

### #undef

`#undef NAME` removes a macro from that point on; code above it keeps the old value:

```ktm run
#define MODE 1

i32 main() {
    printf("%d\n", MODE);
#undef MODE
#ifndef MODE
    printf("MODE is gone\n");
#endif
    return 0;
}
```

```output
1
MODE is gone
```

## Conditional compilation

`#if`, `#ifdef`, `#ifndef`, `#elif`, `#else` and `#endif` keep or drop lines, as in C.
`defined(NAME)` tests whether a macro exists:

```ktm run
#define LEVEL 2

i32 main() {
#if LEVEL > 2
    printf("high\n");
#elif LEVEL == 2 && !defined(QUIET)
    printf("level two\n");
#else
    printf("low\n");
#endif
    return 0;
}
```

```output
level two
```

Dropped lines aren't compiled at all, so they may use names that don't exist:

```ktm run
i32 main() {
#ifdef FEATURE_X
    featureX();
#endif
    printf("built without FEATURE_X\n");
    return 0;
}
```

```output
built without FEATURE_X
```

`#error message` stops the build with the message, for a configuration that can't work:

```ktm error
#define THREADS 0

#if THREADS < 1
#error "THREADS must be at least 1"
#endif

i32 main() {
    return 0;
}
```

```output
THREADS must be at least 1
```

- An `#else` or `#endif` with no `#if`, a second `#else`, or an `#if` never closed is an error.
- Extra tokens after an `#if` condition are an error.

## From the command line

`-DNAME` defines a macro, with no value, for the whole build:

```ktm
i32 main() {
#ifdef VERBOSE
    printf("verbose mode\n");
#else
    printf("quiet mode\n");
#endif
    return 0;
}
```

```sh
$ ktm -DVERBOSE app.ktm -o app && ./app
verbose mode
$ ktm app.ktm -o app && ./app
quiet mode
```

## Predefined macros

| Macro | Defined when |
| --- | --- |
| `IS_LINUX`, `__linux__` | compiling for Linux |
| `IS_MACOS`, `__macos__` | compiling for macOS |
| `IS_X86_64`, `__x86_64__` | targeting x86_64 |
| `IS_ARM_64`, `__aarch64__` | targeting arm64 |
| `__ARCH__` | always: the architecture's name, as a string |
| `__KTM_JIT__` / `__KTM_AOT__` | running in the JIT or the REPL / compiling a binary |
| `__KTM_VERSION__`, `__DATE__`, `__TIME__`, `__TIMESTAMP__` | always |
| `__KTM_LINK_SQLITE3__` | ktm was built with SQLite |

Code for one platform:

```ktm
#ifdef IS_LINUX
u8 *os = "Linux";
#elif defined(IS_MACOS)
u8 *os = "macOS";
#else
#error "unsupported platform"
#endif
```

```ktm run
i32 main() {
#if defined(IS_LINUX) || defined(IS_MACOS)
    printf("a supported system\n");
#endif
#if defined(IS_X86_64) || defined(IS_ARM_64)
    printf("a 64-bit processor\n");
#endif
    return 0;
}
```

```output
a supported system
a 64-bit processor
```

To check another platform's branches without its machine, compile to assembly for it:
`ktm -S --target=aarch64-apple-darwin file.ktm`.

### #ifjit and #ifaot

`#ifjit` and `#ifaot` are short for `#ifdef __KTM_JIT__` and `#ifdef __KTM_AOT__`:

```ktm
i32 main() {
#ifjit
    printf("running in the JIT\n");
#else
    printf("running as a compiled binary\n");
#endif
    return 0;
}
```

```sh
$ ktm app.ktm -o app && ./app
running as a compiled binary
$ ktm -jit app.ktm
running in the JIT
```

## #include and #link

`#include "file"` pastes a file in, relative to the including file; `#include <file>` reads it
from the installed include directory. For ktm code, prefer modules (see
[Modules and packages](22-modules-and-packages.html)): they are compiled once and keep their
private names private.

`#link <name>` links a C library, like `-lname`; `#link "path"` links a library file. It works
for compiled programs, the JIT and the REPL alike (see [Calling C](26-calling-c.html)):

```ktm
#link <sqlite3>          // like -lsqlite3
#link "./libmine.so"     // a path, relative to the working directory
```

A file that can't be read is an error at the `#include`, and the rest of the file is still
checked.

## Rules

- `#define NAME value` names one value: a number, a string, or an expression of macros. Its type
  is `f64` with any float operand, otherwise `i64`.
- A macro's value is evaluated as a whole before it is used; there are no function-like
  macros.
- `#if`/`#ifdef`/`#ifndef`/`#elif`/`#else`/`#endif`/`#undef`/`#error` work as in C;
  `#ifjit`/`#ifaot` test the JIT.
- `-DNAME` defines a macro from the command line.
- `#include` pastes a file; `#link` links a C library.
