# Attributes

An attribute is a word starting with `@`, written before a struct, a field or a parameter. It
asks the compiler to write code for the declaration, or tells a library how to treat it. This
chapter covers where attributes go, `@json` and `@yaml` and the methods they generate, the field
attributes `@key`, `@skip` and `@omitEmpty`, the settings attributes `@env` and `@required`, the
validation rules `@min`, `@max`, `@email`, `@url` and `@oneOf`, `@caller` on parameters, seeing
the generated code, and reading attributes through reflection.

## Where attributes go

An attribute is `@name` or `@name(arguments)`, before what it applies to. Several can be
written one after another:

```ktm
@json @yaml
struct User {
    @key("e-mail") @required String email;
    @skip i64 cacheHits;
};
```

| Attribute | Goes before | What it does |
| --- | --- | --- |
| `@json` | a struct | generates `toJson`, `fromJson`, `freeDecoded` |
| `@yaml` | a struct | generates `toYaml`, `fromYaml` (and `freeDecoded`) |
| `@key("name")` | a field | the field's key in JSON and YAML, its column for ktm-pg |
| `@skip` | a field | leaves the field out of JSON, YAML, settings and validation |
| `@omitEmpty` | a field | leaves it out of JSON and YAML when it is 0, empty or null |
| `@env("NAME")` | a field | its environment variable for `config.fromEnv` |
| `@required` | a field | `config.fromEnv` fails without it; `validate.check` without a value |
| `@min(n)`, `@max(n)` | a field | `validate.check`: a number's bounds, a text's or slice's length |
| `@email`, `@url` | a field | `validate.check`: an email address, an absolute URL |
| `@oneOf("a", "b")` | a field | `validate.check`: one of the values |
| `@caller("file")`, `("line")`, `("func")` | a parameter | filled by every call with where the call is |

An attribute ktm doesn't know, or one in the wrong place, is a compile error:

```ktm error
struct User {
    @jsonn u8 *name;
};

i32 main() {
    return 0;
}
```

```output
unknown attribute `@jsonn`
```

## @json

`@json` before a struct makes the compiler write a JSON encoder and decoder for it, as methods.
The struct's file must import `json`. `json.decode<T>` and `json.encode` then use them:

```ktm run
import json, reflect;

@json struct User {
    i64 id;
    String name;
    bool admin;
};

i32 main() {
    User u = json.decode<User>("{\"id\": 7, \"name\": \"Ada\", \"admin\": true}");
    printf("%d %s %d\n", u.id, u.name, u.admin);
    u.id = 8;
    scoped String text = json.encode(reflect.of(u));
    printf("%s\n", text);
    u.freeDecoded();
    return 0;
}
```

```output
7 Ada 1
{"id":8,"name":"Ada","admin":true}
```

- A key the struct has no field for is ignored; a field the JSON leaves out is zero.
- An enum field is written as its member's name.
- Nested structs, slices, `vec.Vec<T>` and pointers are written and read at any depth.
- `freeDecoded()` frees what decoding allocated (`String`s, slices, pointed-to structs);
  `json.free(reflect.of(u))` does the same.

```ktm run
import json, reflect;

enum Role { guest, admin };

@json struct Tag {
    String name;
    i64 weight;
};

@json struct User {
    i64 id;
    Role role;
    Tag[] tags;
};

i32 main() {
    User u = json.decode<User>("{\"id\": 1, \"role\": \"admin\", \"tags\": [{\"name\": \"math\", \"weight\": 3}], \"since\": 1843}");
    printf("%d %s %d %s\n", u.id, reflect.name(u.role), u.tags.len, u.tags[0].name);
    scoped String text = json.encode(reflect.of(u));
    printf("%s\n", text);
    u.freeDecoded();
    return 0;
}
```

```output
1 admin 1 math
{"id":1,"role":"admin","tags":[{"name":"math","weight":3}]}
```

Without `@json` the same calls work by reflection, about three times slower to decode; adding
`@json` makes existing code faster with no other change. The struct's file must import `json`:

```ktm error
@json struct User {
    i64 id;
};

i32 main() {
    return 0;
}
```

```output
needs `import json;`
```

### The generated methods

| Method | Does |
| --- | --- |
| `u.toJson(&w)` | writes `u` to a `json.Writer` |
| `u.fromJson(&r)` | reads `u` from a `json.Reader` |
| `u.freeDecoded()` | frees what `fromJson` (or `decode`) allocated |
| `u.toYaml(&w)`, `u.fromYaml(&r)` | the same for YAML, with `@yaml` |

