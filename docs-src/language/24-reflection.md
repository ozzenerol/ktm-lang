# Reflection

Reflection lets a program look at its own types while it runs: a struct's fields and where
they are, an enum's members and their names, a value's type. The `reflect` module provides it.
This chapter covers describing a type with `reflect.typeOf`, what a `reflect.Type` holds,
handling a value of any type with `reflect.of`, reading and writing fields by name, enum names,
attributes, and how the library builds on it.

## Describing a type

`reflect.typeOf(T)` gives a `reflect.Type *` for a type, or for the type of an expression:

```ktm run
import reflect;

enum Role { guest, admin = 10 };

struct User {
    i64 id;
    u8[] name;
    Role role;
    User *boss;
};

i32 main() {
    reflect.Type *t = reflect.typeOf(User);
    printf("%s, %d bytes, %d fields\n", t.name, t.size, t.fields.len);
    for (auto f : t.fields) {
        printf("  %-5s at %2d: %s\n", f.name, f.offset, f.type.name);
    }
    reflect.Type *boss = t.fields[3].type;
    printf("%s points to %s\n", boss.name, boss.elem.name);
    return 0;
}
```

```output
User, 40 bytes, 4 fields
  id    at  0: i64
  name  at  8: u8[]
  role  at 24: Role
  boss  at 32: User *
User * points to User
```

`f.offset` is the field's offset in bytes, which is how a C `offsetof` is written in ktm.

The type of an expression works the same way:

```ktm run
import reflect;

i32 main() {
    i64 grid[4];
    f32 ratio = 0.5;
    printf("%s %s %s\n", reflect.typeOf(grid).name, reflect.typeOf(ratio).name, reflect.typeOf(&ratio).name);
    return 0;
}
```

```output
i64[4] f32 f32 *
```

## What a Type holds

| Field | Holds |
| --- | --- |
| `kind` | a `reflect.Kind`: `int`, `float`, `boolean`, `enumType`, `pointer`, `array`, `slice`, `string`, `structType`, `unionType`, `fnType`, `none` |
| `name` | the type's name, as `typeof` prints it |
| `size`, `isSigned` | size in bytes; whether an integer is signed |
| `elem`, `len` | element type and length of a pointer, array or slice |
| `fields` | a struct's fields (`name`, `type`, `offset`, `isPrivate`), inherited ones first |
| `members` | an enum's members (`name`, `value`) |

A function can switch on the kind:

```ktm run
import reflect;

void describe(reflect.Type *t) {
    switch (t.kind) {
        case reflect.Kind.int:    printf("%s: integer, %d bytes, signed %d\n", t.name, t.size, t.isSigned);
        case reflect.Kind.float:  printf("%s: float\n", t.name);
        case reflect.Kind.array:  printf("%s: %d x %s\n", t.name, t.len, t.elem.name);
        case reflect.Kind.slice:  printf("%s: slice of %s\n", t.name, t.elem.name);
        case reflect.Kind.string: printf("%s: String\n", t.name);
        default:                  printf("%s: something else\n", t.name);
    }
}

i32 main() {
    i64 grid[4];
    describe(reflect.typeOf(u16));
    describe(reflect.typeOf(f64));
    describe(reflect.typeOf(grid));
    describe(reflect.typeOf(u8[]));
    describe(reflect.typeOf(String));
    describe(reflect.typeOf(bool));
    return 0;
}
```

```output
u16: integer, 2 bytes, signed 0
f64: float
i64[4]: 4 x i64
u8[]: slice of u8
String: String
bool: something else
```

An enum's members carry their names and values:

```ktm run
import reflect;

enum Level { debug, info = 5, warn };

i32 main() {
    reflect.Type *t = reflect.typeOf(Level);
    for (auto m : t.members) {
        printf("%s = %d\n", m.name, m.value);
    }
    return 0;
}
```

```output
debug = 0
info = 5
warn = 6
```

A derived struct lists its base's fields first:

