# Methods

A method is a function that belongs to a struct: it is named `Type.name`, written outside
the struct, and called on a value with `value.name(...)`. A struct can also extend one other
struct and get its fields and methods. This chapter covers defining and calling methods,
`self`, the conventions for making and freeing values, inheritance, hiding a method, and why
there is no virtual dispatch (and what to do instead). ktm has no classes and no
constructors: a struct is data, and its methods are functions.

## Defining a method

A method is written like a function whose name is the struct's name, a dot, and the method's
name. Inside it, `self` is a pointer to the value it was called on.

```ktm run
struct Account {
    u8 *owner;
    i64 balance;
};

void Account.deposit(i64 amount) {
    self.balance += amount;
}

void Account.print() {
    printf("%s: %d\n", self.owner, self.balance);
}

i32 main() {
    Account a = {"ann", 0};
    a.deposit(100);
    a.deposit(25);
    a.print();
    return 0;
}
```

```output
ann: 125
```

Because `self` is a pointer, a method changes the caller's value, not a copy:
`a.deposit(100)` changed `a`.

A method can return a value like any function:

```ktm run
struct Account {
    u8 *owner;
    i64 balance;
};

bool Account.withdraw(i64 amount) {
    if (amount > self.balance) {
        return false;
    }
    self.balance -= amount;
    return true;
}

i32 main() {
    Account a = {"ann", 100};
    printf("%d %d\n", a.withdraw(30), a.withdraw(500));
    printf("%d\n", a.balance);
    return 0;
}
```

```output
1 0
70
```

## Calling methods

A method is called on a struct value or on a pointer to one, the same way:

```ktm run
struct Counter { i64 n; };

void Counter.bump() {
    self.n++;
}

i32 main() {
    Counter c = {0};
    Counter *p = &c;
    c.bump();
    p.bump();
    p->bump();
    printf("%d\n", c.n);
    return 0;
}
```

```output
3
```

It can be called on an element of an array, on a field, or on a struct a function just
returned:

```ktm run
struct Point { i64 x, y; };

i64 Point.sum() {
    return self.x + self.y;
}

struct Segment { Point a, b; };

Point make(i64 x, i64 y) {
    Point p = {x, y};
    return p;
}

i32 main() {
    Point ps[2] = {{1, 2}, {3, 4}};
    Segment s = {{5, 6}, {7, 8}};
    printf("%d %d %d\n", ps[1].sum(), s.b.sum(), make(10, 20).sum());
    return 0;
}
```

```output
7 15 30
```

A method that returns `self` lets calls be chained:

```ktm run
struct Builder { i64 total; };

Builder *Builder.add(i64 n) {
    self.total += n;
    return self;
}

i32 main() {
    Builder b = {0};
    b.add(1).add(2).add(3);
    printf("%d\n", b.total);
    return 0;
}
```

```output
6
```

Methods take everything a function takes: default arguments, overloads (see
[Overloading](18-overloading.html)) and variable arguments (see [Varargs](19-varargs.html)).

```ktm run
struct Door { bool open; i64 angle; };

void Door.swing(i64 angle = 90) {
    self.open = angle > 0;
    self.angle = angle;
}

i32 main() {
    Door d;
    d.swing();
    printf("%d %d\n", d.open, d.angle);
    d.swing(0);
    printf("%d %d\n", d.open, d.angle);
    return 0;
}
```

```output
1 90
0 0
```

## `self`

`self` is a pointer to the value; its fields are reached with `self.field`. It is fixed for
the whole call: assigning to it is an error.

```ktm error
struct Point { i64 x; };

void Point.reset() {
    self = null;
}

i32 main() {
    return 0;
}
```

```output
cannot assign to const 'self'
```

`*self` is the value itself, for when a copy is wanted:

```ktm run
struct Point { i64 x, y; };

Point Point.mirrored() {
    Point m = *self;
    m.x = -m.x;
    return m;
}

i32 main() {
    Point p = {3, 4};
    Point q = p.mirrored();
    printf("(%d, %d) (%d, %d)\n", p.x, p.y, q.x, q.y);
    return 0;
}
```

```output
(3, 4) (-3, 4)
```

A method calls the struct's other methods through `self`:

```ktm run
struct Temp { f64 celsius; };

f64 Temp.fahrenheit() {
    return self.celsius * 9.0 / 5.0 + 32.0;
}

void Temp.print() {
    printf("%.1f C = %.1f F\n", self.celsius, self.fahrenheit());
}

i32 main() {
    Temp t = {21.5};
    t.print();
    return 0;
}
```

```output
21.5 C = 70.7 F
```

## Declaring a method ahead

A method's prototype, `Type.name(...);` without a body, lets it be called before it is
defined:

