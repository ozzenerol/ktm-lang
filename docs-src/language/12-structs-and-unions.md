# Structs and unions

A struct groups several values under one name; a union lets several fields share the same
memory. This chapter covers declaring structs, initialising them, how they are copied, how
they nest, how they are laid out in memory, declaring them ahead, and unions, named and
anonymous. Methods on structs have their own chapter, [Methods](13-methods.html).

## Declaring a struct

`struct Name { fields };` declares a type called `Name`. The name is a type straight away:
there is no `typedef` to write and no `struct` keyword in front of it later.

```ktm run
struct Point {
    i64 x;
    i64 y;
};

i32 main() {
    Point p;
    p.x = 3;
    p.y = 4;
    printf("(%d, %d)\n", p.x, p.y);
    return 0;
}
```

```output
(3, 4)
```

Fields of one type can share a line, separated by commas:

```ktm run
struct Rect {
    i64 left, top, width, height;
};

i32 main() {
    Rect r = {10, 20, 300, 200};
    printf("%d x %d at (%d, %d)\n", r.width, r.height, r.left, r.top);
    return 0;
}
```

```output
300 x 200 at (10, 20)
```

A field can be of any type: numbers, pointers, arrays, slices, `String`, other structs.

```ktm run
struct Book {
    u8 *title;
    i64 year;
    f64 price;
    bool inPrint;
};

i32 main() {
    Book b = {"Dune", 1965, 9.99, true};
    printf("%s (%d), %.2f, %d\n", b.title, b.year, b.price, b.inPrint);
    return 0;
}
```

```output
Dune (1965), 9.99, 1
```

- Structs are declared at the top level of a file, not inside a function.
- The `;` after the closing brace is required.

## Initialising

A list in braces fills the fields in order. Fields the list leaves out are zero.

```ktm run
struct Point { i64 x, y; };

i32 main() {
    Point a = {7, 8};
    Point b = {7};
    printf("%d %d | %d %d\n", a.x, a.y, b.x, b.y);
    return 0;
}
```

```output
7 8 | 7 0
```

A struct declared without a value starts with every field zero, like `{0}`:

```ktm run
struct Counter { i64 hits; i64 misses; u8 *label; };

i32 main() {
    Counter c;
    printf("%d %d %d\n", c.hits, c.misses, c.label == null);
    return 0;
}
```

```output
0 0 1
```

More values than fields is an error:

```ktm error
struct Point { i64 x, y; };

i32 main() {
    Point p = {1, 2, 3};
    return 0;
}
```

```output
More initialisers than class fields
```

An initialiser list is only for a declaration. To set every field of an existing struct,
assign the fields one by one, or copy in another struct:

```ktm run
struct Point { i64 x, y; };

i32 main() {
    Point p = {1, 2};
    Point origin = {0, 0};
    p = origin;
    printf("%d %d\n", p.x, p.y);
    return 0;
}
```

```output
0 0
```

### Nested lists

A field that is a struct or an array takes a nested list:

```ktm run
struct Point { i64 x, y; };
struct Line {
    Point from, to;
    i64 tags[2];
};

i32 main() {
    Line l = {{0, 0}, {3, 4}, {7, 8}};
    Line m = {{1, 2}, {5, 6}};
    printf("%d %d %d\n", l.to.x, l.to.y, l.tags[1]);
    printf("%d %d %d\n", m.from.y, m.to.x, m.tags[0]);
    return 0;
}
```

```output
3 4 8
2 5 0
```

An array of structs takes one list per element; elements left out are zero:

```ktm run
struct Point { i64 x, y; };

i32 main() {
    Point ps[3] = {{1, 2}, {3, 4}};
    for (i64 i = 0; i < 3; i++) {
        printf("(%d, %d) ", ps[i].x, ps[i].y);
    }
    printf("\n");
    return 0;
}
```

```output
(1, 2) (3, 4) (0, 0) 
```

### Strings in fields

A string literal given to a `u8` array field is copied into the struct, so the struct has
its own writable copy; it must fit with its terminating zero. A `u8 *` field only points at
the literal, which must not be written to.

```ktm run
struct Item {
    i64 id;
    u8 name[8];
    u8 *note;
};

i32 main() {
    Item it = {1, "pen", "blue ink"};
    it.name[0] = 'P';
    printf("%s, %s, %d bytes\n", it.name, it.note, sizeof(Item));
    return 0;
}
```

