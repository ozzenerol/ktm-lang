# Command-line flags

Read flags like `--port 9000` and `-v` from the command line with the standard library's `flag`
module. This guide builds one program, `serve`, a step at a time: one flag, then the four kinds
of flag and their short names, the ways to write them, the arguments that aren't flags, checking
values, a fallback to an environment variable and your own error messages. The last section
builds a second program with subcommands, like `git commit`. Every step is a whole program you
can build and run.

## What you need

Any ktm. `flag` is part of the standard library, so there is nothing to fetch. Every `serve`
program in this guide is one file, `serve.ktm`, built like this:

```sh
ktm serve.ktm -o serve
```

The programs print the settings they were given instead of serving anything, so the guide stays
about flags.

## The first flag

```ktm
import flag;

i32 main(i32 argc, u8 **argv) {
    flag.Set *fs = flag.new("serve", "serves the files of a directory over HTTP");
    i64 *port = fs.integer("port", 8080, "the port to listen on");
    fs.parseOrExit(argc, argv);

    printf("listening on port %d\n", *port);
    return 0;
}
```

```sh
ktm serve.ktm -o serve
./serve
./serve --port 9000
```

```output
listening on port 8080
listening on port 9000
```

- `flag.new` makes a `flag.Set`: the program's name, used in the help, and one line about what
  it does.
- `fs.integer` defines `--port`: its name without dashes, its default, and a line for the help.
  It returns a pointer to the value. Read it after parsing, as `*port`.
- `fs.parseOrExit` reads `argv`. When the command line is right it returns, and `*port` holds
  the value given, or the default when `--port` wasn't there.
- `main` takes `argc` and `argv` to have a command line to pass on.

Every program gets `-h` and `--help` without defining them. `parseOrExit` prints the help to
stdout and ends the program with exit code 0:

```sh
./serve -h
```

```output
usage: serve [flags] [arguments]

serves the files of a directory over HTTP

flags:
      --port int  the port to listen on (default 8080)
  -h, --help      show this help
```

A mistake ends it too, with what is wrong and the help on stderr, and exit code 2:

```sh
./serve --port eighty
echo $?
```

```output
serve: --port: `eighty` isn't a whole number

usage: serve [flags] [arguments]

serves the files of a directory over HTTP

flags:
      --port int  the port to listen on (default 8080)
  -h, --help      show this help
2
```

## Four kinds of flag

A flag holds a whole number, a number with a fraction, text, or on/off. Each kind has its own
call, and each takes a one-letter short name as its last argument:

```ktm
import flag;

i32 main(i32 argc, u8 **argv) {
    flag.Set *fs = flag.new("serve", "serves the files of a directory over HTTP");
    i64 *port = fs.integer("port", 8080, "the port to listen on", 'p');
    u8[] *host = fs.string("host", "127.0.0.1", "the address to listen on");
    f64 *timeout = fs.number("timeout", 30, "seconds to wait for a slow client", 't');
    bool *verbose = fs.boolean("verbose", false, "log every request", 'v');
    fs.parseOrExit(argc, argv);

    printf("listening on %.*s:%d\n", host.len, host.ptr, *port);
    printf("timeout %gs, verbose %s\n", *timeout, *verbose ? "on" : "off");
    return 0;
}
```

```sh
ktm serve.ktm -o serve
./serve
./serve -p 9000 --host 0.0.0.0 -t 2.5 -v
```

```output
listening on 127.0.0.1:8080
timeout 30s, verbose off
listening on 0.0.0.0:9000
timeout 2.5s, verbose on
```

The help lists every flag, with its short name, its kind and its default:

```sh
./serve -h
```

```output
usage: serve [flags] [arguments]

serves the files of a directory over HTTP

flags:
  -p, --port int        the port to listen on (default 8080)
      --host text       the address to listen on (default "127.0.0.1")
  -t, --timeout number  seconds to wait for a slow client (default 30)
  -v, --verbose         log every request
  -h, --help            show this help
```

