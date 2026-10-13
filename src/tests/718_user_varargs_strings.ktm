import cstr, fs, libc, mem, proc, str, test;

/* A program's own `u8 *fmt, ...args` function takes a String and a u8[]
 * for %s as printf and log.* do (it was "a String can't be passed as an
 * untyped extra argument"); with a computed format a String still goes,
 * as its C string, and any `...args` function gets a String that way. */

#define PROG "__718_prog.ktm"
#define OUT  "__718_prog.out"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

String gLast;

void reply(u8 *fmt, ...args)
{
    u8 *text = cstr.formatArgs(null, fmt, args);
    gLast.free();
    gLast = str.from(cstr.view(text));
    mem.free(text);
}

/* a method too, after a fixed argument */
struct Out { i64 n; };
void Out.say(i64 code, u8 *fmt, ...args)
{
    u8 *text = cstr.formatArgs(null, fmt, args);
    gLast.free();
    gLast = str.format("%d %s", code, text);
    mem.free(text);
    self.n++;
}

/* no format: the extras as they come */
u8 *firstArg(...args) { return (u8 *)args[0]; }

String made() { return str.from("made"); }

bool lastIs(u8 *want) {
    bool ok = str.equals(gLast, cstr.view(want));
    if (!ok) { printf("\n  got [%s] want [%s]", gLast.cstr(), want); }
    return ok;
}

bool compileFails(u8 *src, u8 *want) {
    fs.writeAll(PROG, cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    i64 rc = proc.shellf("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -S -o- %s > %s 2>&1", PROG, OUT);
    String out = fs.readAll(OUT);
    bool ok = rc != 0 && out.ptr != null && libc.strstr(out.ptr, want) != null;
    if (!ok) { printf("\n  got [%.*s]", out.len, out.ptr); }
    out.free();
    fs.remove(PROG);
    fs.remove(OUT);
    return ok;
}

i32 main()
{
    printf("Test - user ...args functions take String and u8[]: ");

    ad String name = str.format("users_%d", 2);
    ad String empty = str.new();
    u8[] view = "orders and more";
    view = view[0:6];

    reply("table %s", name);
    check(lastIs("table users_2"), "a String for %s");
    reply("view %s, %d rows", view, 7);
    check(lastIs("view orders, 7 rows"), "a u8[] view, only its own bytes");
    reply("[%s|%s]", empty, view[1:3]);
    check(lastIs("[|rd]"), "an empty String, a sub-slice");
    reply("%s!", made());
    check(lastIs("made!"), "a call's String, evaluated once");

    u8 *fmt = "computed %s %d";
    reply(fmt, name, 3);
    check(lastIs("computed users_2 3"), "a String with a computed format");
    reply(fmt, empty, 4);
    check(lastIs("computed  4"), "an empty String with a computed format");

    Out o;
    o.n = 0;
    o.say(200, "%s %s", name, view);
    check(lastIs("200 users_2 orders") && o.n == 1, "a method after a fixed argument");

    check(!libc.strcmp(firstArg(name), "users_2"), "no format: a String is its C string");
    check(!libc.strcmp(firstArg(empty), ""), "no format: an empty String is \"\"");
    gLast.free();

    check(compileFails("void reply(u8 *fmt, ...args) { }\nvoid main() { u8 *f = \"%s\"; u8[] v = \"x\"; reply(f, v); }\n",
                       "needs a literal format"),
          "a u8[] needs a literal format");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
