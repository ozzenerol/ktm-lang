# A first program

What a ktm source file looks like, where a program starts, how it prints, how it ends, and the
ways to build and run it.

## Hello

A program starts at `main`. `printf` prints, as in C.

```ktm run
void main() {
    printf("Hello\n");
}
```

```output
Hello
```

Save it as `hello.ktm`. There are three ways to run it:

```sh
ktm hello.ktm -o hello && ./hello    # compile to a native binary, then run it
ktm -run hello.ktm                   # compile to a temporary binary and run it
ktm -jit hello.ktm                   # compile in memory and run, no file written
```

The binary needs nothing installed to run: ktm's library is linked into it. `-jit` is the fastest
way to try something; a binary is what you deploy.

## Three forms of main

`main` returns nothing, or the program's exit code, and can take the command line.

| Form | Exit code |
| --- | --- |
| `void main()` | 0 |
| `i32 main()` | what it returns |
| `i32 main(i32 argc, u8 **argv)` | what it returns; `argv` holds the arguments |

```ktm run
i32 main() {
    printf("returning 3\n");
    return 3;
}
```

```output
returning 3
```

The shell sees 3 as the exit status (`echo $?`). With arguments:

```ktm run
i32 main(i32 argc, u8 **argv) {
    printf("%d argument(s)\n", argc);
    printf("the program is called %s\n", argc > 0 ? "something" : "nothing");
    for (i64 i = 1; i < argc; i++) {
        printf("argument %d: %s\n", i, argv[i]);
    }
    return 0;
}
```

```output
1 argument(s)
the program is called something
```

`argv[0]` is the program's own name, so a program run with no arguments has `argc == 1`. After
`ktm -jit file.ktm` or `ktm -run file.ktm`, everything after the file name goes to the program:
`ktm -jit tool.ktm --verbose input.txt`.

## What goes where

A file holds declarations: imports, types, functions, global variables and constants.
Statements go inside functions. Imports come first.

```ktm run
const i64 LIMIT = 3;
i64 gCalls = 0;

struct Point { i64 x; i64 y; };

i64 manhattan(Point p) {
    gCalls++;
    return p.x + p.y;
}

void main() {
    Point p = {2, 5};
    for (i64 i = 0; i < LIMIT; i++) {
        manhattan(p);
    }
    printf("%d after %d calls\n", manhattan(p), gCalls);
}
```

```output
7 after 4 calls
```

- Order doesn't matter between functions: `main` can call a function declared below it.
- Names at file scope are visible in the whole file.
- A global's initial value can run code. Those run in order, before `main`.

```ktm run
i64 square(i64 n) { return n * n; }

i64 gBase = 7;
i64 gArea = square(gBase);    // runs before main

void main() {
    printf("%d\n", gArea);
}
```

```output
49
```

## Comments

Line comments start with `//`, block comments are `/* ... */`, as in C. Block comments don't nest.

```ktm run
/* A block comment
   over several lines. */
void main() {
    // to the end of the line
    printf("comments are ignored\n");   /* or in the middle */
}
```

```output
comments are ignored
```

## Printing

`printf` takes a format and the values for it, one value for each conversion (`%d`, `%s`...).

```ktm run
void main() {
    printf("plain text\n");
    printf("%s is %d years old\n", "Ada", 36);
    printf("%x %X %o\n", 255, 255, 8);
    printf("%c%c\n", 'o', 'k');
    printf("%f %.2f %e\n", 3.14159, 3.14159, 31415.9);
    printf("100%%\n");
}
```

```output
plain text
Ada is 36 years old
ff FF 10
ok
3.141590 3.14 3.141590e+04
100%
```

Widths and flags work as in C: a number is the minimum width, `-` aligns left, `0` pads with zeros,
`.n` is the precision.

```ktm run
void main() {
    printf("[%5d] [%-5d] [%05d]\n", 42, 42, 42);
    printf("[%8.3f] [%-8.1f]\n", 2.5, 2.5);
    printf("[%6s] [%-6s] [%.2s]\n", "ab", "ab", "abcdef");
}
```

```output
[   42] [42   ] [00042]
[   2.500] [2.5     ]
[    ab] [ab    ] [ab]
```

- `%d`, `%u`, `%x` and `%o` print all 64 bits. `%ld` works too, but isn't needed.
- `%s` takes a C string (`u8 *`), a slice (`u8[]`) or a `String`.
- The first argument is always a format: a literal `%` is written `%%`.

```ktm run
import str;

void main() {
    i64 big = 5000000000;
    printf("%d %ld\n", big, big);

    u8[] word = "slice";
    String owned = str.from("String");
    printf("%s, %s and %s\n", "C string", word, owned);
    owned.free();
}
```

```output
5000000000 5000000000
C string, slice and String
```

### ktm's own conversions

`%Q` prints a string escaped the way it would be written in source code. `%C` prints a character
in upper case.

```ktm run
void main() {
    printf("%Q\n", "say \"hi\"\n");
    printf("%C%c\n", 'h', 'i');
}
```

```output
say \"hi\"\n
Hi
```

### Counting the values

The compiler counts the conversions in a format and checks there is one value for each. A missing
value is an error:

```ktm error
void main() {
    printf("%d and %d\n", 1);
}
```

```output
printf format expects 2 arguments but 1 was supplied
```

So is one too many:

```ktm error
void main() {
    printf("%d\n", 1, 2);
}
```

```output
printf format expects 1 argument but 2 were supplied
```

The types of the values aren't checked yet: give `%d` an integer, `%f` a float and `%s` text.

## Printing to stderr

`os.eprintf` is `printf` to stderr, checked the same way.

```ktm run
import os;

void main() {
    printf("to stdout\n");
    os.eprintf("warning: %s\n", "disk almost full");
}
```

```output
to stdout
warning: disk almost full
```

## Ending the program

Returning from `main` ends the program with its exit code. `os.exit(code)` ends it from anywhere.

```ktm run
import os;

void check(i64 n) {
    if (n < 0) {
        printf("negative: %d\n", n);
        os.exit(2);
    }
    printf("ok: %d\n", n);
}

void main() {
    check(5);
    check(-1);
    printf("never printed\n");
}
```

```output
ok: 5
negative: -1
```

`os.exit` ends the program at once: the `defer` statements of the functions still running don't
run. Returning from `main` runs them. See [defer and ad](16-defer-and-ad.html).

## Scripts

ktm skips a first line that starts with `#!`, so a source file can run as a script:

```ktm
#!/usr/bin/env -S ktm -jit
void main() { printf("Hello from a script\n"); }
```

```sh
chmod +x hello.ktm
./hello.ktm
```

## What's next

[Types and values](02-types-and-values.html) covers the built-in types and how to write numbers,
characters and text.