```ktm run
import json;

@json struct Point {
    i64 x;
    i64 y;
};

i32 main() {
    Point p = {3, 4};
    json.Writer w = json.writerNew();
    p.toJson(&w);
    scoped String text = w.take();
    w.free();
    printf("%s\n", text);
    return 0;
}
```

```output
{"x":3,"y":4}
```

A struct with `@json` and no `free()` of its own can be declared `scoped`: it is freed with
`freeDecoded()` (see [defer and scoped](16-defer-and-scoped.html)).

## @yaml

`@yaml` does the same for YAML, and the two go together on one struct:

```ktm run
import json, yaml;

@json @yaml struct Server {
    String host;
    i64 port;
    String[] aliases;
};

i32 main() {
    scoped Server s = json.decode<Server>("{\"host\": \"example.com\", \"port\": 443, \"aliases\": [\"www\", \"api\"]}");
    yaml.Writer y = yaml.writerNew();
    s.toYaml(&y);
    scoped String doc = y.take();
    y.free();
    printf("%s", doc);
    return 0;
}
```

```output
host: example.com
port: 443
aliases:
  - www
  - api
```

## Field attributes for JSON and YAML

`@key("name")` is the field's key in the text, `@skip` leaves the field out entirely, and
`@omitEmpty` leaves it out of the output when it is 0, empty or null:

```ktm run
import json;

@json struct User {
    i64 id;
    @key("e-mail") String email;
    @omitEmpty String nickname;
    @skip i64 cacheHits;
};

i32 main() {
    scoped User u = json.decode<User>("{\"id\": 7, \"e-mail\": \"ada@example.com\"}");
    u.cacheHits = 99;
    json.Writer w = json.writerNew();
    u.toJson(&w);
    scoped String text = w.take();
    w.free();
    printf("%s\n", text);
    return 0;
}
```

```output
{"id":7,"e-mail":"ada@example.com"}
```

`nickname` is empty, so `@omitEmpty` left it out; `cacheHits` is `@skip`, so it is never written,
whatever its value.

Strict decoding refuses keys the struct doesn't have and keys it misses, but a `@skip` field is
never required:

```ktm run
import json;

@json struct Point {
    i64 x;
    i64 y;
    @skip i64 cached;
};

i32 main() {
    u8[] inputs[3] = {"{\"x\": 1, \"y\": 2}", "{\"x\": 1}", "{\"x\": 1, \"y\": 2, \"z\": 3}"};
    for (i64 i = 0; i < 3; i++) {
        try {
            Point p = json.decode<Point>(inputs[i], json.STRICT);
            printf("ok: %d %d\n", p.x, p.y);
        } catch (Error e) {
            printf("%s\n", e.message);
        }
    }
    return 0;
}
```

```output
ok: 1 2
1:9: y: missing
1:22: z: no such field in Point
```

A field whose type can't be written (a union, a function pointer, a struct without `@json` when
the others need it) is an error at that field; mark it `@skip`.

## Settings: @env and @required

`config.fromEnv` fills a struct of settings from environment variables. A field's variable is a
prefix and its name in `SNAKE_CASE` (`databaseUrl` with `APP_` reads `APP_DATABASE_URL`).
`@env("NAME")` names the variable exactly, and `@required` makes a missing one an error:

```ktm run
import config, proc, reflect;

struct Settings {
    i64 port;
    @required String databaseUrl;
    @env("LOG_LEVEL") u8[] level;
};

i32 main() {
    proc.setenv("APP_DATABASE_URL", "postgres://localhost/app");
    proc.setenv("LOG_LEVEL", "warn");
    Settings s;
    s.port = 8080;
    config.fromEnv(reflect.of(s), "APP_");
    printf("%d %s %.*s\n", s.port, s.databaseUrl, s.level.len, s.level.ptr);
    config.free(reflect.of(s));
    proc.unsetenv("APP_DATABASE_URL");
    try {
        config.fromEnv(reflect.of(s), "APP_");
    } catch (Error e) {
        printf("%s\n", e.message);
    }
    return 0;
}
```

```output
8080 postgres://localhost/app warn
config: APP_DATABASE_URL is required
```

