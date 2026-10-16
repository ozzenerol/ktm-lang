# Terminal output

Make a command-line program's output easy to read with the standard library's `term` module:
colors, lines that say what happened, tables whose columns line up, and a progress bar. This
guide shows each one, then puts them together in a small report. Every step is a whole program
you can build and run.

## What you need

Any ktm from v0.27.0. `term` is part of the standard library, so there is nothing to fetch.
Every program in this guide is one file, `app.ktm`, built and run like this:

```sh
ktm app.ktm -o app
./app
```

Run the programs in a terminal to see the colors. The outputs below are what a pipe or a file
gets: the same text, without colors. The next section but one says why.

## Colors

`term.print` is `printf` with colors. Put the words of a color in square brackets before the
text, and `[/]` after it:

```ktm run
import term;

i32 main() {
    term.print("[green]passed[/] 12 tests\n");
    term.print("[bold red]failed[/] 2 tests\n");
    term.print("[yellow]skipped[/] %d tests in %s\n", 3, "net/http");
    term.print("[dim]took 1.4s[/]\n");
    return 0;
}
```

```output
passed 12 tests
failed 2 tests
skipped 3 tests in net/http
took 1.4s
```

In a terminal, `passed` is green, `failed` bold and red, `skipped` yellow and the last line dim.

| Words | What they do |
| --- | --- |
| `black`, `red`, `green`, `yellow`, `blue`, `magenta`, `cyan`, `white`, `gray` | the text's color |
| `on red`, `on blue`, ... (any color) | the background |
| `bold`, `dim`, `italic`, `underline`, `reverse`, `strike` | the style |

- A tag can hold several words: `[bold red]`, `[white on blue]`, `[underline cyan]`.
- `[/]` closes the last tag that is still open, so tags can nest:
  `[bold]total: [green]ok[/] in 3s[/]` keeps `in 3s` bold.
- A tag still open at the end of the text is closed there, so a color never runs into the next
  line.
- `%d`, `%s` and the rest work as in `printf`. The values are written as they are, never read
  as markup: a file called `[red]` prints as `[red]`.
- Square brackets around anything else stay as they are: `[1, 2]`, `[ok]`. To write a word of
  the markup in brackets, double the opening one: `[[red]` prints `[red]`.
- `term.eprint` is the same for stderr.

## Lines that say what happened

Four calls write one line each, after a symbol in its color. They add the newline:

```ktm run
import term;

i32 main() {
    term.title("Deploying shop v2.4.0");
    term.info("building %d services", 3);
    term.success("api is up");
    term.success("web is up");
    term.warn("worker took %ds to start", 41);
    term.error("can't reach %s", "db.internal:5432");
    return 0;
}
```

```output
Deploying shop v2.4.0
• building 3 services
✓ api is up
✓ web is up
! worker took 41s to start
✗ can't reach db.internal:5432
```

| Call | Symbol | Where |
| --- | --- | --- |
| `term.success(fmt, ...)` | a green `✓` | stdout |
| `term.info(fmt, ...)` | a blue `•` | stdout |
| `term.warn(fmt, ...)` | a yellow `!` | stderr |
| `term.error(fmt, ...)` | a red `✗` | stderr |
| `term.title(fmt, ...)` | none: the line is bold | stdout |

Warnings and errors go to stderr, so `./app > report.txt` keeps them on the screen and the
report clean. The message can hold markup too: `term.error("can't reach [bold]%s[/]", host)`.

## When there are no colors

Colors are escape codes: bytes a terminal reads as "turn green". In a file they are noise, so
`term` only writes them to a terminal. For each of stdout and stderr, colors are:

- on when it is a terminal;
- off when it is a pipe or a file (`./app | less`, `./app > out.txt`);
- off when the `NO_COLOR` environment variable is set, which is the
  [standard way](https://no-color.org) for a user to turn them off everywhere;
- off when `TERM` is `dumb`, as in some editors' terminals;
- on when `FORCE_COLOR` is set (and isn't `0`), for CI logs that show colors.

With colors off, the markup is removed and the text stays, as in the outputs on this page. A
program can decide itself with `term.setColors`, and ask with `term.colors()`:

```ktm run
import term;

i32 main() {
    term.print("colors on stdout: %s\n", term.colors() ? "yes" : "no");

    term.setColors(true);
    String ok = term.format("[green]ok[/]");
    printf("with colors, \"ok\" takes %d bytes\n", ok.len);
    ok.free();

    term.setColors(false);
    term.print("[green]never green[/]\n");
    return 0;
}
```

```output
colors on stdout: no
with colors, "ok" takes 11 bytes
never green
```

- The output is a pipe here, so `term.colors()` says no. It answers for stdout.
- `term.setColors(true)` turns colors on for stdout and stderr, whatever they are. The two
  letters then take 11 bytes: 5 to turn green, 2 for `ok` and 4 to turn back. Use it for a
  `--color` flag that the user asked for, and not otherwise.
- `term.setColors(false)` turns them off, even in a terminal.

## Tables

`term.table` takes the column headers. `t.row` adds a row, and `t.print` writes the table with
its columns lined up:

```ktm run
import term;

i32 main() {
    term.Table *t = term.table("SERVICE", "STATUS", "CPU %", "MEMORY MB");
    t.row("api", "[green]running[/]", 12.5, 512);
    t.row("web", "[green]running[/]", 3.25, 128);
    t.row("worker", "[red]stopped[/]", 0, 0);
    t.print();
    t.free();
    return 0;
}
```

```output
SERVICE  STATUS   CPU %  MEMORY MB
api      running   12.5        512
web      running   3.25        128
worker   stopped      0          0
```

- The headers are bold in a terminal.
- A cell can be text, a whole number, a number with a fraction, a `bool`, an enum (its member's
  name) or a `String`. Numbers are written as `printf`'s `%g` writes them.
- A column whose cells are all numbers lines up on the right, so the digits line up. Any other
  column lines up on the left.
- Text cells can hold markup. The colors don't count in the width, so the columns still line up.
- Columns are two spaces apart, and no line ends in spaces.
- `t.free()` frees the table and its cells.

### Lining up and borders

`t.align` puts one column on the left, the right or the center. `t.border` draws lines around
the cells, or makes a Markdown table to paste into a README or an issue:

```ktm run
import term;

enum Plan { free, team, business };

i32 main() {
    term.Table *t = term.table("CUSTOMER", "PLAN", "SEATS", "ACTIVE");
    t.row("Acme", Plan.business, 120, true);
    t.row("Globex", Plan.team, 14, true);
    t.row("Initech", Plan.free, 1, false);
    t.align(1, term.Align.center);

    t.border = term.Border.box;
    t.print();

    t.border = term.Border.markdown;
    t.print();
    t.free();
    return 0;
}
```

```output
╭──────────┬──────────┬───────┬────────╮
│ CUSTOMER │   PLAN   │ SEATS │ ACTIVE │
├──────────┼──────────┼───────┼────────┤
│ Acme     │ business │   120 │ true   │
│ Globex   │   team   │    14 │ true   │
│ Initech  │   free   │     1 │ false  │
╰──────────┴──────────┴───────┴────────╯
| CUSTOMER |   PLAN   | SEATS | ACTIVE |
| -------- | -------: | ----: | ------ |
| Acme     | business |   120 | true   |
| Globex   |   team   |    14 | true   |
| Initech  |   free   |     1 | false  |
```

- Columns count from 0: `t.align(1, ...)` is `PLAN`.
- `term.Align` is `left`, `right` or `center`.
- `term.Border` is `none` (the default), `box` or `markdown`. A Markdown table never has
  colors, and its second line says how each column lines up.

### A table without a header, and a table as text

`term.table()` with no headers makes a table without a header line: a list of names and values.
`t.format()` gives the table as a `String` instead of printing it, to write it to a file or put
it in a message:

```ktm run
import term;

i32 main() {
    term.Table *t = term.table();
    t.row("version", "2.4.0");
    t.row("commit", "a91f3c2");
    t.row("built", "2026-10-16");

    String text = t.format();
    printf("%d bytes:\n%.*s", text.len, text.len, text.ptr);
    text.free();
    t.free();
    return 0;
}
```

```output
52 bytes:
version  2.4.0
commit   a91f3c2
built    2026-10-16
```

`t.format()` has no colors. `t.format(true)` keeps them, for a String that goes to the terminal.
`t.rows()` says how many rows were added.

## A progress bar

`term.progress` takes a label and the number of steps. `p.add()` says one more step is done, and
`p.done()` ends the bar and frees it:

```ktm run
import term;

i32 main() {
    i64 files = 240;
    term.Progress *p = term.progress("copying", files);
    for (i64 i = 0; i < files; i++) {
        /* copy one file here */
        p.add();
    }
    p.done();
    term.success("copied %d files", files);
    return 0;
}
```

```output
✓ copied 240 files
copying  ██████████████████████████████  100%  240/240  0.0s
```

In a terminal, the bar is drawn on one line and redrawn in place as the work goes on:

```
copying  ███████████████░░░░░░░░░░░░░░░   50%  120/240
```

At `p.done()` it is drawn full, with the time the work took, and the program goes on below it.

- The bar goes to stderr, like the warnings, so stdout stays for the program's real output.
- It is redrawn when its percent changes, or after 100 ms, so a loop of a million steps costs
  about a hundred redraws, not a million.
- Lines written with `term.print`, `term.info` and the others while the bar runs go above it,
  and the bar is drawn again under them.
- In a pipe or a file there is nothing to redraw, so only the last line is written, at
  `p.done()`. That is why it comes after the `✓` line above: stdout and stderr are two streams,
  and this page shows stdout first.
- `p.add(n)` adds `n` steps, and `p.set(n)` says how many are done in all.

## Putting it together

A program that checks a few services and reports on them, with each part of `term` where it
fits:

```ktm run
import term;

struct Service {
    u8 *name;
    u8 *host;
    i64 ms;         /* how long it took to answer; -1: no answer */
};

i32 main() {
    Service services[5] = {
        {"api", "api.internal:8080", 12},
        {"web", "web.internal:80", 48},
        {"search", "search.internal:9200", 730},
        {"db", "db.internal:5432", -1},
        {"cache", "cache.internal:6379", 2},
    };

    term.title("Checking %d services", 5);
    term.Progress *p = term.progress("checking", 5);
    for (i64 i = 0; i < 5; i++) {
        /* ask services[i] here */
        p.add();
    }
    p.done();

    term.Table *t = term.table("SERVICE", "HOST", "STATUS", "MS");
    i64 down = 0, slow = 0;
    for (i64 i = 0; i < 5; i++) {
        Service *s = &services[i];
        if (s.ms < 0) {
            t.row(s.name, s.host, "[red]down[/]", "-");
            down++;
        } else if (s.ms > 500) {
            t.row(s.name, s.host, "[yellow]slow[/]", s.ms);
            slow++;
        } else {
            t.row(s.name, s.host, "[green]up[/]", s.ms);
        }
    }
    t.align(3, term.Align.right);
    t.print();
    t.free();

    if (slow) {
        term.warn("%d service answered slowly", slow);
    }
    if (down) {
        term.error("%d service is down", down);
        return 1;
    }
    term.success("all services are up");
    return 0;
}
```

```output
Checking 5 services
SERVICE  HOST                  STATUS   MS
api      api.internal:8080     up       12
web      web.internal:80       up       48
search   search.internal:9200  slow    730
db       db.internal:5432      down      -
cache    cache.internal:6379   up        2
checking  ██████████████████████████████  100%  5/5  0.0s
! 1 service answered slowly
✗ 1 service is down
```

- The `MS` column holds a `-` for the service that didn't answer. That makes it a text column,
  which would line up on the left, so `t.align(3, term.Align.right)` keeps it on the right.
- The program ends with exit code 1 when a service is down, so a script or a CI job can tell.

## Reference

| Call | What it does |
| --- | --- |
| `term.print(fmt, ...)` | `printf` to stdout, with markup in `fmt` |
| `term.eprint(fmt, ...)` | the same, to stderr |
| `term.format(fmt, ...)` | the same, into a new `String` (colors as stdout has them) |
| `term.success`, `term.info` | one line on stdout after a green `✓` or a blue `•` |
| `term.warn`, `term.error` | one line on stderr after a yellow `!` or a red `✗` |
| `term.title(fmt, ...)` | one bold line on stdout |
| `term.colors()` | does stdout get colors? |
| `term.setColors(on)` | colors on or off for stdout and stderr, whatever they are |
| `term.width(text)` | the columns `text` takes on a terminal, without its markup |
| `term.table(headers...)` | a new table; no headers for a table without a header line |
| `t.row(cells...)` | one row: text, numbers, bools, enums or Strings |
| `t.align(col, a)` | `term.Align.left`, `right` or `center` for column `col` (from 0) |
| `t.border` | `term.Border.none`, `box` or `markdown` |
| `t.print()` | writes the table to stdout |
| `t.format(colors)` | the table as a new `String`; `colors` is `false` when left out |
| `t.rows()`, `t.free()` | how many rows; frees the table |
| `term.progress(label, total)` | a progress bar on stderr |
| `p.add(n)`, `p.set(n)` | `n` more steps done (1 when left out); `n` steps done in all |
| `p.done()` | the last line, with the time taken; frees the bar |

The whole module is in the [library reference](../std/#term.print).

## Rules of thumb

- **Color a word, not a line.** `[red]failed[/] 2 tests` is easier to read than a whole red line.
- **Give each color one meaning** and keep it: green for done, yellow for a warning, red for an
  error. Color only adds to the text: `failed` still has to say it, for the people and the
  files that don't see the color.
- **Results to stdout, everything about them to stderr.** The `term` calls already do this:
  tables and `success` lines are output, warnings, errors and the progress bar are not.
- **Never force colors on.** Leave the choice to `term` and the user's `NO_COLOR`, unless the user
  asked for colors with a flag.
- **Put numbers in a table as numbers**, not as text, so they line up on the right.
