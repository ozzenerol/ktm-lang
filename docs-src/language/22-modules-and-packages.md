# Modules and packages

A module is a directory of `.ktm` files with a name. A program imports it and uses the names
the module marks `pub`, with the module's name in front: `geo.area(5)`. A package is a module
that lives in its own git repository and is imported by its path, as in Go. This chapter covers
writing a module, what is visible where, methods and private fields across modules, imports and
how modules are found, start-up order, the prelude, packages with `ktm.mod`, `ktm get`, and the
prebuilt package cache.

## Importing a module

The standard library is a set of modules. `import` makes one available by its name:

```ktm run
import str;

i32 main() {
    ad String s = str.from("modules");
    s.append(" and packages");
    printf("%.*s\n", s.len, s.ptr);
    return 0;
}
```

```output
modules and packages
```

Several modules are imported in one line, separated by commas:

```ktm run
import cstr, mem;

i32 main() {
    u8 *copy = cstr.dup("kept");
    printf("%s %d\n", copy, cstr.len(copy));
    mem.free(copy);
    return 0;
}
```

```output
kept 4
```

A module in a subdirectory is imported with dots, and used by its last name:
`import net.http;` loads the directory `net/http`, whose files say `module http;`, and its names
are `http.get`, `http.Request`.

`import name = path;` gives the module another name in this file:

```ktm run
import s = str;

i32 main() {
    ad String greeting = s.from("aliased");
    printf("%.*s\n", greeting.len, greeting.ptr);
    return 0;
}
```

```output
aliased
```

An import that is never used is a warning, so leftover imports don't pile up.

## Writing a module

A module is a directory. Every file in it starts with `module` and the directory's name. Its
`pub` declarations are what importers see; the rest is private to the module.

```sh
shapes/
├── main.ktm
└── geo/
    ├── shapes.ktm
    └── helpers.ktm
```

```ktm
// geo/shapes.ktm
module geo;

pub struct Point { i64 x, y; };
pub enum Kind { square, circle };
pub const i64 SIZE = 4;

pub i64 area(Kind k, i64 side) {
    if (k == Kind.square) {
        return square(side);
    }
    return 3 * square(side);
}
```

```ktm
// geo/helpers.ktm
module geo;

i64 square(i64 n) {
    return n * n;
}
```

```ktm
// main.ktm
import geo;

i32 main() {
    geo.Point p = {1, 2};
    i64 cells[geo.SIZE];
    printf("%d %d %d\n", geo.area(geo.Kind.square, 5), p.y, sizeof(cells));
    return 0;
}
```

```sh
$ ktm main.ktm -o shapes && ./shapes
25 2 32
```

- Inside the module, every name of every file is visible, in any order: no prototypes, and a
  struct can hold a struct of a later file.
- Outside, only `pub` names are, with the module's name in front: `geo.area`, `geo.Point`,
  `geo.Kind.square`.
- A module's `const` works in constant expressions outside it too: `i64 cells[geo.SIZE]`.

Using a private name from outside is an error:

```sh
$ cat bad.ktm
import geo;

i32 main() {
    return geo.square(3);
}
$ ktm bad.ktm -o bad
error: `square` is private to module geo
```

## Methods across modules

Only the module that defines a type can define its methods. Outside, its `pub` methods are
called on a value with no prefix, and its private methods can't be called:

```ktm
// bank/account.ktm
module bank;
import mem;

pub struct Account {
    u8 *owner;
    i64 balance;
};

pub Account *open(u8 *owner) {
    Account *a = mem.allocZero(sizeof(Account));
    a.owner = owner;
    return a;
}

pub void Account.deposit(i64 x) {
    self.balance += x;
    self.audit();
}

void Account.audit() {
    printf("audit: %d\n", self.balance);
}

pub void Account.close() {
    mem.free(self);
}
```

```ktm
// main.ktm
import bank;

i32 main() {
    bank.Account *a = bank.open("ann");
    a.deposit(5);
    printf("%s %d\n", a.owner, a.balance);
    a.close();
    return 0;
}
```

```sh
$ ktm -jit main.ktm
audit: 5
ann 5
```

