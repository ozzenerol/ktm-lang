# Enums

An enum is a set of named integer constants of its own type. Its members are always written
with the enum's name in front, `Color.red`, so two enums can have members of the same name.
This chapter covers declaring enums, their values and underlying type, using members as
constants, flags, conversions to and from integers, switching on an enum, names at run time,
and enums as error codes.

## Declaring an enum

`enum Name { members };` declares the type `Name` and its members. A member is used as
`Name.member`:

```ktm run
enum Color { red, green, blue };

i32 main() {
    Color c = Color.green;
    printf("%d %d %d\n", Color.red, c, Color.blue);
    return 0;
}
```

```output
0 1 2
```

The bare member name isn't in scope outside the enum:

```ktm error
enum Color { red, green, blue };

i32 main() {
    printf("%d\n", green);
    return 0;
}
```

```output
`green` has not been defined
```

So members of different enums never collide:

```ktm run
enum Light { off, on };
enum Fan { off, low, high };

i32 main() {
    printf("%d %d\n", Light.on, Fan.high);
    return 0;
}
```

```output
1 2
```

- Enums are declared at the top level of a file.
- A variable of an enum type, like any variable, starts at zero, which is the first member
  when it has no value of its own.

## Values

The first member is 0 and each next one is the previous plus 1, unless it is given a value:

```ktm run
enum Status { ok = 200, created, accepted, notFound = 404, gone };

i32 main() {
    printf("%d %d %d\n", Status.ok, Status.created, Status.accepted);
    printf("%d %d\n", Status.notFound, Status.gone);
    return 0;
}
```

```output
200 201 202
404 405
```

Values can be negative, and a member's value can use earlier members without the prefix:

```ktm run
enum Temp { freezing = -10, cold = 0, warm = 20, hot };
enum Size { small = 1, medium = small * 4, large = medium * 4 };

i32 main() {
    printf("%d %d %d %d\n", Temp.freezing, Temp.cold, Temp.warm, Temp.hot);
    printf("%d %d %d\n", Size.small, Size.medium, Size.large);
    return 0;
}
```

```output
-10 0 20 21
1 4 16
```

### The underlying type

An enum is stored as an `i32` unless `: Type` after its name picks another integer type:

```ktm run
enum Color { red, green, blue };
enum Status : u16 { ok = 200, notFound = 404 };
enum Big : i64 { huge = 5000000000 };

i32 main() {
    printf("%d %d %d\n", sizeof(Color), sizeof(Status), sizeof(Big));
    printf("%d\n", Big.huge);
    return 0;
}
```

```output
4 2 8
5000000000
```

A member that doesn't fit the type is an error:

```ktm error
enum Tiny : u8 { a, b = 255, c };

i32 main() {
    return 0;
}
```

```output
value 256 does not fit in `u8`
```

## Members are constants

`Name.member` is a constant expression, so it can size an array, initialise a `const` or a
global, and label a `case`:

```ktm run
enum Color { red, green, blue, count };

u8 *names[Color.count] = {"red", "green", "blue"};
const i64 LAST = Color.blue;

i32 main() {
    printf("%d names, last %d\n", sizeof(names) / sizeof(u8 *), LAST);
    printf("%s\n", names[Color.green]);
    return 0;
}
```

```output
3 names, last 2
green
```

A last member like `count` above is a common way to know how many members there are, as long
as the others have the default values 0, 1, 2 ...

## Switching on an enum

A `switch` with one `case` per member reads naturally; a case doesn't fall through into the
next (see [switch](06-switch.html)):

```ktm run
enum Color { red, green, blue };

u8 *mood(Color c) {
    switch (c) {
        case Color.red:   return "warm";
        case Color.green: return "calm";
        case Color.blue:  return "cool";
    }
    return "?";
}

i32 main() {
    printf("%s %s %s\n", mood(Color.red), mood(Color.green), mood(Color.blue));
    return 0;
}
```

```output
warm calm cool
```

## Conversions

An enum value converts to an integer by itself, in assignments, arithmetic and comparisons:

```ktm run
enum Color { red, green, blue };

i32 main() {
    Color c = Color.blue;
    i64 n = c;
    printf("%d %d %d\n", n, c * 10, c > Color.red);
    return 0;
}
```

```output
2 20 1
```

The other way needs a cast: an integer isn't an enum value until it is said to be one.

```ktm error
enum Color { red, green, blue };

i32 main() {
    Color c = 1;
    return 0;
}
```

```output
Cannot convert `i64` to enum `Color` without a cast
```

```ktm run
enum Color { red, green, blue };

Color next(Color c) {
    return (Color)((c + 1) % 3);
}

i32 main() {
    Color c = (Color)2;
    printf("%d %d\n", c, next(c));
    return 0;
}
```