```ktm run
struct Point { i64 x; };

void Point.show();

i32 main() {
    Point a = {4};
    a.show();
    return 0;
}

void Point.show() {
    printf("%d\n", self.x);
}
```

```output
4
```

## Making and freeing values

There are no constructors or destructors. By convention a plain function makes the value
(often `new` or `open` in a module, see [Modules and packages](22-modules-and-packages.html)),
and a `free()` method releases what it holds:

```ktm run
import mem;

struct Buffer {
    u8 *data;
    i64 len;
};

Buffer bufferNew(i64 size) {
    Buffer b = {mem.allocZero(size), size};
    return b;
}

void Buffer.free() {
    mem.free(self.data);
    self.data = null;
    printf("freed %d bytes\n", self.len);
}

i32 main() {
    Buffer b = bufferNew(64);
    b.data[0] = 'x';
    printf("%c\n", b.data[0]);
    b.free();
    return 0;
}
```

```output
x
freed 64 bytes
```

A value with a `free()` method can be declared with `ad` instead of a type's plain declaration:
`free()` is then called for it when its block ends, however the block is left (see
[defer and ad](16-defer-and-ad.html)).

```ktm run
import mem;

struct Buffer {
    u8 *data;
    i64 len;
};

Buffer bufferNew(i64 size) {
    Buffer b = {mem.allocZero(size), size};
    return b;
}

void Buffer.free() {
    mem.free(self.data);
    printf("freed %d bytes\n", self.len);
}

i32 main() {
    ad Buffer b = bufferNew(16);
    b.data[0] = 'y';
    printf("%c\n", b.data[0]);
    return 0;
}
```

```output
y
freed 16 bytes
```

A method that sets fields up can stand in for a constructor too, called on a zeroed value:

```ktm run
struct Range { i64 lo, hi, step; };

void Range.init(i64 lo, i64 hi, i64 step = 1) {
    self.lo = lo;
    self.hi = hi;
    self.step = step;
}

i64 Range.count() {
    return (self.hi - self.lo + self.step - 1) / self.step;
}

i32 main() {
    Range r;
    r.init(0, 10, 3);
    printf("%d\n", r.count());
    return 0;
}
```

```output
4
```

## Inheritance

`struct Derived : Base { ... };` makes a struct that starts with all of `Base`'s fields,
followed by its own. It also has `Base`'s methods.

```ktm run
struct User {
    u8 *name;
    i64 age;
};

struct Admin : User {
    i64 level;
};

void User.describe() {
    printf("%s is %d\n", self.name, self.age);
}

void User.birthday() {
    self.age++;
}

i32 main() {
    Admin a;
    a.name = "bob";
    a.age = 40;
    a.level = 2;
    a.birthday();
    a.describe();
    printf("%d bytes\n", sizeof(Admin));
    return 0;
}
```

```output
bob is 41
24 bytes
```

Because a derived struct begins with its base, a pointer to it can be used wherever a pointer
to the base is expected:

```ktm run
struct User { u8 *name; i64 age; };
struct Admin : User { i64 level; };

void greet(User *u) {
    printf("hello %s\n", u.name);
}

i32 main() {
    Admin a;
    a.name = "bob";
    a.level = 3;
    greet(&a);
    User *u = &a;
    printf("%s\n", u.name);
    return 0;
}
```

```output
hello bob
bob
```

Going the other way, from a base pointer to a derived one, is a plain conversion: it is only
right when the value really is the derived struct.

```ktm run
struct User { u8 *name; };
struct Admin : User { i64 level; };

void show(User *u, bool isAdmin) {
    if (isAdmin) {
        Admin *a = u;
        printf("%s, level %d\n", a.name, a.level);
    } else {
        printf("%s\n", u.name);
    }
}

i32 main() {
    Admin a;
    a.name = "bob";
    a.level = 3;
    User u = {"ann"};
    show(&a, true);
    show(&u, false);
    return 0;
}
```

```output
bob, level 3
ann
```

The chain can go on: a struct can extend one that extends another.

```ktm run
struct Animal { u8 *name; };
struct Dog : Animal { i64 tricks; };
struct Puppy : Dog { i64 ageWeeks; };

void Animal.hello() {
    printf("I am %s\n", self.name);
}

i32 main() {
    Puppy p;
    p.name = "rex";
    p.tricks = 2;
    p.ageWeeks = 9;
    p.hello();
    printf("%d %d, %d bytes\n", p.tricks, p.ageWeeks, sizeof(Puppy));
    return 0;
}
```

```output
I am rex
2 9, 24 bytes
```

- A struct extends at most one base.
- The base's fields come first, in its layout; the derived struct's follow.
- A derived struct initialised with `{...}` takes the base's fields first, then its own.

## Hiding a base method

A method on the derived struct with the same name as the base's hides it. `Base.name(self)`
still calls the base's version, for a method that adds to it:

