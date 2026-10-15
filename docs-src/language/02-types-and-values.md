# Types and values

The built-in types, their sizes and limits, how values convert from one type to another, and how to
write numbers, characters and text in source code.

## The built-in types

| Type | Size in bytes | What it is |
| --- | --- | --- |
| `i8` `i16` `i32` `i64` | 1 2 4 8 | Signed integers. `i64` is the default integer type |
| `u8` `u16` `u32` `u64` | 1 2 4 8 | Unsigned integers. `u8` is also the byte and character type |
| `f64` `f32` | 8 4 | Floating point numbers. `f64` is the default |
| `bool` | 1 | `true` (1) or `false` (0) |
| `void` | 0 | No value: what a function that returns nothing returns |
| `T *` | 8 | A pointer to a `T`. `null` is the null pointer |
| `T[]` | 16 | A slice: a pointer and a length |
| `String` | 24 | An owned, growable string |

`sizeof` gives the size of a type or of an expression's type, in bytes.

```ktm run
void main() {
    printf("%d %d %d %d\n", sizeof(i8), sizeof(i16), sizeof(i32), sizeof(i64));
    printf("%d %d %d\n", sizeof(f32), sizeof(f64), sizeof(bool));
    printf("%d %d %d\n", sizeof(u8 *), sizeof(u8[]), sizeof(String));
}
```

```output
1 2 4 8
4 8 1
8 16 24
```

## Integers

`i64` is what you use unless there is a reason not to: an integer literal is an `i64`, arithmetic
on smaller integers is done in `i64`, and `printf`'s `%d` prints all 64 bits.

```ktm run
void main() {
    i64 population = 8100000000;
    i32 small = 2000000000;
    u8 byte = 200;
    printf("%d %d %d\n", population, small, byte);
}
```

```output
8100000000 2000000000 200
```

### Limits

The prelude, which every program sees without an import, names the smallest and largest value of
each integer type.

```ktm run
void main() {
    printf("i8:  %d .. %d\n", I8_MIN, I8_MAX);
    printf("i32: %d .. %d\n", I32_MIN, I32_MAX);
    printf("i64: %d .. %d\n", I64_MIN, I64_MAX);
    printf("u8:  0 .. %d\n", U8_MAX);
    printf("u32: 0 .. %u\n", U32_MAX);
}
```

```output
i8:  -128 .. 127
i32: -2147483648 .. 2147483647
i64: -9223372036854775808 .. 9223372036854775807
u8:  0 .. 255
u32: 0 .. 4294967295
```

### Overflow wraps

Integer arithmetic wraps around at the type's size, as in C on every machine ktm runs on: there is
no trap and no undefined behaviour to worry about.

```ktm run
void main() {
    u8 b = 255;
    b = b + 1;
    i64 big = I64_MAX;
    big = big + 1;
    i8 small = 127;
    small = small + 1;
    printf("%d %d %d\n", b, big, small);
}
```

```output
0 -9223372036854775808 -128
```

### Division

Integer division rounds towards zero, and `%` takes the sign of the left side. Dividing by zero
stops the program with a crash report that says where it happened.

```ktm run
void main() {
    printf("%d %d\n", 7 / 2, -7 / 2);
    printf("%d %d\n", 7 % 3, -7 % 3);
}
```

```output
3 -3
1 -1
```

### Unsigned values

An unsigned type holds no sign. `%u` prints a value as unsigned; `%d` prints the same bits as
signed.

```ktm run
void main() {
    u64 all = U64_MAX;
    printf("%u\n", all);
    printf("%d\n", all);
}
```

```output
18446744073709551615
-1
```

Arithmetic on integers smaller than `i64` is done in `i64`, so `x - y` on two `u32` values below is
-2. It wraps to the `u32` range only when it is stored in a `u32`. This differs from C, where the
subtraction itself would wrap.

```ktm run
void main() {
    u32 x = 3;
    u32 y = 5;
    printf("%d\n", x - y);
    u32 diff = x - y;
    printf("%u\n", diff);
}
```

```output
-2
4294967294
```

## Floating point numbers

`f64` is a double, `f32` a single-precision float. A literal with a `.` or an exponent is an `f64`.

