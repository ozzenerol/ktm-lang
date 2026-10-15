# Operators

Arithmetic, bit operations, assignment, comparisons and logic, ktm's chained comparisons, `sizeof`
and `typeof`, casts, and the order operators apply in.

## Arithmetic

`+`, `-`, `*`, `/` and `%` work as in C. Integer division drops the fraction; with a float on either
side the result is a float.

```ktm run
void main() {
    i64 a = 7, b = 2;
    printf("%d %d %d %d %d\n", a + b, a - b, a * b, a / b, a % b);
    printf("%.2f %.2f\n", 7.0 / 2, a / 2.0);
}
```

```output
9 5 14 3 1
3.50 3.50
```

There is no exponent operator: use `math.power(x, y)`, which takes and returns an `f64`.

```ktm run
import math;

void main() {
    printf("%.0f %.3f\n", math.power(2, 10), math.power(2, 0.5));
}
```

```output
1024 1.414
```

### Unary minus and plus

Unary `-` and `+` take a number. A narrower integer is widened to `i64` first, so negating a `u8`
gives a negative number, not a wrapped byte.

```ktm run
void main() {
    u8 small = 200;
    i64 n = 5;
    printf("%d %d %d\n", -small, -n, +n);
}
```

```output
-200 -5 5
```

## Bit operations

`&` (and), `|` (or), `^` (exclusive or), `~` (not), `<<` and `>>` (shifts).

```ktm run
void main() {
    i64 a = 0b1100, b = 0b1010;
    printf("%d %d %d %d\n", a & b, a | b, a ^ b, ~a);
    printf("%d %d\n", 1 << 10, 1024 >> 3);
}
```

```output
8 14 6 -13
1024 128
```

Flags are the usual use: one bit per option, `|` to combine, `&` to test, `& ~` to clear.

```ktm run
const i64 READ = 1 << 0;
const i64 WRITE = 1 << 1;
const i64 EXEC = 1 << 2;

void show(i64 mode) {
    printf("%c%c%c\n", mode & READ ? 'r' : '-', mode & WRITE ? 'w' : '-', mode & EXEC ? 'x' : '-');
}

void main() {
    i64 mode = READ | WRITE;
    show(mode);
    mode |= EXEC;
    show(mode);
    mode &= ~WRITE;
    show(mode);
}
```

```output
rw-
rwx
r-x
```

`>>` on a signed value keeps the sign (an arithmetic shift); on an unsigned value it shifts in zeros.

```ktm run
void main() {
    i64 neg = -16;
    u64 big = 0xFFFFFFFFFFFFFFFF;
    printf("%d %u\n", neg >> 2, big >> 60);
}
```

```output
-4 15
```

`~` takes an integer only: `~` on an `f64` is an error.

## Assignment

`=` stores a value; `+=`, `-=`, `*=`, `/=`, `%=`, `&=`, `|=`, `^=`, `<<=` and `>>=` combine an
operation with it. `++` and `--` add or subtract one, before (`++n`) or after (`n++`) the value is
used.

```ktm run
void main() {
    i64 n = 10;
    n += 5;
    n *= 2;
    n -= 1;
    n /= 3;
    n %= 4;
    printf("%d\n", n);

    i64 i = 5;
    i64 before = i++;
    i64 after = ++i;
    printf("%d %d %d\n", before, after, i);
}
```

```output
1
5 7 7
```

An assignment is also an expression: its value is what was stored. That value has the type of the
left side, so it is cut to fit:

```ktm run
void main() {
    i64 a, b, c;
    a = b = c = 7;
    printf("%d %d %d\n", a, b, c);

    u8 byte;
    i64 v = (byte = 300);
    printf("%d\n", v);
}
```

```output
7 7 7
44
```

## Comparisons

`==`, `!=`, `<`, `<=`, `>` and `>=` give 1 or 0.

```ktm run
void main() {
    i64 a = 3, b = 5;
    printf("%d %d %d %d %d %d\n", a == b, a != b, a < b, a <= b, a > b, a >= b);
}
```

```output
0 1 1 1 0 0
```

On `u64` values, comparisons, `/`, `%` and `>>` are unsigned:

```ktm run
void main() {
    u64 big = 0xFFFFFFFFFFFFFFFF;
    i64 neg = -1;
    printf("%d %d\n", big > 1, neg > 1);
}
```

```output
1 0
```

### Chained comparisons

As in mathematics, `lo < x < hi` means `lo < x && x < hi`, and the middle value is computed once.

```ktm run
bool inRange(i64 x) { return 1 <= x <= 10; }

void main() {
    printf("%d %d %d\n", inRange(0), inRange(5), inRange(10));
    i64 a = 1, b = 2, c = 3;
    printf("%d %d\n", a < b < c, a < c < b);
}
```

