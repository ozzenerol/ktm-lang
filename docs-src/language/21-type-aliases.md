# Type aliases

`type Name = T;` gives a type a second name. The alias is the type itself, not a new one: the
two are interchangeable everywhere. This chapter covers declaring aliases, naming long types
with them, what "the same type" means, aliases of arrays and generic instances, and aliases in
modules.

## Declaring an alias

```ktm run
type Celsius = f64;
type Count = i64;

i32 main() {
    Celsius t = 21.5;
    Count n = 3;
    printf("%.1f %d\n", t, n);
    return 0;
}
```

```output
21.5 3
```

The name can stand for any type: a slice, a pointer, a function pointer, a struct, an array, a
generic instance.

```ktm run
type Text = u8[];
type Name = u8 *;
type Handler = fn(i64) bool;

bool positive(i64 n) {
    return n > 0;
}

i32 main() {
    Text t = "hello";
    Name n = "ada";
    Handler h = &positive;
    printf("%d %s %d\n", t.len, n, h(5));
    return 0;
}
```

```output
5 ada 1
```

## Naming long types

Aliases make signatures read as what they mean, especially with function pointer types:

```ktm run
type Text = u8[];
type Handler = fn(i64, Text) bool;
type Celsius = f64;

struct Reading {
    Text place;
    Celsius temp;
};

type Readings = Reading[];

bool show(i64 i, Text place) {
    printf("%d: %.*s\n", i, place.len, place.ptr);
    return true;
}

Celsius warmest(Readings all, Handler each) {
    Celsius best = -273.15;
    for (i64 i = 0; i < all.len; i++) {
        each(i, all[i].place);
        if (all[i].temp > best) {
            best = all[i].temp;
        }
    }
    return best;
}

i32 main() {
    Reading r[3] = {{"Iasi", 18.5}, {"Cluj", 21.0}, {"Sibiu", 16.25}};
    printf("warmest %.1f\n", warmest(r, &show));
    return 0;
}
```

```output
0: Iasi
1: Cluj
2: Sibiu
warmest 21.0
```

## An alias is the same type

A `Celsius` and an `f64` pass to each other with no conversion; `sizeof`, `typeof` and error
messages see the real type:

```ktm run
type Celsius = f64;
type Text = u8[];

i32 main() {
    Celsius c = 21.5;
    f64 f = c;
    Text t = "hello";
    u8[] s = t;
    printf("%.1f %.*s %d %s\n", f, s.len, s.ptr, sizeof(Text), typeof(c));
    return 0;
}
```

```output
21.5 hello 16 f64
```

An alias of a struct is that struct: values of either name assign to each other, and the
struct's methods work through the alias.

```ktm run
struct Point { i64 x, y; };

i64 Point.sum() {
    return self.x + self.y;
}

type Pos = Point;

i32 main() {
    Point a = {1, 2};
    Pos b = a;
    printf("%d %d\n", b.y, b.sum());
    return 0;
}
```

```output
2 3
```

A method can be defined through the alias too: `i64 Pos.sum()` is the method `Point.sum`.

Because an alias is not a new type, it doesn't stop units from being mixed up:

```ktm run
type Celsius = f64;
type Fahrenheit = f64;

void heat(Celsius c) {
    printf("heating to %.1f C\n", c);
}

i32 main() {
    Fahrenheit f = 100.0;
    heat(f);
    return 0;
}
```

```output
heating to 100.0 C
```

For a type that the compiler keeps apart, wrap the value in a struct: two structs never
convert into each other (see [Structs and unions](12-structs-and-unions.html)).

```ktm error
struct Celsius { f64 v; };
struct Fahrenheit { f64 v; };

void heat(Celsius c) {
}

i32 main() {
    Fahrenheit f = {100.0};
    heat(f);
    return 0;
}
```

```output
expected 'Celsius' got 'Fahrenheit'
```

## Fixed arrays and generic instances

An alias of an array type includes the size, and declares arrays of it:

```ktm run
type Buf = u8[8];
type Matrix = f64[2][2];

i32 main() {
    Buf b;
    b[0] = 'k';
    Matrix m = {{1.0, 2.0}, {3.0, 4.0}};
    printf("%d %c %d %.1f\n", sizeof(Buf), b[0], sizeof(Matrix), m[1][0]);
    return 0;
}
```

```output
8 k 32 3.0
```

An alias names one instance of a generic type, with its arguments written out:

```ktm run
import vec, map;

type Ints = vec.Vec<i64>;
type Ages = map.Map<u8 *, i64>;

i32 main() {
    scoped Ints nums = vec.new<i64>();
    nums.push(3);
    nums.push(4);
    scoped Ages ages = map.new<u8 *, i64>();
    ages.set("ann", 41);
    printf("%d %d %s\n", nums.len, ages.getOr("ann", 0), typeof(nums));
    return 0;
}
```

```output
2 41 vec.Vec<i64>
```

An alias can't take type parameters of its own: `type ByName<V> = map.Map<u8 *, V>;` is not
ktm. Name each instance instead.

## Where aliases go

Aliases are declared at the top level of a file. In a program an alias can only name types
declared above it, and is only usable below it:

```ktm error
i32 main() {
    Later x = 1;
    return 0;
}

type Later = i64;
```

```output
Type `Later` has not been declared
```

Two aliases that name each other are an error.

In a module, any order works, and an alias is private unless it is `pub`; outside the module it
is `module.Name` (see [Modules and packages](22-modules-and-packages.html)):

```ktm
module units;

pub type Meters = f64;      // units.Meters for the module's importers
type Scratch = u8[64];      // only this module sees it
```

`type` is a keyword only at the start of such a declaration: a variable or a field can still be
called `type`.

```ktm run
type Id = i64;

struct Token {
    u8 *type;
    Id id;
};

i32 main() {
    Token t = {"word", 7};
    printf("%s %d\n", t.type, t.id);
    return 0;
}
```

```output
word 7
```

## Rules

- `type Name = T;` makes `Name` another name for `T`; both are the same type everywhere.
- `T` can be any type: numbers, pointers, slices, fixed arrays, function pointers, structs,
  generic instances with their arguments.
- Aliases have no type parameters of their own.
- They are declared at the top level; in a program before their use, in a module in any order.
- In a module an alias is private unless `pub`, and is `module.Name` outside.
- For a distinct type the compiler keeps apart, use a struct.
