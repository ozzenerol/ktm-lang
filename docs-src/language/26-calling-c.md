# Calling C

ktm calls C functions directly: declare one with `extern "c"` and call it, with no wrapper and
no cost beyond a normal call. Most of the C library is already declared in the `libc` module.
This chapter covers the `libc` module, declaring C functions and data, how C types are written
in ktm, C structs, passing ktm functions to C as callbacks, linking other C libraries, and how
ktm's names stay apart from C's.

## The libc module

`libc` is always linked, and `import libc;` gives its functions, structs and constants by their
C names:

```ktm run
import libc;

i32 main() {
    printf("%.4f %.0f\n", libc.sqrt(2.0), libc.pow(2.0, 10.0));
    printf("%d\n", libc.getenv("PATH") != null);
    printf("%d\n", libc.getpid() > 0);
    printf("%d\n", libc.strlen("hello"));
    return 0;
}
```

```output
1.4142 1024
1
1
5
```

`printf` itself is C's `printf`, declared for every program.

### Streams and exit codes

`libc.stdin`, `libc.stdout` and `libc.stderr` are C's streams, for `libc.fprintf` and
`libc.fflush`. `printf` writes to `libc.stdout`, so the two stay in order:

```ktm run
import libc;

i32 main() {
    printf("checking %d files\n", 2);
    libc.fprintf(libc.stdout, "%s: ok\n", "a.txt");
    libc.fflush(libc.stdout);
    return 0;
}
```

```output
checking 2 files
a.txt: ok
```

`libc.EXIT_SUCCESS` and `libc.EXIT_FAILURE` are `exit`'s statuses, as in C. `libc.exit` ends
the program at once, without running `defer`s:

```ktm run
import libc;

i32 main() {
    defer printf("not printed: exit skips defers\n");
    printf("leaving\n");
    libc.exit(libc.EXIT_SUCCESS);
    return 1;
}
```

```output
leaving
```

`libc.fprintf`'s format is checked like `printf`'s.

## Declaring a C function

For anything `libc` doesn't declare, write the prototype yourself, with `extern "c"` in front:

```ktm run
extern "c" i32 abs(i32 x);
extern "c" i64 strtol(u8 *s, u8 **end, i32 base);

i32 main() {
    u8 *end;
    printf("%d %d\n", abs(-5), strtol("ff zz", &end, 16));
    printf("[%s]\n", end);
    return 0;
}
```

```output
5 255
[ zz]
```

The prototype has to match the C function: ktm takes it at its word, and a wrong one gives wrong
results or a crash, not an error.

A C function that takes a variable number of arguments is declared with a plain `...`:

```ktm run
extern "c" i32 snprintf(u8 *buf, u64 size, u8 *fmt, ...);

i32 main() {
    u8 buf[32];
    i32 n = snprintf(buf, 32, "%s-%d", "id", 42);
    printf("%s (%d chars)\n", buf, n);
    return 0;
}
```

```output
id-42 (5 chars)
```

## C types in ktm

| C | ktm |
| --- | --- |
| `char`, `unsigned char` | `i8`, `u8` |
| `char *` | `u8 *` |
| `short`, `int` | `i16`, `i32` |
| `long`, `long long`, `ssize_t` | `i64` |
| `size_t`, `unsigned long` | `u64` |
| `socklen_t`, `unsigned int` | `u32` |
| `float`, `double` | `f32`, `f64` |
| `void *` | `void *` |
| `int (*)(int)` | `fn(i32) i32` |
| `bool` (C99) | `bool` |

`long` is 64 bits on Linux and macOS, the platforms ktm targets. A C string is a `u8 *` that ends
with a zero byte; a ktm `u8[]` or `String` passes as one with `.ptr` when it has its terminating
zero, as string literals do.

## C data

A C global is declared the same way, by its C name:

```ktm run
extern "c" u8 **environ;

i32 main() {
    i64 n = 0;
    while (environ[n]) {
        n++;
    }
    printf("%d\n", n > 0);
    return 0;
}
```

```output
1
```