```ktm run
void main() {
    f64 third = 1.0 / 3.0;
    f32 small = 1.0 / 3.0;
    printf("%.17f\n", third);
    printf("%.17f\n", small);
    printf("%f %.1f %e\n", 2.5, 1e6, 0.000123);
}
```

```output
0.33333333333333331
0.33333334326744080
2.500000 1000000.0 1.230000e-04
```

Mixing an integer and a float gives a float. Dividing two integers stays an integer, so make one of
them a float when you want the fraction.

```ktm run
void main() {
    i64 total = 7;
    i64 count = 2;
    printf("%d\n", total / count);
    printf("%.1f\n", (f64)total / count);
    printf("%.1f\n", total / 2.0);
}
```

```output
3
3.5
3.5
```

Floats follow IEEE 754: dividing by zero gives an infinity, and a NaN compares unequal even to
itself.

```ktm run
void main() {
    f64 zero = 0.0;
    f64 inf = 1.0 / zero;
    f64 nan = zero / zero;
    printf("%f %f\n", inf, -inf);
    printf("%d %d\n", nan == nan, inf > 1e308);
}
```

```output
inf -inf
0 1
```

## bool

A `bool` is `true` (1) or `false` (0). Any value becomes a `bool` by being compared with 0, wherever
the conversion happens: an assignment, an argument, a return, a cast.

```ktm run
bool isSet(i64 flags, i64 bit) { return flags & bit; }

void main() {
    bool a = 0x100;          // true: the whole value counts, not its low byte
    bool b = (bool)0.5;      // true: not 0.0
    bool c = (bool)(-1);     // true
    u8 *p = null;
    bool d = p;              // false: a null pointer
    printf("%d %d %d %d %d\n", a, b, c, d, isSet(0x800, 0x800));
}
```

```output
1 1 1 0 1
```

Conditions (`if`, `while`, `&&`, `!`) take any number or pointer the same way: non-zero is true.

```ktm run
void main() {
    i64 count = 3;
    u8 *name = "ada";
    if (count && name) {
        printf("both set\n");
    }
    if (!0) {
        printf("!0 is true\n");
    }
}
```

```output
both set
!0 is true
```

## null, true and false

`null` is the pointer that points nowhere. It can be assigned to any pointer type, and a pointer
compares equal to it when it is unset.

```ktm run
void main() {
    u8 *name = null;
    if (name == null) {
        printf("no name yet\n");
    }
    name = "Ada";
    if (name) {
        printf("name: %s\n", name);
    }
    printf("%d %d\n", true, false);
}
```

```output
no name yet
name: Ada
1 0
```

## Converting between types

### Implicit conversions

A value converts to a wider type of the same kind without a cast: a smaller integer into a bigger
one, an integer into a float, an `f32` into an `f64`, any pointer into `void *`.

```ktm run
void main() {
    u8 b = 200;
    i64 wide = b;
    f64 f = wide;
    i32 neg = -5;
    i64 alsoNeg = neg;
    printf("%d %.1f %d\n", wide, f, alsoNeg);
}
```

```output
200 200.0 -5
```

A signed value keeps its sign when it gets wider (`-5` stays `-5`); an unsigned one is filled with
zeros.

### Casts

`(type)value` converts explicitly. Going to a narrower integer keeps the low bits; going from a float
to an integer drops the fraction.

```ktm run
void main() {
    i64 big = 300;
    u8 low = (u8)big;
    f64 price = 9.99;
    i64 whole = (i64)price;
    i64 negWhole = (i64)-2.7;
    printf("%d %d %d\n", low, whole, negWhole);
}
```

```output
44 9 -2
```

A cast between pointer types changes how the same memory is read:

```ktm run
void main() {
    u32 word = 0x04030201;
    u8 *bytes = (u8 *)&word;
    printf("%d %d %d %d\n", bytes[0], bytes[1], bytes[2], bytes[3]);
}
```

```output
1 2 3 4
```

The bytes come out lowest first because every machine ktm runs on is little-endian.

## Writing numbers

### Integer literals

Decimal, hexadecimal (`0x`) or binary (`0b`). An `_` between two digits groups them, for reading.