| Call | Returns | The value given must be |
| --- | --- | --- |
| `fs.integer(name, def, usage, short)` | `i64 *` | a whole number: `9000`, `-5` |
| `fs.number(name, def, usage, short)` | `f64 *` | a number: `2.5`, `30`, `1e3` |
| `fs.string(name, def, usage, short)` | `u8[] *` | anything |
| `fs.boolean(name, def, usage, short)` | `bool *` | nothing: the flag alone turns it on |

- The short name is optional: `--host` has none, so it is only `--host`.
- `host.len` and `host.ptr` reach through the pointer, like the fields of a struct pointer.
- The text a string flag holds points into `argv`, or is the default. There is nothing to free.
- The pointers stay valid as long as the Set, which is the whole program here.
- A flag defined twice, or a name that starts with a dash, throws when the program starts: a
  mistake in the program, not on the command line.

## Ways to write a flag

The same program understands the forms of Go's `flag` package and of GNU tools, so people can
type what they are used to:

```sh
./serve --port=9000 --host=0.0.0.0
./serve -p9000 -t0.5
./serve -port 9000 -verbose
./serve -v --verbose=false
```

```output
listening on 0.0.0.0:9000
timeout 30s, verbose off
listening on 127.0.0.1:9000
timeout 0.5s, verbose off
listening on 127.0.0.1:9000
timeout 30s, verbose on
listening on 127.0.0.1:8080
timeout 30s, verbose off
```

