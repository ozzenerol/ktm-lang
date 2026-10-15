# switch

`switch` picks one of several branches by a value. It looks like C's, with four differences: cases
don't fall through into each other, a case can be a range, you can switch on text, and an explicit
`fallthrough` is there for the rare case you want it.

## The basics

The value is compared with each `case`; the matching case runs. `default` runs when none matches.
A case ends where the next one starts, so no `break` is needed.

```ktm run
void describe(i64 n) {
    switch (n) {
        case 0: printf("%d: zero\n", n);
        case 1: printf("%d: one\n", n);
        default: printf("%d: many\n", n);
    }
}

void main() {
    describe(0);
    describe(1);
    describe(7);
}
```

```output
0: zero
1: one
7: many
```

In C, `describe(0)` would print all three lines. In ktm each case stands alone.

Without a `default`, a value that matches no case does nothing:

```ktm run
void main() {
    for (i64 i = 0; i < 4; i++) {
        switch (i) {
            case 1: printf("one ");
            case 3: printf("three ");
        }
    }
    printf("\n");
}
```

```output
one three 
```

The parentheses are optional, as for `if`:

```ktm run
void main() {
    i64 n = 2;
    switch n {
        case 2: printf("two\n");
        default: printf("other\n");
    }
}
```

```output
two
```

## break

`break` leaves the switch early, from the middle of a case:

```ktm run
void check(i64 n) {
    switch (n) {
        default:
            if (n < 0) {
                printf("%d: negative, stop\n", n);
                break;
            }
            printf("%d: fine\n", n);
    }
}

void main() {
    check(-3);
    check(4);
}
```

```output
-3: negative, stop
4: fine
```

Inside a loop, `break` in a switch leaves the switch, not the loop; `continue` goes to the loop's
next pass.

```ktm run
void main() {
    for (i64 i = 0; i < 5; i++) {
        switch (i) {
            case 1: continue;
            case 3: break;
            default: printf("%d ", i);
        }
        printf("| ");
    }
    printf("\n");
}
```

```output
0 | 2 | | 4 | 
```

## Several values, one body

Labels stacked with nothing between them share the code after them.

```ktm run
u8 *dayType(i64 day) {
    switch (day) {
        case 0:
        case 6:
            return "weekend";
        default:
            return "weekday";
    }
}

void main() {
    printf("%s %s %s\n", dayType(0), dayType(3), dayType(6));
}
```

```output
weekend weekday weekend
```

## Ranges

`case lo ... hi:` matches every value from `lo` to `hi`, both included.

```ktm run
void grade(i64 score) {
    switch (score) {
        case 90 ... 100: printf("%d: A\n", score);
        case 80 ... 89:  printf("%d: B\n", score);
        case 0:          printf("%d: no score\n", score);
        default:         printf("%d: C or below\n", score);
    }
}

void main() {
    grade(95);
    grade(83);
    grade(0);
    grade(42);
}
```

```output
95: A
83: B
0: no score
42: C or below
```

Ranges work on characters too, since a character is a number:

```ktm run
u8 *kind(u8 c) {
    switch (c) {
        case 'a' ... 'z': return "lower";
        case 'A' ... 'Z': return "upper";
        case '0' ... '9': return "digit";
        default:          return "other";
    }
}

void main() {
    printf("%s %s %s %s\n", kind('q'), kind('Q'), kind('7'), kind('!'));
}
```

```output
lower upper digit other
```

Two cases may not cover the same value:

```ktm error
void main() {
    i64 n = 5;
    switch (n) {
        case 1 ... 10: printf("low\n");
        case 5: printf("five\n");
    }
}
```

```output
error:
```

## fallthrough

`fallthrough;` as the last statement of a case runs on into the next case.

```ktm run
void main() {
    for (i64 i = 1; i <= 3; i++) {
        printf("%d:", i);
        switch (i) {
            case 1:
                printf(" one");
                fallthrough;
            case 2:
                printf(" one-or-two");
            case 3:
                printf(" three");
        }
        printf("\n");
    }
}
```

```output
1: one one-or-two
2: one-or-two
3: three
```

`fallthrough` is an error anywhere else:

- outside a switch, or before other statements of its case;
- inside an `if` or a loop in the case;
- in the last case, which has no next case.

```ktm error
void main() {
    i64 n = 1;
    switch (n) {
        case 1:
            fallthrough;
            printf("never\n");
        case 2:
            printf("two\n");
    }
}
```

```output
error:
```

## Each case is a block

A case can declare its own variables without braces, and two cases can use the same name.

```ktm run
void main() {
    for (i64 i = 0; i < 2; i++) {
        switch (i) {
            case 0:
                i64 n = 10;
                printf("case 0: n = %d\n", n);
            case 1:
                i64 n = 20;
                printf("case 1: n = %d\n", n);
        }
    }
}
```

```output
case 0: n = 10
case 1: n = 20
```

A `defer` in a case runs at the end of that case. It needs braces: `case 1: { defer ...; }`.

## Switching on enums

The cases of an [enum](14-enums.html) are its members.

```ktm run
enum Color { red, green, blue };

u8 *hex(Color c) {
    switch (c) {
        case Color.red:   return "#f00";
        case Color.green: return "#0f0";
        case Color.blue:  return "#00f";
    }
    return "?";
}

void main() {
    printf("%s %s %s\n", hex(Color.red), hex(Color.green), hex(Color.blue));
}
```

```output
#f00 #0f0 #00f
```

## Switching on text

A `u8[]` or a `String` can be switched on, with string literals as the cases. A case matches when
the value has exactly its bytes: the same length, the same case, no trimming.

```ktm run
i64 run(u8[] cmd) {
    switch (cmd) {
        case "GET":    return 1;
        case "SET":    return 2;
        case "DEL":
        case "REMOVE": return 3;
        default:       return 0;
    }
}

void main() {
    u8[] line = "SET name ktm";
    printf("%d %d %d %d\n", run("GET"), run(line[0:3]), run("REMOVE"), run("get"));
}
```

```output
1 2 3 0
```

- Every case is a string literal: no ranges and no integers.
- The same string twice is an error.
- The value is computed once, so `switch (readCommand())` calls the function once.
- `default`, stacked labels and `fallthrough` work as in a switch on numbers.

For a match that ignores case, switch on a lower-case copy:

```ktm run
import str;

i64 code(u8[] word) {
    String low = str.from(word);
    low.toLower();
    i64 result = 0;
    switch (low) {
        case "yes": result = 1;
        case "no":  result = 2;
    }
    low.free();
    return result;
}

void main() {
    printf("%d %d %d\n", code("YES"), code("No"), code("maybe"));
}
```

```output
1 2 0
```

## Two extras

### Implicit case values

A `case:` with no value is the previous case's value plus one, starting from 0.

```ktm run
void main() {
    for (i64 i = 0; i < 4; i++) {
        switch (i) {
            case: printf("zero ");
            case: printf("one ");
            case: printf("two ");
            default: printf("more");
        }
    }
    printf("\n");
}
```

```output
zero one two more
```

### switch [x]: no range check

`switch [x]` leaves out the check that `x` is within the cases' range, for a jump table without the
test. Use it only when `x` always matches a case: anything else is undefined. A switch on text
always checks.

```ktm run
void main() {
    for (i64 i = 0; i < 3; i++) {
        switch [i] {
            case 0: printf("a");
            case 1: printf("b");
            case 2: printf("c\n");
        }
    }
}
```

```output
abc
```