When the symbol differs from the name you want, or between systems, `_extern` gives the symbol
as the assembly spells it, then the declaration. `libc` declares `stderr` this way, because macOS
calls it `__stderrp` (with Mach-O's leading `_`):

```ktm
#ifdef IS_MACOS
pub _extern ___stderrp FILE *stderr;
#else
pub _extern stderr FILE *stderr;
#endif
```

## C structs

A C struct is declared with the same fields in the same order; ktm lays structs out as C does
(see [Structs and unions](12-structs-and-unions.html)):

```ktm run
struct tm {
    i32 tm_sec, tm_min, tm_hour, tm_mday, tm_mon, tm_year, tm_wday, tm_yday, tm_isdst;
    i64 tm_gmtoff;
    u8 *tm_zone;
};

extern "c" tm *gmtime_r(i64 *t, tm *out);

i32 main() {
    i64 t = 86400 * 365;
    tm out;
    gmtime_r(&t, &out);
    printf("%d-%02d-%02d\n", out.tm_year + 1900, out.tm_mon + 1, out.tm_mday);
    return 0;
}
```

```output
1971-01-01
```

A C struct that ktm only passes around by pointer can be declared ahead and never defined (see
[Structs and unions](12-structs-and-unions.html)):

```ktm run
struct FILE;

extern "c" FILE *fopen(u8 *path, u8 *mode);

i32 main() {
    FILE *f = fopen("/nonexistent/file", "r");
    printf("%d\n", f == null);
    return 0;
}
```

```output
1
```

## Callbacks into ktm

A ktm function can be passed where C wants a function pointer: ktm functions use the C calling
convention (see [Function pointers](20-function-pointers.html)):

```ktm run
extern "c" void qsort(void *base, u64 n, u64 size, fn(void *, void *) i32 cmp);

i32 byValue(i64 *a, i64 *b) {
    if (*a < *b) {
        return -1;
    }
    return *a > *b;
}

i32 main() {
    i64 nums[4] = {42, 7, 19, 3};
    qsort(nums, 4, sizeof(i64), &byValue);
    for (i64 i = 0; i < 4; i++) {
        printf("%d ", nums[i]);
    }
    printf("\n");
    return 0;
}
```

```output
3 7 19 42 
```

`byValue` takes `i64 *` where C passes `void *`: a `void *` parameter on one side matches any
pointer on the other. To sort in ktm, `sort.slice` and `vec.Vec<T>.sort` are simpler (see
[the library](../std/index.html#sort)).

## Other C libraries

Name the library in the source with `#link`, or on the command line with `-clibs` (see
[The preprocessor](25-preprocessor.html)):

```ktm
#link <sqlite3>

extern "c" i32 sqlite3_libversion_number();

i32 main() {
    printf("%d\n", sqlite3_libversion_number());
    return 0;
}
```

```sh
$ ktm app.ktm -clibs="-lcurl -lm" -o app
```

`#link` works for compiled programs, the JIT and the REPL alike; the JIT loads the shared library
when the program starts.

## Names stay apart

ktm's own functions and globals get symbols of their own, so a ktm function called `read` or
`free` doesn't replace C's for the code that calls C's:

```ktm run
import mem;

void free(u8 *p) {
    printf("the program's own free\n");
}

i32 main() {
    u8 *buf = mem.alloc(64);
    mem.free(buf);
    free(null);
    printf("done\n");
    return 0;
}
```

```output
the program's own free
done
```

`mem.free` still releases the memory with C's `free`: the program's `free` is the symbol
`_K04free`, not `free`. Within the program, though, keep such names apart from the modules it
calls: a program function named like a module's function can be picked up by a call to the
module's (a known bug, being fixed), so prefer names of your own.

- A prototype without `extern "c"` is a ktm function, which must be defined in the program or in
  a ktm library; linking fails otherwise.
- In a module, an `extern "c"` declaration is the module's name (private unless `pub`) but keeps
  its C symbol.
- An `extern "c"` function has one C symbol and can't be overloaded.

## Rules

- `extern "c" T name(params);` declares a C function; `extern "c" T name;` a C global;
  `_extern SYMBOL declaration` binds a declaration to an exact symbol.
- The prototype must match C's; ktm doesn't check it against C.
- C structs are declared with the same fields in the same order.
- ktm functions use C's calling convention and can be C callbacks.
- `libc` is always linked; other libraries come with `#link` or `-clibs`.
- ktm's names never clash with C's symbols.