```ktm run
void main() {
    i64 dec = 42, hex = 0xFF, bin = 0b1010;
    i64 million = 1_000_000;
    i64 mask = 0xFF_FF;
    u32 flags = 0b1000_0001;
    printf("%d %d %d %d %d %d\n", dec, hex, bin, million, mask, flags);
}
```

```output
42 255 10 1000000 65535 129
```

- A literal too big for `i64` is a `u64`: `18446744073709551615` is `U64_MAX`.
- `_` only goes between two digits: `_100` is a name, `1__0` and `1_` are errors.
- There are no octal literals. A leading zero changes nothing: `0644` is six hundred and forty-four.
  ktm warns at such a literal and suggests writing it in hex.

A malformed number is an error that says what is wrong:

```ktm error
void main() {
    i64 x = 0b102;
}
```

```output
error:
```

### Float literals

```ktm run
void main() {
    f64 a = 1.5, b = 1.5e3, c = 2e-3, price = 1_000.25;
    printf("%.1f %.1f %.3f %.2f\n", a, b, c, price);
    printf("%s %s\n", typeof(2), typeof(2.0));
}
```

```output
1.5 1500.0 0.002 1000.25
i64 f64
```

## Characters

A character constant is one character in single quotes. Its value is the character's code, so it
can be used in arithmetic and compared.

```ktm run
void main() {
    i64 a = 'A', newline = '\n', zero = '0';
    printf("%d %d %d\n", a, newline, zero);
    printf("%c%c%c\n", a, a + 1, a + 2);
    u8 c = 'x';
    printf("%c is a lower-case letter: %d\n", c, c >= 'a' && c <= 'z');
    printf("'7' is the digit %d\n", '7' - '0');
}
```

```output
65 10 48
ABC
x is a lower-case letter: 1
'7' is the digit 7
```

A character constant holds exactly one character (an escape counts as one). Two characters in
quotes are an error: use a string, or an [enum](14-enums.html) for a set of tags.

```ktm error
void main() {
    i64 c = 'ab';
}
```

```output
error:
```

## Text

A string literal, in double quotes, is a `u8 *` to read-only bytes that end in a NUL (0). Wherever a
slice is expected, it converts to a `u8[]` of the same bytes.

```ktm run
void main() {
    u8 *greeting = "hello";
    u8[] view = "hello";
    printf("%s %s %d\n", greeting, view, view.len);
    printf("%d\n", sizeof("abc"));     // the three bytes and the NUL
}
```

```output
hello hello 5
4
```

Two literals next to each other are joined into one, which helps with long text:

```ktm run
void main() {
    u8 *msg = "a long sentence written "
              "over two lines";
    printf("%s\n", msg);
}
```

```output
a long sentence written over two lines
```

### Escapes

| Escape | Means |
| --- | --- |
| `\n` `\t` `\r` `\b` `\f` `\v` `\a` | The C control characters |
| `\\` `\'` `\"` | A backslash, a quote |
| `\xHH` | A byte in hex, at most two digits: `"\x01b"` is the byte 1, then `b` |
| `\0` to `\7` | An octal escape of up to three digits: `"\033"` is ESC |
| `\d` | A `$` |

```ktm run
void main() {
    printf("tab:[\t] quote:[\"] backslash:[\\]\n");
    printf("hex:[\x41\x42] octal:[\101] dollar:[\d]\n");
}
```

```output
tab:[	] quote:["] backslash:[\]
hex:[AB] octal:[A] dollar:[$]
```

A string literal can't be changed: writing into one is an error when the compiler sees it, and a
crash when it doesn't. Copy it into a buffer or a `String` first; [Strings](11-strings.html) shows
how.

## Asking about a type

`typeof(x)` is the name of an expression's type, as a string, made at compile time. `sizeof` and
`alignof` give a size and an alignment in bytes.

```ktm run
void main() {
    u8 small = 1;
    i64 n = 5;
    printf("%s %s %s\n", typeof(small), typeof(small + 1), typeof("hi"));
    printf("%s %s\n", typeof(n * 2.0), typeof(&n));
    printf("%d %d %d\n", sizeof(small), sizeof(n), alignof(f64));
}
```

```output
u8 i64 u8[3]
f64 i64 *
1 8 8
```

`small + 1` is an `i64` because arithmetic on integers smaller than `i64` is done in `i64`.