Calling `a.audit()` from `main.ktm` is an error: "the method `audit` of `bank.Account` is
private to module bank". `&bank.Account.deposit` is the method as a function pointer.

## Private fields

A field marked `private` can only be used inside the module that defines the struct:

```ktm
// chat/conn.ktm
module chat;

pub struct Conn {
    i32 fd;
    private u8 *buf;
};

pub void reset(Conn *c) {
    c.buf = null;
}
```

```ktm
// main.ktm
import chat;

i32 main() {
    chat.Conn c;
    c.fd = 3;
    chat.Conn copy = c;
    chat.reset(&c);
    printf("%d %d\n", copy.fd, sizeof(chat.Conn));
    return 0;
}
```

```sh
$ ktm -jit main.ktm
3 16
```

Writing `c.buf = null;` in `main.ktm` is an error: "`buf` is a private field of chat.Conn".

- Outside the module, reading, writing or taking the address of a private field is an error,
  and so is an initialiser list or a struct that inherits it.
- `sizeof`, copying and passing by value still work: the layout isn't hidden, only the name.
- Fields are public unless marked; `pub` on a field is an error. `private` fields exist only in
  modules.

## How modules are found

`import geo;` looks for the directory `geo`:

1. next to the main file of the program;
2. in the standard library;
3. in each directory of the `KTM_PATH` environment variable, separated by `:`.

```sh
$ KTM_PATH=$HOME/ktm-modules ktm main.ktm -o app
```

- A module found in two places is an error: give your modules names the standard library
  doesn't use.
- Imports come first in a file, before any declaration.
- A cycle of imports is an error that shows it: "circular import: a -> b -> a".
- A file whose `module` line is missing or doesn't match its directory is an error.

## Start-up order

Each module is compiled once, before the code that imports it. When the program starts, a
module's globals are set up before its importers' globals and before `main`, and a module's
files in the order of their names.

A blank import, `import _ = name;`, loads a module only for what it does when the program
starts: `import _ = net.https;` gives the HTTP client `https://` URLs. It is never reported as
unused.

## The prelude

The prelude's names are seen by every file with no import and no prefix:

- `Error`, the struct a `catch` binds (see [Errors](15-errors.html));
- `EXIT_OK` and `EXIT_FAIL`;
- the integer limits `I8_MIN`, `I8_MAX`, `U8_MAX` ... `I64_MIN`, `I64_MAX`, `U64_MAX`;
- `ERR_BOUNDS`, the code of an index out of range.

```ktm run
i32 main() {
    printf("%d %d %u\n", I32_MAX, I8_MIN, U16_MAX);
    printf("%d %d\n", EXIT_OK, EXIT_FAIL);
    return 0;
}
```

```output
2147483647 -128 65535
0 1
```

The runtime module `rt` is part of every program but internal: `import rt;` outside the standard
library is an error.

## A local with a module's name

A local variable or a parameter may have an import's name; within its scope the name is the
local:

```ktm run
import str;

i64 count(u8[] str) {
    return str.len;
}

i32 main() {
    ad String s = str.from("module again");
    printf("%d %.*s\n", count("four"), s.len, s.ptr);
    return 0;
}
```

```output
4 module again
```

A global with an import's name is an error, since it would hide the module everywhere.

## Packages

A package is a module in its own git repository, imported by its path in quotes:

```ktm
import "github.com/ozzenerol/ktm-pg";
```

Its name in the code is its `module` line, so this package is used as `pg.connect(...)`.
`import p = "github.com/user/repo";` gives it another name, and a directory of a package is a
package too: `import "github.com/user/repo/sub";`.

### ktm.mod

A project that uses packages has a `ktm.mod` file at its root. `ktm mod init` makes one:

```sh
$ mkdir hello && cd hello
$ ktm mod init example.com/me/hello
ktm: created ktm.mod for example.com/me/hello
```

```sh
module github.com/you/users-api

ktm 0.23

require (
	github.com/ozzenerol/ktm-pg v0.2.0
	github.com/someone/color v1.1.0 // indirect
)

replace github.com/someone/color => ../color
```

- `module` is the project's own path; its other directories are imported by it too
  (`import "github.com/you/users-api/store";`).
