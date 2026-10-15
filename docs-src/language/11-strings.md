# Strings

ktm has three ways to hold text. A `String` is text you own: it grows and you free it. A `u8[]` is a
view of text someone else owns: what functions take to read text. A `u8 *` is a C string, for C
functions. This chapter covers all three and how they convert.

| Type | What it is | Use it for |
| --- | --- | --- |
| `String` | A pointer, a length and a capacity (24 bytes). Owned, growable, always ends in a NUL | Text you build or keep |
| `u8[]` | A pointer and a length (16 bytes). A view | Parameters, parts of text, literals |
| `u8 *` | A pointer to bytes ending in a NUL | C functions |

## Making a String

A `String` you get from the [str](../std/index.html#str) module is yours, to free when you are done.
`scoped` frees it at the end of the block.

```ktm run
import str;

void main() {
    scoped String s = str.from("Hello");
    s.append(", world");
    s.appendFormat(" %d", 2026);
    printf("%s (%d bytes)\n", s, s.len);
}
```

```output
Hello, world 2026 (17 bytes)
```

```ktm run
import str;

void main() {
    scoped String a = str.new();               // empty, nothing allocated yet
    scoped String b = str.from("copied");      // a copy of any text
    scoped String c = str.withCap(64);         // empty, with room for 64 bytes
    scoped String d = str.format("%d-%s", 42, "x");
    String e;                              // a declared String starts empty
    a.append("grown");
    printf("[%s] [%s] [%s] [%s] [%d]\n", a, b, c, d, e.len);
}
```

```output
[grown] [copied] [] [42-x] [0]
```

`str.format` takes `printf`'s format and returns the result as a new `String`:

```ktm run
import str;

void main() {
    scoped String line = str.format("%-6s|%5.1f|%03d", "id", 3.14159, 7);
    printf("[%s]\n", line);
}
```

```output
[id    |  3.1|007]
```

## Changing a String

```ktm run
import str;

void main() {
    scoped String s = str.from("found 3 files");
    s.insert(0, "> ");
    s.replace("files", "logs");
    s.append(" in /tmp");
    s.appendByte('!');
    printf("%s\n", s);
    s.remove(0, 2);
    s.toUpper();
    printf("%s\n", s);
    s.reverse();
    printf("%s\n", s);
    s.clear();
    printf("[%s] %d\n", s, s.len);
}
```

```output
> found 3 logs in /tmp!
FOUND 3 LOGS IN /TMP!
!PMT/ NI SGOL 3 DNUOF
[] 0
```

- `s.remove(a, b)` removes the bytes from `a` up to, not including, `b`.
- `s.replace(old, new)` replaces every `old`.
- The length and capacity fields can't be assigned: the methods keep them right.

Building text in a loop:

```ktm run
import str;

void main() {
    scoped String csv = str.new();
    i64 values[] = {3, 1, 4, 1, 5};
    for (i64 i = 0; i < 5; i++) {
        if (i > 0) { csv.appendByte(','); }
        csv.appendFormat("%d", values[i]);
    }
    printf("%s\n", csv);
}
```

```output
3,1,4,1,5
```

## Reading text

A `String` converts to a `u8[]`, and so does a literal. So every function that reads text takes
either.

```ktm run
import str;

void main() {
    scoped String s = str.from("Hello, world");
    u8[] hello = s[0:5];
    printf("%s %c %d\n", hello, s[7], s.len);
    printf("%d %d %d\n", s.startsWith("Hell"), s.find("world"), s.contains("xyz"));
    printf("%d %d\n", s.equals("Hello, world"), s.endsWith("!"));
}
```

```output
Hello w 12
1 7 0
1 0
```

The functions that read text are in [str](../std/index.html#str), and can be called as methods on a
`u8[]` or a `String`: `s.find("x")` is `str.find(s, "x")`.

```ktm run
import str;

void main() {
    u8[] line = "  name = ktm  ";
    u8[] t = str.trim(line);
    i64 eq = t.find("=");
    u8[] key = str.trim(t[0:eq]);
    u8[] value = str.trim(t[eq + 1:]);
    printf("[%s] [%s]\n", key, value);
}
```

```output
[name] [ktm]
```

### Splitting and joining

```ktm run
import str;

void main() {
    scoped u8[][] parts = str.split("red,green,,blue", ",");
    printf("%d parts:", parts.len);
    for (auto p : parts) {
        printf(" [%s]", p);
    }
    printf("\n");
    scoped String joined = str.join(parts, " + ");
    printf("%s\n", joined);
}
```

```output
4 parts: [red] [green] [] [blue]
red + green +  + blue
```

The parts are views into the text that was split: the list is yours to free, the parts are not.

### Numbers in text

```ktm run
import str;

void main() {
    i64 n;
    f64 f;
    if (str.toInt("42", &n)) {
        printf("%d\n", n + 1);
    }
    if (!str.toInt("42x", &n)) {
        printf("42x isn't an integer\n");
    }
    if (str.toFloat("2.5", &f)) {
        printf("%.2f\n", f * 2);
    }
    scoped String back = str.format("%d", 1234);
    printf("%s has %d digits\n", back, back.len);
}
```

```output
43
42x isn't an integer
5.00
1234 has 4 digits
```

## Copies share bytes

Assigning a `String` copies its three fields, so both variables point at the same bytes, and only one
of them may free them. `clone()` makes a copy with bytes of its own.

```ktm run
import str;

void main() {
    scoped String a = str.from("shared");
    String alias = a;              // the same bytes: don't free both
    scoped String own = a.clone();     // bytes of its own
    own.toUpper();
    printf("%s %s %s\n", a, alias, own);
}
```

```output
shared shared SHARED
```

- `free()` empties the `String` it is called on, so freeing the same variable twice is harmless.
- A view (`s[a:b]`) is valid until the `String` changes or is freed: growing may move its bytes.
- Freeing through two copies is a double free, which `-jit -Memsafe` reports.

## Bytes, not characters

`len` and `s[i]` count bytes. Text is UTF-8 by convention, so a character outside ASCII takes more
than one byte.

```ktm run
import str;

void main() {
    u8[] word = "naïve";
    printf("%d bytes, %d characters\n", word.len, str.runeCount(word));
}
```

```output
6 bytes, 5 characters
```

## Literals are read-only

A string literal's bytes can't be changed. A write the compiler can see is an error that says what to
do; one it can't see crashes at run time. Copy the text into a `String` first.

```ktm error
void main() {
    u8[] a = "abc";
    a[0] = 'x';
}
```

```output
read-only
```

```ktm run
import str;

void main() {
    scoped String a = str.from("abc");
    a[0] = 'x';
    printf("%s\n", a);
}
```

```output
xbc
```

A `u8` array initialised from a literal gets its own writable copy:

```ktm run
void main() {
    u8 buf[] = "abc";        // 4 bytes: a, b, c and the NUL
    u8 name[8] = "hi";       // the 6 bytes after "hi" are zero
    buf[0] = 'X';
    printf("%s %d %s %d\n", buf, sizeof(buf), name, sizeof(name));
}
```

```output
Xbc 4 hi 8
```

## C strings

A C string is a `u8 *` to bytes that end in a NUL: what C functions take and return. A `String`'s
bytes always end in a NUL, so a `String` variable goes where a `u8 *` is expected as it is, with no
copy. `cstr.view(p)` turns a C string into a `u8[]`.

```ktm run
import cstr, libc, str;

void main() {
    scoped String s = str.from("for C");
    u8 *c = s;
    u8[] back = cstr.view(c);
    printf("%s %d %d\n", c, back.len, libc.strlen(s));
}
```

```output
for C 5 5
```

- The pointer is the `String`'s own buffer: valid until the `String` changes or is freed.
- A `String` that a call returns doesn't convert (nothing would free it): keep it in a variable first.
- The [cstr](../std/index.html#cstr) module has the classic C string functions.

## printf and %s

`%s` takes a C string, a `u8[]` or a `String`. A width works (`%-10s`); a precision cuts the text.

```ktm run
import str;

void main() {
    scoped String s = str.from("ktm");
    u8[] part = "language"[0:4];
    printf("[%-6s][%6s][%s][%.2s]\n", s, part, "c string", "abcdef");
}
```

```output
[ktm   ][  lang][c string][ab]
```

## Which one to use

- Take text as a `u8[]` parameter: callers can pass a literal, a slice or a `String`.
- Return a `String` when you make new text, and say in the comment that the caller frees it.
- Keep a `String` in a struct for text the struct owns, and free it in the struct's `free()`.
- Use `u8 *` only to talk to C.