```output
Pen, blue ink, 24 bytes
```

A `String` field owns its text: give it one with `str.from` and free it when the struct is
done with (see [Strings](11-strings.html)).

```ktm run
import str;

struct User {
    String name;
    i64 age;
};

i32 main() {
    User u;
    u.name = str.from("ada");
    u.age = 36;
    u.name.append(" lovelace");
    printf("%.*s, %d\n", u.name.len, u.name.ptr, u.age);
    u.name.free();
    return 0;
}
```

```output
ada lovelace, 36
```

## Values are copied

A struct is a value. Assigning it, passing it to a function and returning it all copy every
field; the copy and the original are independent afterwards.

```ktm run
struct Point { i64 x, y; };

i32 main() {
    Point a = {1, 2};
    Point b = a;
    b.x = 9;
    printf("a = (%d, %d), b = (%d, %d)\n", a.x, a.y, b.x, b.y);
    return 0;
}
```

```output
a = (1, 2), b = (9, 2)
```

A function gets its own copy of a struct argument, so changing it doesn't touch the caller's:

```ktm run
struct Point { i64 x, y; };

i64 sumAfterReset(Point p) {
    p.x = 1000;
    return p.x + p.y;
}

i32 main() {
    Point a = {1, 2};
    printf("%d\n", sumAfterReset(a));
    printf("%d\n", a.x);
    return 0;
}
```

```output
1002
1
```

A function can return a struct, and the result can be used straight away:

```ktm run
struct Point { i64 x, y; };

Point make(i64 x, i64 y) {
    Point r = {x, y};
    return r;
}

i32 main() {
    Point p = make(3, 4);
    printf("%d %d %d\n", p.x, p.y, make(5, 6).y);
    return 0;
}
```

```output
3 4 6
```

An array field is part of the struct, so it is copied too. A pointer field is copied as a
pointer: both copies point at the same thing.

```ktm run
struct Box {
    i64 nums[3];
    i64 *shared;
};

i32 main() {
    i64 n = 5;
    Box a = {{1, 2, 3}, &n};
    Box b = a;
    b.nums[0] = 100;
    *b.shared = 50;
    printf("%d %d %d\n", a.nums[0], b.nums[0], *a.shared);
    return 0;
}
```

```output
1 100 50
```

Copying a struct that holds a `String` copies the `String` (its pointer, length and
capacity), not the text: both structs then hold the same text, and only one may free it.

## Pointers to structs

To let a function change the caller's struct, pass its address. `.` reaches a field through
a pointer too, so `p.x` works whether `p` is a struct or a pointer to one.

```ktm run
struct Counter { i64 n; };

void bump(Counter *c) {
    c.n++;
}

i32 main() {
    Counter c = {0};
    bump(&c);
    bump(&c);
    printf("%d\n", c.n);
    return 0;
}
```

```output
2
```

C's `->` is accepted as well and means the same:

```ktm run
struct Point { i64 x, y; };

i32 main() {
    Point a = {1, 2};
    Point *p = &a;
    p->x = 7;
    p.y = 8;
    printf("%d %d\n", a.x, a.y);
    return 0;
}
```

```output
7 8
```

`*p` is the struct itself; assigning it to a variable copies it:

```ktm run
struct Point { i64 x, y; };

i32 main() {
    Point a = {1, 2};
    Point *p = &a;
    Point c = *p;
    c.x = 9;
    printf("%d %d\n", a.x, c.x);
    return 0;
}
```

```output
1 9
```

Large structs are cheaper to pass by pointer: a 1 KiB struct passed by value is 1 KiB copied
on every call.

## Comparing structs

`==` and `!=` don't apply to structs. Compare the fields that matter:

```ktm error
struct Point { i64 x, y; };

i32 main() {
    Point a = {1, 2};
    Point b = {1, 2};
    return a == b;
}
```

```output
`Point == Point` is invalid
```

```ktm run
struct Point { i64 x, y; };

bool samePoint(Point a, Point b) {
    return a.x == b.x && a.y == b.y;
}

i32 main() {
    Point a = {1, 2};
    Point b = {1, 2};
    Point c = {2, 1};
    printf("%d %d\n", samePoint(a, b), samePoint(a, c));
    return 0;
}
```

```output
1 0
```

Two structs of different types don't convert into each other, even with the same fields:

```ktm error
struct Celsius { f64 v; };
struct Fahrenheit { f64 v; };

i32 main() {
    Celsius c = {20.0};
    Fahrenheit f = c;
    return 0;
}
```

```output
`Fahrenheit = Celsius` is invalid
```

## Nesting

A struct can hold another struct by value. The inner fields are reached with one more `.`:

```ktm run
struct Point { i64 x, y; };
struct Player {
    u8 *name;
    Point pos;
    i64 score;
};

i32 main() {
    Player p = {"ada", {3, 4}, 0};
    p.score += 10;
    p.pos.x = 0;
    printf("%s at (%d, %d) with %d\n", p.name, p.pos.x, p.pos.y, p.score);
    return 0;
}
```

```output
ada at (0, 4) with 10
```

A struct can't hold itself by value (it would be infinitely large), but it can hold a pointer
to its own type, which is how lists and trees are built:

```ktm run
struct Node {
    i64 value;
    Node *next;
};

i32 main() {
    Node c = {3, null};
    Node b = {2, &c};
    Node a = {1, &b};
    for (Node *n = &a; n; n = n.next) {
        printf("%d ", n.value);
    }
    printf("\n");
    return 0;
}
```

```output
1 2 3 
```

### An anonymous struct inside a struct

A `struct { ... };` without a name inside a struct adds its fields to the outer struct, as
if they were written there. It is mostly useful inside unions (below).

```ktm run
struct Sample {
    i64 id;
    struct {
        i64 low;
        i64 high;
    };
};

i32 main() {
    Sample s = {1, 20, 30};
    printf("%d %d %d, %d bytes\n", s.id, s.low, s.high, sizeof(Sample));
    return 0;
}
```

```output
1 20 30, 24 bytes
```

## Size and layout

Fields are laid out in the order they are written, each at an offset that is a multiple of
its own size (its alignment), as C does on the same machine. The struct's size is rounded up
to its largest alignment, so arrays of it stay aligned. `sizeof` and `alignof` give both.

```ktm run
struct A { u8 tag; i64 value; };
struct B { i64 value; u8 tag; };
struct C { i64 value; u8 tag; u8 flags; u16 port; };

i32 main() {
    printf("%d %d %d\n", sizeof(A), sizeof(B), sizeof(C));
    printf("%d %d\n", alignof(A), alignof(C));
    return 0;
}
```

```output
16 16 16
8 8
```

`A` has 7 bytes of padding after `tag`; `B` has 7 at its end. `C` fits its three small fields
into the 8 bytes after `value`, so ordering fields from largest to smallest keeps structs
small.

```ktm run
struct Loose { u8 a; i64 b; u8 c; i64 d; };
struct Tight { i64 b; i64 d; u8 a; u8 c; };

i32 main() {
    printf("%d %d\n", sizeof(Loose), sizeof(Tight));
    return 0;
}
```

```output
32 24
```

A struct of only small fields is aligned to its largest one:

```ktm run
struct Rgb { u8 r, g, b; };
struct Pair { u8 a; u16 b; u32 c; u8 d; };

i32 main() {
    printf("%d %d | %d %d\n", sizeof(Rgb), alignof(Rgb), sizeof(Pair), alignof(Pair));
    return 0;
}
```

```output
3 1 | 12 4
```

The same layout as C means a ktm struct can be handed to a C function that expects the
matching C struct (see [Calling C](26-calling-c.html)). The offsets of the fields are
available at run time through [reflection](24-reflection.html).

## Declaring ahead

`struct Name;` declares a struct that is defined further down. Until its definition, it can
only be pointed to:

```ktm run
struct Node;

struct List {
    Node *head;
    i64 len;
};

struct Node {
    i64 value;
    Node *next;
};

i32 main() {
    Node b = {2, null};
    Node a = {1, &b};
    List l = {&a, 2};
    printf("%d %d %d\n", l.head.value, l.head.next.value, l.len);
    return 0;
}
```

```output
1 2 2
```

Two structs that point at each other need it:

```ktm run
struct Owner;

struct Pet {
    u8 *name;
    Owner *owner;
};

struct Owner {
    u8 *name;
    Pet *pet;
};

i32 main() {
    Owner o = {"ada", null};
    Pet p = {"rex", &o};
    o.pet = &p;
    printf("%s's pet %s belongs to %s\n", o.name, o.pet.name, p.owner.name);
    return 0;
}
```

```output
ada's pet rex belongs to ada
```

