import cstr, fs, libc, mem, proc, str, test;

/* switch on strings (v0.18.0): a u8[] or a String is matched against
 * string literal cases, byte for byte and case-sensitive; `default`,
 * shared arms and `fallthrough` work as in an int switch, the value is
 * read once, and a case string given twice is a compile error. */

#define SW_SRC "./ktm-717-sw.ktm"
#define SW_OUT "./ktm-717-sw.out"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s", what); }
}

i64 command(u8[] cmd) {
    switch (cmd) {
        case "GET": return 1;
        case "SET": return 2;
        case "DEL":
        case "REMOVE": return 3;
        case "": return 4;
        case "GETX": return 5;
        default: return 0;
    }
}

i64 owned(String s) {
    i64 r = -1;
    switch (s) {
        case "ping": r = 10;
        case "a\nb": r = 11;
        case "\xff": r = 12;
        case "Ping": r = 13;
    }
    return r;
}

i64 gReads = 0;
u8[] nextWord() {
    gReads++;
    return "two";
}

i64 falls(u8[] w) {
    i64 n = 0;
    switch (w) {
        case "one": n += 1; fallthrough;
        case "two": n += 10;
        default: n += 100;
    }
    return n;
}

u8 *diagnose(u8 *source) {
    proc.shell("rm -f " SW_SRC " " SW_OUT);
    fs.writeAll(SW_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-S " SW_SRC " -o /dev/null > " SW_OUT " 2>&1");
    u8 *out = fs.readAll(SW_OUT).take();
    proc.shell("rm -f " SW_SRC " " SW_OUT);
    return out;
}

/* compiling `source` gives an error containing `want` */
bool reports(u8 *source, u8 *want) {
    u8 *out = diagnose(source);
    bool ok = out && libc.strstr(out, "error") && libc.strstr(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", source, out); }
    mem.free(out);
    return ok;
}

i32 main()
{
    printf("Test - switch on strings: ");

    check(command("GET") == 1 && command("SET") == 2, "u8[] cases");
    check(command("DEL") == 3 && command("REMOVE") == 3, "two cases share an arm");
    check(command("") == 4 && command("GETX") == 5, "empty case, longer case");
    check(command("GE") == 0 && command("get") == 0 && command("GETXY") == 0 &&
          command("SEt") == 0, "default: prefix, case and length differ");
    u8[] line = "SET key";
    check(command(line[0:3]) == 2 && command(line[4:7]) == 0, "a sub-slice");

    String s = str.from("ping");
    check(owned(s) == 10, "String value");
    s.free();
    s = str.from("Ping");
    check(owned(s) == 13, "case-sensitive");
    s.free();
    s = str.from("a\nb");
    check(owned(s) == 11, "escapes in a case");
    s.free();
    s = str.from("\xff");
    check(owned(s) == 12, "a byte over 127");
    s.free();
    s = str.from("pong");
    check(owned(s) == -1, "no case and no default: nothing runs");
    s.free();

    i64 hit = 0;
    switch (nextWord()) {
        case "one": hit = 1;
        case "two": hit = 2;
        case "three": hit = 3;
    }
    check(hit == 2 && gReads == 1, "the value is read once");

    check(falls("one") == 11 && falls("two") == 10 && falls("x") == 100, "fallthrough and default");

    i64 nested = 0;
    for (i64 i = 0; i < 2; i++) {
        switch (i) {
            case 0:
                u8[] w = "in";
                switch (w) {
                    case "in": nested += 1;
                    case "out": nested += 100;
                }
            case 1: nested += 10;
        }
    }
    check(nested == 11, "inside an int switch");

    check(reports("i32 main() { u8[] c = \"a\"; switch (c) { case \"x\": return 1; case \"x\": return 2; } return 0; }\n",
                  "duplicate case value \"x\""), "duplicate string case");
    check(reports("i32 main() { u8[] c = \"a\"; switch (c) { case 1: return 1; } return 0; }\n",
                  "a string literal"), "int case in a string switch");
    check(reports("i32 main() { i64 c = 1; switch (c) { case \"x\": return 1; } return 0; }\n",
                  "`case` must be followed by an integer constant"), "string case in an int switch");
    check(reports("i32 main() { u8[] c = \"a\"; switch (c) { case \"a\" ... \"z\": return 1; } return 0; }\n",
                  "range"), "range in a string switch");
    check(reports("i32 main() { i64 *c = null; switch (c) { case 1: return 1; } return 0; }\n",
                  "Switch can only have"), "other types still rejected");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