```output
2 0
```

The cast doesn't check the number: `(Color)7` is a `Color` with no member of that value.

Arithmetic assignment (`+=`, `++`) on an enum variable is refused for the same reason; cast,
compute, and cast back:

```ktm error
enum Color { red, green, blue };

i32 main() {
    Color c = Color.red;
    c++;
    return 0;
}
```

```output
Cannot do arithmetic assignment on enum `Color`
```

```ktm run
enum Color { red, green, blue };

i32 main() {
    for (Color c = Color.red; c <= Color.blue; c = (Color)(c + 1)) {
        printf("%d ", c);
    }
    printf("\n");
    return 0;
}
```

```output
0 1 2 
```

Two different enums don't mix: one doesn't convert to the other, and comparing them is an
error.

```ktm error
enum Color { red, green };
enum Size { small, large };

i32 main() {
    Color c = Size.large;
    return 0;
}
```

```output
Cannot convert `Size` to enum `Color` without a cast
```

```ktm error
enum Color { red, green };
enum Size { small, large };

i32 main() {
    return Color.green == Size.large;
}
```

```output
Cannot compare enum `Color` with enum `Size`
```

## Flags

Members whose values are powers of two can be combined with `|` and tested with `&`. The
combination is an integer, not a member:

```ktm run
enum Perm { read = 1, write = 2, exec = 4, all = read | write | exec };

i32 main() {
    i64 p = Perm.read | Perm.write;
    printf("can write: %d\n", (p & Perm.write) != 0);
    printf("can exec: %d\n", (p & Perm.exec) != 0);
    printf("all: %d\n", Perm.all);
    p &= ~Perm.write;
    printf("after removing write: %d\n", p);
    return 0;
}
```

```output
can write: 1
can exec: 0
all: 7
after removing write: 1
```

## Enums in structs

An enum field takes the enum's size; a struct initialiser list takes a member for it:

```ktm run
enum Suit { hearts, spades };

struct Card {
    Suit suit;
    i64 rank;
};

i32 main() {
    Card c = {Suit.spades, 12};
    printf("%d %d, %d bytes\n", c.suit, c.rank, sizeof(Card));
    return 0;
}
```

```output
1 12, 16 bytes
```

An enum can't have methods of its own; a function that takes it does the same job:

```ktm error
enum Color { red, green };

void Color.print() {
}

i32 main() {
    return 0;
}
```

```output
only a struct or union can have methods
```

## Names at run time

`printf` prints an enum's number. `reflect.name` gives the member's name, and
`reflect.enumValue` finds the value of a name (see [Reflection](24-reflection.html)):

```ktm run
import reflect;

enum Role { guest, member, admin = 10 };

i32 main() {
    Role r = Role.admin;
    printf("%d %s\n", r, reflect.name(r));
    i64 v;
    if (reflect.enumValue(reflect.typeOf(Role), "member", &v)) {
        printf("member is %d\n", v);
    }
    printf("[%s]\n", reflect.name((Role)7));
    return 0;
}
```

```output
10 admin
member is 1
[]
```

A value that isn't a member has the empty name. JSON and YAML write an enum field as its
member's name and read it back the same way (see [Attributes](23-attributes.html)).

## Enums as error codes

An enum is the usual code of an error (see [Errors](15-errors.html)):

```ktm run
enum ParseError { empty, notNumber };

i64 parse(u8[] s) {
    if (!s.len) {
        throw(ParseError.empty, "empty input");
    }
    i64 n = 0;
    for (i64 i = 0; i < s.len; i++) {
        u8 c = s[i];
        if (c < '0' || c > '9') {
            throw(ParseError.notNumber, "bad digit '%c'", c);
        }
        n = n * 10 + c - '0';
    }
    return n;
}

i32 main() {
    u8[] inputs[3] = {"42", "", "4x"};
    for (i64 i = 0; i < 3; i++) {
        try {
            printf("%d\n", parse(inputs[i]));
        } catch (Error e) {
            printf("error %d: %s\n", e.code, e.message);
        }
    }
    return 0;
}
```

```output
42
error 0: empty input
error 1: bad digit 'x'
```

## Rules

- `enum Name { a, b = 5, c };` declares a type and its members, at the top level of a file.
- Members count up from 0, or from the last given value; a value may use earlier members.
- The underlying type is `i32`; `enum Name : u16 { ... }` picks another integer type, and a
  member that doesn't fit it is an error.
- `Name.member` is a constant expression; the bare member name is not in scope outside.
- An enum converts to an integer by itself; an integer or another enum needs a cast to become
  one, and a cast doesn't check the value.
- `++`, `+=` and the like don't apply to enum variables; two enums can't be compared.
- An enum has no methods. `reflect.name` and `reflect.enumValue` map members and names.
