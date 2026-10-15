# Safety and debugging

ktm is a systems language: it has pointers and manual memory, and a program can still go wrong in
the ways C programs do. What it adds is help in finding out where. This chapter covers the checks
ktm makes while a program runs (bounds checks, zeroed locals), the reports a failing program
prints (uncaught errors and crashes), finding memory bugs with `-Memsafe`, looking at what the
compiler made of a program, and using a native debugger.

## Bounds checks

Indexing a slice checks the index against its length; out of range, it throws `ERR_BOUNDS` with
the index, the length and where (see [Arrays and slices](10-arrays-and-slices.html)):

```ktm run
i32 main() {
    i64 nums[3] = {1, 2, 3};
    i64[] s = nums;
    i64 i = 3;
    try {
        printf("%d\n", s[i]);
    } catch (Error e) {
        printf("%s (%s:%d)\n", e.message, e.file, e.line);
    }
    return 0;
}
```

```output
index 3 out of range for length 3 (example.ktm:6)
```

Slicing is checked the same way:

```ktm run
i32 main() {
    u8[] word = "hello";
    i64 end = 9;
    try {
        u8[] part = word[2:end];
        printf("%d\n", part.len);
    } catch (Error e) {
        printf("%s\n", e.message);
    }
    return 0;
}
```

```output
slice 2:9 out of range for length 5
```

The check is what makes a slice safer than a pointer: a pointer has no length, so `p[i]` on a
plain pointer isn't checked. Prefer slices for anything indexed with a computed number.

## Locals start zeroed

A local variable declared without a value is zero: 0, 0.0, false, null, an empty `String` or
slice, a struct or array with every byte 0. Reading one before setting it is never garbage:

```ktm run
struct Report {
    i64 lines;
    f64 ratio;
    u8 *name;
};

i32 main() {
    i64 n;
    Report r;
    i64 grid[4];
    printf("%d %d %.1f %d %d\n", n, r.lines, r.ratio, r.name == null, grid[3]);
    return 0;
}
```

```output
0 0 0.0 1 0
```

## Uncaught errors

An error no `try` catches ends the program with exit status 1, after printing its code, message,
where it was thrown, and the calls that led there (see [Errors](15-errors.html)):

```ktm run
i64 parsePort(u8[] s) {
    if (s.len == 0) {
        throw(1, "empty port");
    }
    return 80;
}

i64 loadConfig() {
    return parsePort("");
}

i32 main() {
    printf("port %d\n", loadConfig());
    return 0;
}
```

```output
uncaught error 1: empty port (example.ktm:3)
  at example.ktm:3 in parsePort
  called from example.ktm:9 in loadConfig (inlined)
  called from example.ktm:13 in main
```

## Crash reports