- `ktm` is the ktm version the project needs; an older compiler refuses it.
- `require` lists each package the build uses and its version; `// indirect` marks one that only
  another package needs.
- `replace` uses a local copy (a path starting with `./`, `../` or `/`) or another repository's
  version (`=> github.com/fork/color v1.1.2`).
- `ktm.sum`, next to it, holds a hash of every version's files: a download that doesn't match is
  refused, so a version can't change under you. Commit both files.

### Getting, building and running

```sh
$ ktm get github.com/ozzenerol/ktm-pg
ktm: added github.com/ozzenerol/ktm-pg v0.2.0
$ ktm build              # main.ktm of this directory, into ./users-api
$ ktm run . --port 8080  # or build and run it, with its arguments
```

A local package through `replace`, end to end:

```ktm
// ../greet/greet.ktm  (its ktm.mod says: module example.com/acme/greet)
module greet;

pub void hello(u8 *who) {
    printf("hello, %s\n", who);
}
```

```ktm
// main.ktm
import "example.com/acme/greet";

i32 main() {
    greet.hello("world");
    return 0;
}
```

```sh
$ cat ktm.mod
module example.com/me/hello

ktm 0.23

require example.com/acme/greet v0.0.0

replace example.com/acme/greet => ../greet
$ ktm build && ./hello
hello, world
$ ktm mod list
example.com/me/hello
example.com/acme/greet v0.0.0 => ../greet
```

| Command | What it does |
| --- | --- |
| `ktm mod init [path]` | a new `ktm.mod` here |
| `ktm get path@version` | adds or changes a requirement and fetches it: `@v1.2.0`, `@latest` (the default), `@none` removes it |
| `ktm mod tidy` | makes `ktm.mod` and `ktm.sum` match the imports |
| `ktm build [dir]`, `ktm run [dir] [args]` | builds (or runs) `main.ktm`, fetching what is missing |
| `ktm test [dir]`, `ktm test ./...` | runs the `*_test.ktm` tests of a directory, or of every one below |
| `ktm mod list`, `ktm mod why path` | the versions a build uses; who needs a package |
| `ktm mod download`, `ktm mod verify` | fetch into the cache; check the cache against `ktm.sum` |

### Versions

A version is a git tag `vMAJOR.MINOR.PATCH`. For each package a build uses the highest version
anything requires: a package always gets at least what it asked for, and nothing newer than
anyone asked for, so the same `ktm.mod` always gives the same build. One major version of a
package per build. A package is downloaded only when the project imports one of its directories,
directly or through another package. Fetching never runs a package's code.

To publish a package: a repository whose root has a `ktm.mod` and the module's `.ktm` files; tag a
version and push the tag (`git tag v0.1.0 && git push origin v0.1.0`). Never move a tag: `ktm.sum`
files that recorded it will refuse the new files.

### Prebuilt packages

A compiled build turns each package from the cache into a library the first time it needs it,
and later builds link that library instead of compiling the package again. Nothing has to be
done for it.

- The library is made again when anything it depends on changes: the compiler, the versions the
  program's `ktm.mod` picks, `-D` names, `-Memsafe`.
- A package replaced by a directory is always compiled from source, since it is being edited;
  so is every package of a program whose `ktm.mod` has such a replace.
- `-jit`, the REPL and builds for another `--target` compile packages from source.
- `KTM_PREBUILD=0` turns it off.

## Symbols

A module's names get symbols of their own (`geo.area` is `_K3geo4area`), so they never clash
with C functions or the program's names. A C function a module declares with `extern "c"` keeps
its C symbol (see [Calling C](26-calling-c.html)).

## Rules

- A module is a directory; each of its files starts with `module name;`.
- `pub` makes a declaration visible to importers as `module.name`; a method is called on a value
  without a prefix. Everything else is private to the module.
- Only the defining module adds methods to a type; `private` hides a field outside it.
- `import a, b;`, `import a.b;` (used as `b`), `import x = a.b;`, `import _ = a;` (blank).
- Modules are found next to the program, in the standard library, then on `KTM_PATH`.
- Import cycles, a module found twice and a global with an import's name are errors.
- Packages are imported by path in quotes; `ktm.mod` and `ktm.sum` pin their versions.