Holding a declared-ahead struct by value before its definition is an error, because its size
isn't known yet:

```ktm error
struct B;

struct A {
    B b;
};

struct B { i64 x; };

i32 main() {
    return 0;
}
```

```output
which is not complete here
```

## Unions

A union is declared like a struct, but all its fields start at the same address, so they
share the memory. Writing one field overwrites the others; the union is as large as its
largest field.

```ktm run
union Bits {
    i64 i;
    f64 f;
    u8 bytes[8];
};

i32 main() {
    Bits b;
    b.i = 0x41;
    printf("%c, %d bytes\n", b.bytes[0], sizeof(Bits));
    b.f = 1.0;
    printf("%x\n", b.i);
    return 0;
}
```

```output
A, 8 bytes
3ff0000000000000
```

The second line shows the bits of the `f64` 1.0 read back as an integer: reading a field other
than the one written last reinterprets the bytes.

An initialiser list gives a union one value, for its first field:

```ktm run
union Number {
    i64 whole;
    f64 real;
};

i32 main() {
    Number n = {42};
    printf("%d\n", n.whole);
    return 0;
}
```

```output
42
```

### Anonymous unions: tagged values

An anonymous union inside a struct puts its fields into the struct, sharing one slot. With a
field that says which of them is in use (a tag), this is the classic way to keep one of
several kinds of value:

```ktm run
enum Kind { num, text };

struct Value {
    Kind kind;
    union {
        i64 n;
        u8 *s;
    };
};

void show(Value v) {
    if (v.kind == Kind.num) {
        printf("number %d\n", v.n);
    } else {
        printf("text %s\n", v.s);
    }
}

i32 main() {
    Value a = {Kind.num, 42};
    Value b;
    b.kind = Kind.text;
    b.s = "hi";
    show(a);
    show(b);
    printf("%d bytes\n", sizeof(Value));
    return 0;
}
```

```output
number 42
text hi
16 bytes
```

The union takes 8 bytes whichever field is used, so `Value` is 16 bytes: the tag (padded to
8) and the shared slot. The initialiser list fills the tag, then the union's first field.

An anonymous union can hold anonymous structs, for kinds of value with several fields each:

```ktm run
enum Shape { circle, rect };

struct Figure {
    Shape shape;
    union {
        struct { f64 radius; };
        struct { f64 width; f64 height; };
    };
};

f64 area(Figure f) {
    if (f.shape == Shape.circle) {
        return 3.14159 * f.radius * f.radius;
    }
    return f.width * f.height;
}

i32 main() {
    Figure c;
    c.shape = Shape.circle;
    c.radius = 2.0;
    Figure r;
    r.shape = Shape.rect;
    r.width = 3.0;
    r.height = 4.0;
    printf("%.2f %.2f\n", area(c), area(r));
    return 0;
}
```

```output
12.57 12.00
```

### Named inner types

A struct or union defined inside a struct can have a name of its own. A named union inside a
struct still adds its fields to the struct; a named struct followed by a field name declares
a field of that type. Either name can be used as a type elsewhere:

```ktm run
struct Shape {
    u8 kind;
    union Size { i32 side; f32 radius; };
    struct Pos { i32 x, y; } at;
};

i32 main() {
    Shape s;
    s.side = 3;
    s.at.x = 10;
    Size z;
    z.radius = 1.5;
    Pos p = {1, 2};
    printf("%d %d %.1f %d | %d %d\n", s.side, s.at.x, z.radius, p.y, sizeof(Size), sizeof(Shape));
    return 0;
}
```

```output
3 10 1.5 2 | 4 16
```

## Rules

- A struct's name is a type; there is no `typedef` step.
- Structs and unions are declared at the top level of a file.
- `{a, b, ...}` initialises fields in order, in a declaration only; missing fields are zero,
  extra ones are an error. A struct declared without one is all zero.
- Assigning, passing and returning a struct copy it, arrays inside included; pointers inside
  are copied as pointers.
- `.` reaches a field through a pointer too; `->` is accepted and means the same.
- `==` and `!=` don't apply to structs, and different struct types don't convert.
- The layout is C's: fields in order, each aligned to its size, the size rounded up to the
  largest alignment.
- `struct Name;` declares ahead: pointers to it work at once, holding it by value only after
  its definition.
- A union's fields share its memory; its initialiser list sets the first field.
- Anonymous structs and unions inside a struct add their fields to it.