A field keeps what it holds when its variable isn't set, so defaults are whatever the struct
holds first (`port` above). See [config](../std/index.html#config).

## Validation rules

`validate.check` checks a struct's values against rules written on its fields, and collects one
error per broken rule, naming the field:

```ktm run
import reflect, str, validate;

struct Signup {
    @required @email u8[] email;
    @required @min(3) @max(20) String name;
    @min(18) i64 age;
    @oneOf("free", "pro") u8[] plan;
};

i32 main() {
    Signup s;
    s.email = "ann@example";
    s.name = str.from("an");
    s.age = 17;
    s.plan = "gold";
    validate.Errors errs;
    if (!validate.check(reflect.of(s), &errs)) {
        for (i64 i = 0; i < errs.items.len; i++) {
            validate.FieldError e = errs.items.get(i);
            printf("%s: %s\n", e.field, e.message);
        }
    }
    errs.free();
    s.name.free();
    return 0;
}
```

```output
email: not an email address
name: must have at least 3 characters
age: must be at least 18
plan: must be one of free, pro
```

| Rule | On | Holds when |
| --- | --- | --- |
| `@required` | any field | the value isn't 0, false, "", an empty slice or null |
| `@min(n)`, `@max(n)` | a number | its value is at least / at most n |
| `@min(n)`, `@max(n)` | `String`, `u8[]` | its length in characters is at least / at most n |
| `@min(n)`, `@max(n)` | a slice | it has at least / at most n items |
| `@email` | `String`, `u8[]` | it looks like `name@example.com` |
| `@url` | `String`, `u8[]` | it is an absolute URL with a host |
| `@oneOf("a", "b")` | `String`, `u8[]` | it is exactly one of the values |

- An empty text or slice that isn't `@required` passes its other rules; a number is always
  checked.
- Nested structs, pointed-to structs and slices of structs are checked too.
- The rules work on any struct, with or without `@json`; `@skip` fields are left alone.
- A rule on a field it can't check (`@email` on an `i64`) makes `check` throw
  `validate.ERR_VALIDATE`.

See [validate](../std/index.html#validate) for the error list's methods and HTTP use.

## @caller on parameters

A parameter marked `@caller("file")`, `@caller("line")` or `@caller("func")` is filled in by
every call with where that call is written. Callers never pass it:

```ktm run
void trace(@caller("file") u8 *file, @caller("line") i64 line, @caller("func") u8 *func, u8 *what) {
    printf("%s:%d in %s: %s\n", file, line, func, what);
}

void load() {
    trace("loading");
}

i32 main() {
    trace("start");
    load();
    return 0;
}
```

```output
example.ktm:10 in main: start
example.ktm:6 in load: loading
```

- `file` is the source file's path as ktm was given it, `func` the function the call is in;
  both are `u8 *`. `line` is an integer.
- They can go anywhere in the parameter list, and have no default.
- [Errors](15-errors.html) uses them to report where a check failed.

## Seeing the generated code

`ktm --expand` prints the methods `@json` and `@yaml` write, on stderr:

```sh
$ ktm --expand user.ktm -o user
```

## Attributes through reflection

The type information of a struct carries its attributes: whether it is `@json`, and each field's
key and `@skip` (see [Reflection](24-reflection.html)):

```ktm run
import json, reflect;

@json struct Item {
    @key("item-id") i64 id;
    @skip i64 internal;
    u8 *label;
};

i32 main() {
    reflect.Type *t = reflect.typeOf(Item);
    printf("json: %d\n", t.json);
    for (i64 i = 0; i < t.fields.len; i++) {
        reflect.Field f = t.fields[i];
        printf("%.*s key=%.*s skip=%d\n", f.name.len, f.name.ptr, f.key.len, f.key.ptr, f.skip);
    }
    return 0;
}
```

```output
json: 1
id key=item-id skip=0
internal key=internal skip=1
label key=label skip=0
```

`Field.key` is the key a field is written under: its `@key`, or its name.

## Rules

- `@json` and `@yaml` go before the struct's definition (and before `pub`), not before a
  declaration ahead; the file imports `json` or `yaml`.
- Field attributes go before the field's type.
- `@key`, `@skip`, `@omitEmpty` shape JSON and YAML; `@env`, `@required` are read by `config`;
  `@required`, `@min`, `@max`, `@email`, `@url`, `@oneOf` by `validate`.
- `@caller` goes on parameters only.
- An unknown or misplaced attribute is a compile error.