```output
0 1 1
1 0
```

## Logic

`&&` (and), `||` (or) and `!` (not) take any number or pointer: zero and `null` are false, everything
else is true. They give 1 or 0.

```ktm run
void main() {
    f64 half = 0.5;
    u8 *none = null;
    printf("%d %d %d %d\n", half && 1, !half, none || 0, !none);
}
```

```output
1 0 0 1
```

`&&` and `||` stop as soon as the answer is known: the right side runs only when it is needed. That
makes "check, then use" safe in one expression.

```ktm run
bool loud(u8 *name, bool v) {
    printf("%s ", name);
    return v;
}

void main() {
    bool r1 = loud("a", false) && loud("b", true);
    printf("\n");
    bool r2 = loud("c", true) || loud("d", true);
    printf("\n%d %d\n", r1, r2);

    u8 *name = null;
    if (name && name[0] == 'x') {
        printf("starts with x\n");
    } else {
        printf("no name, and no crash\n");
    }
}
```

```output
a 
c 
0 1
no name, and no crash
```

## The conditional operator

`cond ? a : b` is `a` when `cond` is true, else `b`. Only the chosen side runs.

```ktm run
i64 abs(i64 n) { return n < 0 ? -n : n; }

void main() {
    i64 count = 1;
    printf("%d %d\n", abs(-4), abs(4));
    printf("%d item%s\n", count, count == 1 ? "" : "s");
    count = 3;
    printf("%d item%s\n", count, count == 1 ? "" : "s");
}
```

```output
4 4
1 item
3 items
```

## sizeof, alignof and typeof

`sizeof` is the size of a type or an expression, `alignof` its alignment, in bytes. `typeof` is the
name of an expression's type, as a string.

```ktm run
struct Pair { i32 a; i64 b; };

void main() {
    i64 arr[10];
    printf("%d %d\n", sizeof(arr), sizeof(arr) / sizeof(arr[0]));
    printf("%d %d\n", sizeof(Pair), alignof(Pair));
    printf("%s | %s | %s\n", typeof(1 + 1), typeof(&arr[0]), typeof(arr));
}
```

```output
80 10
16 8
i64 | i64 * | i64[10]
```

All three are worked out at compile time. Their operand never runs:

```ktm run
i64 noisy() {
    printf("called!\n");
    return 1;
}

void main() {
    printf("%s %d\n", typeof(noisy()), sizeof(noisy()));
}
```

```output
i64 8
```

## Casts

`(type)value` converts a value, as in C. A cast binds tighter than any binary operator: `(f64)a / b`
converts `a`, then divides.

```ktm run
void main() {
    f64 f = 3.9;
    i64 i = (i64)f;
    printf("%d\n", i);
    printf("%.1f %.1f\n", (f64)(7 / 2), (f64)7 / 2);
    printf("%.1f\n", (f64)-i / 2);

    u8 *s = "hey";
    i64 address = (i64)s;
    printf("%c\n", *(u8 *)address);
}
```

```output
3
3.0 3.5
-1.5
h
```

- A cast applies to the whole postfix expression after it: `(i64)p.n` casts `p.n`.
- Any type works, function types included: `(fn(i64) i64)ptr`.
- [Types and values](02-types-and-values.html#converting-between-types) covers what each conversion
  does to the value.

## Precedence

The same as C, from the tightest to the loosest:

| Operators | Kind |
| --- | --- |
| `a[i]` `f()` `a.b` `a->b` `x++` `x--` | Postfix |
| `-` `!` `~` `*p` `&x` `++x` `--x` `(T)x` `sizeof` | Unary |
| `*` `/` `%` | Multiplicative |
| `+` `-` | Additive |
| `<<` `>>` | Shifts |
| `<` `<=` `>` `>=` | Relational (they chain) |
| `==` `!=` | Equality |
| `&`, then `^`, then `|` | Bitwise |
| `&&`, then `||` | Logical |
| `?:` | Conditional |
| `=` `+=` `-=` ... | Assignment |

When in doubt, add parentheses: they cost nothing.

```ktm run
void main() {
    i64 a = 2, b = 3, c = 4;
    printf("%d %d\n", a + b * c, (a + b) * c);
    printf("%d %d\n", 1 << 2 + 1, (1 << 2) + 1);
    printf("%d %d\n", 6 & 3 == 3, (6 & 3) == 2);
}
```

```output
14 20
8 5
0 1
```

The middle line shows a classic trap: `+` binds tighter than `<<`. So does the last one: `==` binds
tighter than `&`.