| Form | Example |
| --- | --- |
| `--name value` | `--port 9000` |
| `--name=value` | `--port=9000` |
| `-name value`, `-name=value` (Go's) | `-port 9000` |
| `-s value` | `-p 9000` |
| `-svalue` | `-p9000` |
| `--name`, `-s` for an on/off flag | `--verbose`, `-v` |
| `--name=false` to turn one off | `--verbose=false` |
| `-abc` for several on/off flags | `-vq` for `-v -q` |

- An on/off flag takes `true`, `false`, `1`, `0`, `yes` or `no` after an `=`. Without the `=` it
  takes nothing: `--verbose false` turns it on and leaves `false` as an argument.
- A flag given twice keeps the last value: `-v --verbose=false` is off.
- Only on/off flags group: `-vp 9000` is a mistake. Write `-v -p 9000`.
- A value can start with a dash: `--port -5` gives `-5`, which the next section's checks turn
  away.

## Arguments that aren't flags

Whatever on the command line isn't a flag or a flag's value is an argument. `fs.nargs()` says how
many there are, and `fs.arg(i)` gives each one. Here they are the directories to serve, and `.`
when there are none:

```ktm
import flag;

i32 main(i32 argc, u8 **argv) {
    flag.Set *fs = flag.new("serve", "serves the files of a directory over HTTP");
    i64 *port = fs.integer("port", 8080, "the port to listen on", 'p');
    u8[] *host = fs.string("host", "127.0.0.1", "the address to listen on");
    f64 *timeout = fs.number("timeout", 30, "seconds to wait for a slow client", 't');
    bool *verbose = fs.boolean("verbose", false, "log every request", 'v');
    fs.parseOrExit(argc, argv);

    printf("listening on %.*s:%d\n", host.len, host.ptr, *port);
    printf("timeout %gs, verbose %s\n", *timeout, *verbose ? "on" : "off");
    if (fs.nargs() == 0) {
        printf("serving .\n");
    }
    for (i64 i = 0; i < fs.nargs(); i++) {
        u8[] dir = fs.arg(i);
        printf("serving %.*s\n", dir.len, dir.ptr);
    }
    return 0;
}
```

```sh
ktm serve.ktm -o serve
./serve public --port 9000 assets
```

```output
listening on 127.0.0.1:9000
timeout 30s, verbose off
serving public
serving assets
```

Flags and arguments can come in any order. `--` ends the flags: everything after it is an
argument, even when it starts with a dash:

```sh
./serve -- -drafts
```

```output
listening on 127.0.0.1:8080
timeout 30s, verbose off
serving -drafts
```

- `fs.arg(i)` past the end gives `""`, so `fs.arg(0)` is safe to read when an argument is
  optional.
- A lone `-` is an argument, the usual name for stdin.

## Checking values and $PORT

`flag` checks that a value has the right kind, not that it makes sense: `--port 70000` is a whole
number. Check the values after parsing and stop with a message when one is wrong.

`fs.given("port")` says whether `--port` (or `-p`) was on the command line. It tells a default
apart from the same value typed by hand, which is what a fallback needs. Here `--port` wins over
the `PORT` environment variable, and `PORT` over the default:

```ktm
import cstr, flag, libc, os, proc, str;

/* Says what is wrong on stderr and ends the program with exit code 2, the
 * code parseOrExit uses for a mistake */
void usageError(u8 *fmt, ...args) {
    u8 *message = cstr.formatArgs(null, fmt, args);
    libc.fprintf(libc.stderr, "serve: %s\nrun `serve -h` for help\n", message);
    os.exit(2);
}

i32 main(i32 argc, u8 **argv) {
    flag.Set *fs = flag.new("serve", "serves the files of a directory over HTTP");
    i64 *port = fs.integer("port", 8080, "the port to listen on, or $PORT", 'p');
    u8[] *host = fs.string("host", "127.0.0.1", "the address to listen on");
    f64 *timeout = fs.number("timeout", 30, "seconds to wait for a slow client", 't');
    bool *verbose = fs.boolean("verbose", false, "log every request", 'v');
    fs.parseOrExit(argc, argv);

    /* --port wins over $PORT, and $PORT over the default */
    scoped String env = proc.getenv("PORT");
    if (!fs.given("port") && env.len) {
        if (!str.toInt(env[0:], port)) {
            usageError("$PORT: `%.*s` isn't a whole number", env.len, env.ptr);
        }
    }
    if (*port < 1 || *port > 65535) {
        usageError("the port must be between 1 and 65535, not %d", *port);
    }
    if (*timeout <= 0) {
        usageError("--timeout must be more than 0 seconds");
    }

    printf("listening on %.*s:%d\n", host.len, host.ptr, *port);
    printf("timeout %gs, verbose %s\n", *timeout, *verbose ? "on" : "off");
    if (fs.nargs() == 0) {
        printf("serving .\n");
    }
    for (i64 i = 0; i < fs.nargs(); i++) {
        u8[] dir = fs.arg(i);
        printf("serving %.*s\n", dir.len, dir.ptr);
    }
    return 0;
}
```

```sh
ktm serve.ktm -o serve
PORT=3000 ./serve
PORT=3000 ./serve -p 9000
```

```output
listening on 127.0.0.1:3000
timeout 30s, verbose off
serving .
listening on 127.0.0.1:9000
timeout 30s, verbose off
serving .
```

```sh
./serve -p 70000
echo $?
PORT=abc ./serve
./serve -t 0
```

```output
serve: the port must be between 1 and 65535, not 70000
run `serve -h` for help
2
serve: $PORT: `abc` isn't a whole number
run `serve -h` for help
serve: --timeout must be more than 0 seconds
run `serve -h` for help
```

- `str.toInt` writes the number straight into the flag's value, through the same pointer.
- The help says where else the value can come from (`or $PORT`). It is the only place a user
  will look.
- The checks run after `parseOrExit`, so `-h` still prints the help when `PORT` is wrong.

## Your own error messages

`parseOrExit` prints the whole help for every mistake. The checks above print one line and point
to `-h` instead. To make the flag mistakes look the same, call `fs.parse`, which returns `false`
instead of ending the program, and decide what to do:

```ktm
import cstr, flag, libc, os, proc, str;

/* Says what is wrong on stderr and ends the program with exit code 2, the
 * code for a mistake */
void usageError(u8 *fmt, ...args) {
    u8 *message = cstr.formatArgs(null, fmt, args);
    libc.fprintf(libc.stderr, "serve: %s\nrun `serve -h` for help\n", message);
    os.exit(2);
}

i32 main(i32 argc, u8 **argv) {
    flag.Set *fs = flag.new("serve", "serves the files of a directory over HTTP");
    i64 *port = fs.integer("port", 8080, "the port to listen on, or $PORT", 'p');
    u8[] *host = fs.string("host", "127.0.0.1", "the address to listen on");
    f64 *timeout = fs.number("timeout", 30, "seconds to wait for a slow client", 't');
    bool *verbose = fs.boolean("verbose", false, "log every request", 'v');
    if (!fs.parse(argc, argv)) {
        if (fs.helpAsked()) {
            String help = fs.usage();
            printf("%.*s", help.len, help.ptr);
            help.free();
            return 0;
        }
        u8[] why = fs.error();
        usageError("%.*s", why.len, why.ptr);
    }

    /* --port wins over $PORT, and $PORT over the default */
    scoped String env = proc.getenv("PORT");
    if (!fs.given("port") && env.len) {
        if (!str.toInt(env[0:], port)) {
            usageError("$PORT: `%.*s` isn't a whole number", env.len, env.ptr);
        }
    }
    if (*port < 1 || *port > 65535) {
        usageError("the port must be between 1 and 65535, not %d", *port);
    }
    if (*timeout <= 0) {
        usageError("--timeout must be more than 0 seconds");
    }

    printf("listening on %.*s:%d\n", host.len, host.ptr, *port);
    printf("timeout %gs, verbose %s\n", *timeout, *verbose ? "on" : "off");
    if (fs.nargs() == 0) {
        printf("serving .\n");
    }
    for (i64 i = 0; i < fs.nargs(); i++) {
        u8[] dir = fs.arg(i);
        printf("serving %.*s\n", dir.len, dir.ptr);
    }
    return 0;
}
```

```sh
ktm serve.ktm -o serve
./serve --port eighty
./serve --colour
./serve -p
```

```output
serve: --port: `eighty` isn't a whole number
run `serve -h` for help
serve: unknown flag --colour
run `serve -h` for help
serve: -p needs a value
run `serve -h` for help
```

- `fs.parse` returns `true` when the command line is right. When it isn't, `fs.error()` says why.
- `-h` and `--help` make `fs.parse` return `false` too, with `fs.helpAsked()` true. Asking for
  help isn't a mistake: print it to stdout and exit 0.
- `fs.usage()` returns the help as a new `String`. Free it when you are done.

## Subcommands

A tool that does several jobs, like `git commit` and `git push`, gives each job a subcommand with
flags of its own. `fs.command` makes one: a `flag.Set` of its own, with its own flags and
arguments. After parsing, `fs.chosen()` says which one was on the command line. This is a second
program, `site.ktm`:

```ktm
import flag, libc;

i32 main(i32 argc, u8 **argv) {
    flag.Set *fs = flag.new("site", "builds and serves a static site");
    bool *verbose = fs.boolean("verbose", false, "say more", 'v');

    flag.Set *build = fs.command("build", "writes the site into a directory");
    u8[] *out = build.string("out", "dist", "the directory to write", 'o');
    bool *minify = build.boolean("minify", false, "shrink the HTML and CSS");

    flag.Set *serve = fs.command("serve", "serves the site while you edit it");
    i64 *port = serve.integer("port", 8080, "the port to listen on", 'p');

    fs.parseOrExit(argc, argv);

    flag.Set *cmd = fs.chosen();
    if (cmd == build) {
        printf("building into %.*s, minify %s\n", out.len, out.ptr, *minify ? "on" : "off");
    } else if (cmd == serve) {
        printf("serving on port %d\n", *port);
        for (i64 i = 0; i < serve.nargs(); i++) {
            u8[] page = serve.arg(i);
            printf("watching %.*s\n", page.len, page.ptr);
        }
    } else {
        String help = fs.usage();
        libc.fprintf(libc.stderr, "%.*s", help.len, help.ptr);
        help.free();
        return 2;
    }
    if (*verbose) {
        printf("done\n");
    }
    return 0;
}
```

```sh
ktm site.ktm -o site
./site build
./site -v build -o public --minify
./site serve -p 3000 index.md about.md
```

```output
building into dist, minify off
building into public, minify on
done
serving on port 3000
watching index.md
watching about.md
```

The program's help lists the subcommands, and each subcommand has a help of its own:

```sh
./site -h
./site serve -h
```

```output
usage: site [flags] <command> [arguments]

builds and serves a static site

commands:
  build  writes the site into a directory
  serve  serves the site while you edit it

flags:
  -v, --verbose  say more
  -h, --help     show this help
usage: site serve [flags] [arguments]

serves the site while you edit it

flags:
  -p, --port int  the port to listen on (default 8080)
  -h, --help      show this help
```

A flag of another subcommand is a mistake, and the help shown is the subcommand's:

```sh
./site build --port 1
```

```output
site: unknown flag --port

usage: site build [flags] [arguments]

writes the site into a directory

flags:
  -o, --out text  the directory to write (default "dist")
      --minify    shrink the HTML and CSS
  -h, --help      show this help
```

- The program's own flags, here `-v`, go before the subcommand's name. Everything after the name
  belongs to the subcommand: `./site build -v` is an unknown flag.
- The first word that isn't a flag must be a subcommand's name: `./site deploy` stops with
  an unknown command error and the help.
- `fs.chosen()` is `null` when no subcommand was given. This program prints the help to stderr
  and exits 2 then.
- A subcommand's arguments are its own: `serve.nargs()` and `serve.arg(i)`.
- `fs.free()` frees the Set, its subcommands and every value. The pointers the flags returned
  dangle after it, so a program that keeps its flags until it ends never needs to call it.

## Reference

| Call | What it does |
| --- | --- |
| `flag.new(name, about)` | a new Set for a program called `name` |
| `fs.integer(name, def, usage, short)` | a whole-number flag; returns an `i64 *` |
| `fs.number(name, def, usage, short)` | a number flag; returns an `f64 *` |
| `fs.string(name, def, usage, short)` | a text flag; returns a `u8[] *` |
| `fs.boolean(name, def, usage, short)` | an on/off flag; returns a `bool *` |
| `fs.command(name, about)` | a subcommand: a Set of its own |
| `fs.parseOrExit(argc, argv)` | parses; `-h` prints the help and exits 0, a mistake exits 2 |
| `fs.parse(argc, argv)` | parses; `false` on a mistake or `-h` |
| `fs.parseFrom(argc, argv, from)` | parses from `argv[from]` instead of `argv[1]` |
| `fs.error()` | why `parse` returned `false` |
| `fs.helpAsked()` | was `-h` or `--help` given? |
| `fs.usage()` | the help, as a new `String` |
| `fs.given(name)` | was the flag on the command line? |
| `fs.nargs()`, `fs.arg(i)` | the arguments that aren't flags; `""` past the end |
| `fs.chosen()` | the subcommand given, or `null` |
| `fs.free()` | frees the Set, its subcommands and the values |

The whole module is in the [library reference](../std/#flag.Set).

## Rules of thumb

- **Give every flag a default that works.** A program run with no flags at all should do the
  common thing.
- **Give short names to the flags people type often**, and only to those. Long names are what
  scripts should use: they still read well a year later.
- **Write the usage line for the help.** Say what the value is and its unit (`seconds to wait`),
  not the flag's name again.
- **Check values right after parsing**, before the program does any work, and exit 2 with one line
  that says what is wrong.
- **Help goes to stdout with exit code 0; a mistake goes to stderr with exit code 2.** Then
  `serve -h | less` works, and scripts can tell a mistake from a failure.