```ktm run
import reflect;

struct Base { i64 id; };
struct Item : Base { u8 *label; };

i32 main() {
    for (auto f : reflect.typeOf(Item).fields) {
        printf("%s ", f.name);
    }
    printf("\n");
    return 0;
}
```

```output
id label 
```

## Values of any type

`reflect.of(x)`, for a variable, a field or an element, gives a `reflect.Value`: its address
(`ptr`) and its type (`type`). One function can then take a value of any type:

```ktm run
import reflect;

void show(reflect.Value v) {
    printf("%s at %d bytes: ", v.type.name, v.type.size);
    if (v.type.kind == reflect.Kind.int) {
        printf("%d\n", reflect.getInt(v));
    } else if (v.type.kind == reflect.Kind.float) {
        printf("%.2f\n", reflect.getFloat(v));
    } else {
        printf("(not a number)\n");
    }
}

i32 main() {
    i32 a = 7;
    f64 b = 2.5;
    u8 *c = "x";
    show(reflect.of(a));
    show(reflect.of(b));
    show(reflect.of(c));
    return 0;
}
```

```output
i32 at 4 bytes: 7
f64 at 8 bytes: 2.50
u8 * at 8 bytes: (not a number)
```

`reflect.of` needs something with an address; a plain value is an error:

```ktm error
import reflect;

i32 main() {
    reflect.Value v = reflect.of(1 + 2);
    return 0;
}
```

```output
reflect.of takes a variable, a field or an element
```

## Fields by name

`reflect.field(v, name)` is one field of a struct value, as a `reflect.Value`; its `type` is
`null` when there is no such field. `getInt`/`setInt` and `getFloat`/`setFloat` read and write
integers, bools, enums and floats of any size:

```ktm run
import reflect;

struct Point { i32 x; i32 y; };
struct Stats { i64 hits; f64 ratio; };

void bump(reflect.Value v, u8[] field) {
    reflect.Value f = reflect.field(v, field);
    if (!f.type) {
        printf("no field %s\n", field);
        return;
    }
    reflect.setInt(f, reflect.getInt(f) + 1);
}

i32 main() {
    Point p = {3, 4};
    Stats s = {10, 0.5};
    bump(reflect.of(p), "y");
    bump(reflect.of(s), "hits");
    bump(reflect.of(s), "misses");
    reflect.setFloat(reflect.field(reflect.of(s), "ratio"), 0.75);
    printf("%d %d | %d %.2f\n", p.x, p.y, s.hits, s.ratio);
    return 0;
}
```

```output
no field misses
3 5 | 11 0.75
```

A generic printer walks a struct's fields:

```ktm run
import reflect;

struct Server {
    u8[] host;
    i64 port;
    bool tls;
    f64 timeout;
};

void dump(reflect.Value v) {
    for (auto f : v.type.fields) {
        reflect.Value fv = reflect.field(v, f.name);
        printf("%s = ", f.name);
        switch (f.type.kind) {
            case reflect.Kind.int:
            case reflect.Kind.boolean: printf("%d\n", reflect.getInt(fv));
            case reflect.Kind.float:   printf("%.1f\n", reflect.getFloat(fv));
            case reflect.Kind.slice:   printf("%s\n", *(u8[] *)fv.ptr);
            default:                   printf("?\n");
        }
    }
}

i32 main() {
    Server s = {"localhost", 8080, true, 2.5};
    dump(reflect.of(s));
    return 0;
}
```

```output
host = localhost
port = 8080
tls = 1
timeout = 2.5
```

The same walk can fill a struct, as a settings loader does:

```ktm run
import reflect;

struct Limits {
    i64 maxUsers;
    i64 maxFiles;
    f64 ratio;
};

void setAll(reflect.Value v, i64 n) {
    for (auto f : v.type.fields) {
        reflect.Value fv = reflect.field(v, f.name);
        if (f.type.kind == reflect.Kind.int) {
            reflect.setInt(fv, n);
        }
    }
}

i32 main() {
    Limits l = {0, 0, 0.5};
    setAll(reflect.of(l), 100);
    printf("%d %d %.1f\n", l.maxUsers, l.maxFiles, l.ratio);
    return 0;
}
```