A program that crashes (a null pointer, an integer division by zero, a stack overflow, memory it
doesn't own) prints what happened and where, with the calls that led there, before it dies of
the signal as it would without the report:

```ktm run
struct Node {
    i64 value;
    Node *next;
};

i64 second(Node *n) {
    return n.next.value;
}

i32 main() {
    Node last = {2, null};
    printf("%d\n", second(&last));
    return 0;
}
```

```output
crash: null pointer dereference (address 0x0)
  at example.ktm:7 in second (inlined)
  called from example.ktm:12 in main
```

`second` was inlined into `main` by the compiler, and still shows as a call of its own, marked
`(inlined)`.

An integer division by zero (`/` or `%`) crashes:

```ktm
i64 average(i64 total, i64 count) {
    return total / count;
}

i32 main() {
    printf("%d\n", average(10, 2));
    i64 none = 0;
    printf("%d\n", average(10, none));
    return 0;
}
```

```sh
$ ktm avg.ktm -o avg && ./avg
5
crash: integer division by zero
  at avg.ktm:2 in average (inlined)
  called from avg.ktm:8 in main
Floating point exception
```

What the program printed before the crash comes first. The arm64 processor doesn't trap a
division by zero by itself, so there the compiler tests a divisor that can be zero before dividing:
the crash, its report and the exit status (136, a SIGFPE) are the same on every platform. Check a
divisor that can be zero before dividing when the program should go on. The smallest `i64` divided
by -1 crashes the same way, with `crash: integer overflow in division (-9223372036854775808 / -1)`.

A recursion that never ends overflows the stack, and shows once, with how many times it
repeated:

```ktm
i64 depth(i64 n) {
    i64 pad[64];
    pad[0] = n;
    return depth(n + 1) + pad[0];
}

i32 main() {
    printf("%d\n", depth(0));
    return 0;
}
```

```sh
$ ktm deep.ktm -o deep && ./deep
crash: stack overflow: the calls below went too deep (a recursion that never ends?)
  at deep.ktm:1 in depth
  called from deep.ktm:4 in depth (14962 times)
  called from deep.ktm:4 in depth (inlined)
  called from deep.ktm:8 in main
Segmentation fault
```

Writing to a string literal is a crash too: literals live in read-only memory. Copy one with
`str.from` (or into a `u8` array) to change it.

```sh
$ cat lit.ktm
i32 main() {
    u8 *s = "literal";
    s[0] = 76;
    return 0;
}
$ ktm lit.ktm -o lit && ./lit
crash: invalid memory access at address 0x562a92f81361 (memory that was freed, never allocated, or read-only)
  at lit.ktm:3 in main
Segmentation fault
```

- Compiled programs and `-jit` report the same way, in every thread.
- A crash inside C code (`libc.strlen(null)`) shows `in C code`, then the ktm line that called it and that line's callers, up to `main`.
- A package's function shows by the name the program calls it by (`pg.Pool.exec`), its file
  under the package's path.
- The exit status is still the signal's: 139 for a segmentation fault, 136 for a division by
  zero on x86_64, as a shell reports them.
- Programs carry a table of their functions' lines for the report, about a quarter of their
  size.

## Memory bugs: -Memsafe

`ktm -jit -Memsafe` tracks every heap block while the program runs. Double frees and frees of
memory that was never allocated are reported, and skipped, as they happen; blocks that leaked are
listed when `main` returns or the program calls `exit()`. Each report names the line in your code:

```ktm
import mem;

i32 main() {
    u8 *a = mem.alloc(16);
    u8 *b = mem.alloc(32);
    mem.free(a);
    mem.free(a);
    return 0;
}
```

```sh
$ ktm -jit -Memsafe leaky.ktm
Free: double free of 0x555e8dc678c8 (16 bytes)
  allocated   at leaky.ktm:4 in main
  first freed at leaky.ktm:6 in main
  this free   at leaky.ktm:7 in main - ignored, heap unharmed
memsafe: 1 allocation(s) (32 bytes) never freed
  0x555e8dc67ea8        32 bytes  at leaky.ktm:5 in main
```

What the library allocates for you is tracked too, and reported at your line with the library
function it went through:

```ktm
import str;

i32 main() {
    for (i64 i = 0; i < 3; i++) {
        String s = str.format("item %d", i);
        printf("%s\n", s);
    }
    return 0;
}
```

```sh
$ ktm -jit -Memsafe loop.ktm
item 0
item 1
item 2
memsafe: 3 allocation(s) (768 bytes) never freed
  0x5576d4d659c8       256 bytes  at loop.ktm:5 in main, via str.format
  0x5576d4d670d8       256 bytes  at loop.ktm:5 in main, via str.format
  0x5576d4d672c8       256 bytes  at loop.ktm:5 in main, via str.format
```

The fix is `scoped String s = ...` (see [defer and scoped](16-defer-and-scoped.html)), and the report is empty.

- A leak is a block nothing points to any more. What a global, the library, or (when the program
  calls `exit()`) a live variable still holds at exit is in use, not a leak: it is listed apart,
  so a program that ends while holding its data needs no frees just for the report.
- The check is conservative: a word that looks like a pointer keeps a block alive.
- It works in any thread. The REPL always tracks allocations: `replHeap();` lists the live ones.
- `-Memsafe` is for the JIT; a test suite runs it with `ktm test -jit -Memsafe`.

## Looking under the hood

Each stage of the compiler can be shown:

```sh
$ ktm -tokens main.ktm         # what the lexer sees
$ ktm -ast main.ktm            # the parsed program
$ ktm --dump-ir main.ktm       # the SSA intermediate representation
$ ktm -S -o- main.ktm          # the generated assembly
$ ktm --expand main.ktm        # the code @json and @yaml wrote
$ ktm -cfg main.ktm && dot -Tpng main.dot -o main.png   # a control flow graph
```

`-cfg-png` and `-cfg-svg` write the image directly (they need Graphviz). `typeof(x)` prints a
type inside a program:

```ktm run
i32 main() {
    auto n = 3;
    auto ratio = n / 2.0;
    u8 *name = "x";
    printf("%s %s %s\n", typeof(n), typeof(ratio), typeof(&name));
    return 0;
}
```

```output
i64 f64 u8 **
```

## Native debuggers

A compiled ktm program is a normal executable: `gdb` and `lldb` can run it, set breakpoints on
function names, step through it and show registers and memory.

```sh
$ ktm app.ktm -o app
$ gdb ./app
(gdb) break main
(gdb) run
```

A module's functions have their own symbols, `_K` followed by the module and name (`geo.area` is
`_K3geo4area`), so break on that name.

## Rules

- Slice indexes and slices are bounds-checked and throw `ERR_BOUNDS`; pointer indexes aren't.
- Locals start zeroed.
- An uncaught error prints its place and calls and exits with 1; a crash prints what happened and
  the calls, then dies of its signal. Both work compiled and in the JIT.
- `ktm -jit -Memsafe` reports double frees, wild frees and leaks at your lines.
- `-tokens`, `-ast`, `--dump-ir`, `-S`, `--expand` and `-cfg` show the compiler's stages;
  compiled programs work with gdb and lldb.