```ktm run
struct User { u8 *name; };
struct Admin : User { i64 level; };

void User.describe() {
    printf("user %s\n", self.name);
}

void Admin.describe() {
    printf("admin %d, ", self.level);
    User.describe(self);
}

i32 main() {
    Admin a;
    a.name = "bob";
    a.level = 2;
    a.describe();
    return 0;
}
```

```output
admin 2, user bob
```

## No virtual dispatch

Which method runs is decided by the type the compiler sees, never at run time. Through a
`User *`, the call is `User.describe`, even when the value is an `Admin`:

```ktm run
struct User { u8 *name; };
struct Admin : User { i64 level; };

void User.describe() {
    printf("user %s\n", self.name);
}

void Admin.describe() {
    printf("admin %s\n", self.name);
}

i32 main() {
    Admin a;
    a.name = "bob";
    a.describe();
    User *u = &a;
    u.describe();
    return 0;
}
```

```output
admin bob
user bob
```

For behaviour chosen at run time, put a function pointer in the struct (see
[Function pointers](20-function-pointers.html)). Each value carries the function it uses:

```ktm run
struct Shape {
    u8 *name;
    fn(Shape *) f64 area;
};

struct Circle : Shape { f64 r; };
struct Rect : Shape { f64 w, h; };

f64 circleArea(Shape *s) {
    Circle *c = s;
    return 3.14159 * c.r * c.r;
}

f64 rectArea(Shape *s) {
    Rect *r = s;
    return r.w * r.h;
}

i32 main() {
    Circle c;
    c.name = "circle";
    c.area = &circleArea;
    c.r = 1.0;
    Rect r;
    r.name = "rect";
    r.area = &rectArea;
    r.w = 2.0;
    r.h = 3.0;
    Shape *shapes[2] = {&c, &r};
    for (i64 i = 0; i < 2; i++) {
        Shape *s = shapes[i];
        printf("%s: %.2f\n", s.name, s.area(s));
    }
    return 0;
}
```

```output
circle: 3.14
rect: 6.00
```

## A method is a function

`Type.name` is an ordinary function whose first parameter is `self`. It can be called that way
too, and `&Type.name` is its address, a `fn(Type *, ...)` like any other function's:

```ktm run
struct User { u8 *name; };

void User.hello() {
    printf("hello %s\n", self.name);
}

void each(User[] users, fn(User *) void f) {
    for (i64 i = 0; i < users.len; i++) {
        f(&users[i]);
    }
}

i32 main() {
    User all[2] = {{"ann"}, {"bob"}};
    User.hello(&all[0]);
    each(all, &User.hello);
    return 0;
}
```

```output
hello ann
hello ann
hello bob
```

There are no bound methods: `u.hello` without parentheses is an error.

```ktm error
struct User { u8 *name; };

void User.hello() {
}

i32 main() {
    User u;
    auto f = u.hello;
    return 0;
}
```

```output
is a method: call it with `(...)`, or take its address with `&User.hello`
```

## Unions

A union can have methods as well; `self` points at the union:

```ktm run
union Bits {
    i64 i;
    f64 f;
};

i64 Bits.exponent() {
    return (self.i >> 52) & 0x7FF;
}

i32 main() {
    Bits b;
    b.f = 8.0;
    printf("%d\n", b.exponent() - 1023);
    return 0;
}
```

```output
3
```

## What isn't allowed

A method can't have the name of one of the struct's fields (inherited ones included):

```ktm error
struct Point { i64 x; };

void Point.x() {
}

i32 main() {
    return 0;
}
```

```output
has a field named `x`: a method cannot have the same name
```

Calling a field as if it were a method says so:

```ktm error
struct Box { i64 len; };

i32 main() {
    Box b;
    return b.len();
}
```

```output
`len` is a field, not a method: read it without `()`
```

Only the module that defines a type can give it methods (see
[Modules and packages](22-modules-and-packages.html)):

```ktm error
import json;

void json.Value.dump() {
}

i32 main() {
    return 0;
}
```

```output
can only be defined in the module that defines it
```

## Rules

- A method is `Ret Type.name(params) { ... }`, written outside the struct, for a struct or a
  union.
- `self` is a pointer to the value the method was called on, and can't be reassigned.
- `v.name(...)` works on a value, a pointer, an array element, a field or a returned struct.
- Default arguments, overloads and varargs work as for functions.
- `struct D : B` extends one struct: `D` starts with `B`'s fields, has `B`'s methods, and a
  `D *` converts to a `B *`.
- A derived struct's method of the same name hides the base's; `B.name(self)` calls the base's.
- The call is chosen by the static type: no virtual dispatch. Use function pointers for that.
- `&Type.name` is the method as a function whose first parameter is `self`; there are no
  bound methods.
- A method can't be named like a field, and only the defining module adds methods to a type.