```output
100 100 0.5
```

## Enum names

`reflect.name(e)` is the name of an enum value's member, `""` for a value that isn't one.
`reflect.nameOf(type, n)` names a number, and `reflect.enumValue(type, name, &v)` finds the value
of a name:

```ktm run
import reflect;

enum Level { debug, info, warn };

i32 main() {
    Level l = Level.warn;
    printf("%s\n", reflect.name(l));
    printf("%s\n", reflect.nameOf(reflect.typeOf(Level), 1));
    i64 v;
    if (reflect.enumValue(reflect.typeOf(Level), "debug", &v)) {
        printf("debug is %d\n", v);
    }
    printf("[%s]\n", reflect.name((Level)9));
    return 0;
}
```

```output
warn
info
debug is 0
[]
```

## Attributes

A type's description carries the attributes written on it (see [Attributes](23-attributes.html)):
`Type.json`, and for each field `key`, `skip`, `omitEmpty`, `env`, `required`, and validation's
`hasMin`/`min`, `hasMax`/`max`, `email`, `url` and `oneOf`:

```ktm run
import json, reflect;

@json struct Account {
    @key("e-mail") @required u8[] email;
    @skip i64 cache;
    @omitEmpty u8[] note;
};

i32 main() {
    reflect.Type *t = reflect.typeOf(Account);
    printf("json %d\n", t.json);
    for (auto f : t.fields) {
        printf("%s: key %s, skip %d, omitEmpty %d, required %d\n", f.name, f.key, f.skip, f.omitEmpty, f.required);
    }
    return 0;
}
```

```output
json 1
email: key e-mail, skip 0, omitEmpty 0, required 1
cache: key cache, skip 1, omitEmpty 0, required 0
note: key note, skip 0, omitEmpty 1, required 0
```

## Comparing types

A type's description is made at compile time, as read-only data, only for the types a program
asks about and the types they lead to. A library and a program each have their own copy, so two
`Type` pointers for the same type may differ: compare with `reflect.same(a, b)`, not `==`.

```ktm run
import reflect;

struct P { i64 x; };

i32 main() {
    reflect.Type *a = reflect.typeOf(P);
    P p;
    reflect.Type *b = reflect.of(p).type;
    printf("%d %d\n", reflect.same(a, b), reflect.same(a, reflect.typeOf(i64)));
    return 0;
}
```

```output
1 0
```

## What the library builds on it

`json.encode`, `json.decode`, `config.fromEnv`, `validate.check` and `any ...` extras all work
through `reflect.of(x)` (see [Variable arguments](19-varargs.html)): they walk the struct's fields
and read or write them with the functions above.

```ktm run
import json, reflect;

struct Config {
    i64 port;
    i64 workers;
    bool debug;
};

i32 main() {
    Config c = {8080, 4, false};
    json.decode("{\"workers\": 16}", reflect.of(c));
    printf("%d %d %d\n", c.port, c.workers, c.debug);
    return 0;
}
```

```output
8080 16 0
```

Fields the JSON leaves out keep what they had, so defaults survive.

## Rules

- `reflect.typeOf(T)` or `reflect.typeOf(expr)` is a `reflect.Type *`; `reflect.of(x)` a
  `reflect.Value` (address and type) of something with an address. Both need `import reflect;`.
- `Type.fields` lists a struct's fields with their offsets, inherited ones first;
  `Type.members` an enum's members.
- `reflect.field`, `getInt`/`setInt`, `getFloat`/`setFloat` read and write fields by name.
- `reflect.name`, `nameOf` and `enumValue` map enum values and names.
- Descriptions are compile-time data; compare types with `reflect.same`.
