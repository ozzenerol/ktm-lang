import str, term, test;

/* term (v0.27.0): markup to escape codes or removed, [[ and unknown
 * brackets kept, [/] closing the last tag, values never read as markup,
 * width without markup, and tables (plain, box, Markdown; numbers on the
 * right, align, cells of any type, no header). */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

void expect(String got, u8[] want, u8 *what) {
    check(str.equals(got[0:], want), what);
    if (!str.equals(got[0:], want)) {
        printf("  got:  [%.*s]\n  want: [%.*s]\n", got.len, got.ptr, want.len, want.ptr);
    }
    got.free();
}

enum State { running, stopped };

i32 main() {
    printf("Test - term: ");

    term.setColors(false);
    check(!term.colors(), "setColors(false)");
    expect(term.format("[green]ok[/] %s", "[red]x[/]"), "ok [red]x[/]", "markup removed, values kept");
    expect(term.format("[[a] [1, 2] [nope] [/] [bold"), "[a] [1, 2] [nope] [/] [bold", "brackets that aren't markup");
    expect(term.format("[on red]x[/] [red on]y"), "x [red on]y", "on needs a color after it");
    expect(term.format("%d%% [bold]%s[/]", 5, "z"), "5% z", "formats after the markup");

    term.setColors(true);
    check(term.colors(), "setColors(true)");
    expect(term.format("[green]ok[/]"), "\x1b[32mok\x1b[0m", "a color");
    expect(term.format("[bold red]a[/]"), "\x1b[1;31ma\x1b[0m", "two words");
    expect(term.format("[white on blue]a[/]"), "\x1b[37;44ma\x1b[0m", "a background");
    expect(term.format("[gray]a[/][on gray]b[/]"), "\x1b[90ma\x1b[0m\x1b[100mb\x1b[0m", "gray");
    expect(term.format("[bold]a [red]b[/] c[/]"), "\x1b[1ma \x1b[31mb\x1b[0m\x1b[1m c\x1b[0m", "[/] closes the last tag");
    expect(term.format("[underline]open"), "\x1b[4mopen\x1b[0m", "an open tag is closed at the end");

    check(term.width("[red]héllo[/] [[x]") == 9, "width");

    term.Table *t = term.table("NAME", "STATE", "CPU", "MB", "UP");
    String name = str.from("cache");
    t.row("web", "[green]ok[/]", 12.5, 512, true);
    t.row("db", State.stopped, 0.25, 2048, false);
    t.row(name, State.running, 100, 64, true);
    check(t.rows() == 3, "rows");
    expect(t.format(),
           "NAME   STATE     CPU    MB  UP\n"
           "web    ok       12.5   512  true\n"
           "db     stopped  0.25  2048  false\n"
           "cache  running   100    64  true\n", "a plain table");
    expect(t.format(true),
           "\x1b[1mNAME\x1b[0m   \x1b[1mSTATE\x1b[0m     \x1b[1mCPU\x1b[0m    \x1b[1mMB\x1b[0m  \x1b[1mUP\x1b[0m\n"
           "web    \x1b[32mok\x1b[0m       12.5   512  true\n"
           "db     stopped  0.25  2048  false\n"
           "cache  running   100    64  true\n", "a plain table in color");
    t.border = term.Border.box;
    expect(t.format(),
           "╭───────┬─────────┬──────┬──────┬───────╮\n"
           "│ NAME  │ STATE   │  CPU │   MB │ UP    │\n"
           "├───────┼─────────┼──────┼──────┼───────┤\n"
           "│ web   │ ok      │ 12.5 │  512 │ true  │\n"
           "│ db    │ stopped │ 0.25 │ 2048 │ false │\n"
           "│ cache │ running │  100 │   64 │ true  │\n"
           "╰───────┴─────────┴──────┴──────┴───────╯\n", "a box");
    t.border = term.Border.markdown;
    t.align(1, term.Align.center);
    expect(t.format(true),
           "| NAME  |  STATE  |  CPU |   MB | UP    |\n"
           "| ----- | ------: | ---: | ---: | ----- |\n"
           "| web   |   ok    | 12.5 |  512 | true  |\n"
           "| db    | stopped | 0.25 | 2048 | false |\n"
           "| cache | running |  100 |   64 | true  |\n", "Markdown, no colors, a centered column");
    t.free();
    name.free();

    term.Table *k = term.table();
    k.row("name", "ktm");
    k.row("version", 1, "extra");
    k.align(0, term.Align.right);
    expect(k.format(), "   name  ktm\nversion  1    extra\n", "no header, a longer row");
    k.free();

    term.Table *e = term.table("A");
    expect(e.format(), "A\n", "only a header");
    e.free();

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
