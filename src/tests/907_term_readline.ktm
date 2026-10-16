import fs, proc, str, term, test;

/* term.readLine (v0.28.0): the history (add, duplicates, the limit, a file
 * read back and appended to), lines from a pipe, and editing on a terminal
 * under script(1): moving, inserting, Ctrl-A/E/W/C, Backspace, Up through
 * the history, Ctrl-D to end. util-linux and BSD script take their
 * arguments differently. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

#define RUN "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) sh __907_run.sh"

i32 main() {
    printf("Test - term.readLine: ");

    term.historyAdd("one");
    term.historyAdd("one");
    term.historyAdd("");
    term.historyAdd("two");
    check(term.historyLen() == 2, "duplicates and empty lines are left out");
    check(str.equals(term.historyAt(0), "one") && str.equals(term.historyAt(1), "two"), "historyAt");
    check(str.equals(term.historyAt(5), ""), "historyAt past the end");
    term.historyMax(1);
    check(term.historyLen() == 1 && str.equals(term.historyAt(0), "two"), "historyMax drops the oldest");
    term.historyMax(1000);
    term.historyClear();
    check(term.historyLen() == 0, "historyClear");

    fs.writeAll("__907_hist", "a\nb\r\n\nc\n");
    term.historyFile("__907_hist");
    check(term.historyLen() == 3 && str.equals(term.historyAt(1), "b"), "historyFile reads the lines");
    term.historyAdd("d");
    scoped String saved = fs.readAll("__907_hist");
    check(str.equals(saved[0:], "a\nb\r\n\nc\nd\n"), "historyAdd appends to the file");
    term.historyClear();
    fs.remove("__907_hist");

    fs.writeAll("__907_prog.ktm",
        "import term;\n"
        "i32 main() {\n"
        "    scoped String line;\n"
        "    while (term.readLine(\"> \", &line)) {\n"
        "        printf(\"got [%.*s]\\n\", line.len, line.ptr);\n"
        "    }\n"
        "    printf(\"bye %d\\n\", term.historyLen());\n"
        "    return 0;\n"
        "}\n");
    scoped String built = proc.shellOutput("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                           "-o __907_prog __907_prog.ktm 2>&1");
    check(built.len == 0, "the program compiles");

    scoped String piped = proc.shellOutput("printf 'one\\ntwo\\r\\nthree' | ./__907_prog");
    check(str.equals(piped[0:], "> got [one]\n> got [two]\n> got [three]\n> bye 3"),
          "lines from a pipe, the last without a newline");
    if (!str.equals(piped[0:], "> got [one]\n> got [two]\n> got [three]\n> bye 3")) {
        printf("  got: [%.*s]\n", piped.len, piped.ptr);
    }

    /* typed on a terminal: hello; then junk dropped by Ctrl-C, edits; then
     * Up Up gives hello again; Ctrl-D */
    fs.writeAll("__907_run.sh",
        "keys() { sleep 1; printf 'hello\\r'; sleep 0.5;\n"
        "  printf 'junk\\003wor\\033[D\\033[DX\\001Z\\005! tail\\027ab\\177c\\r'; sleep 0.5;\n"
        "  printf '\\033[A\\033[A\\r'; sleep 0.5; printf '\\004'; sleep 0.5; }\n"
        "keys | if script -V >/dev/null 2>&1; then script -qfec ./__907_prog /dev/null;\n"
        "else script -q /dev/null ./__907_prog; fi\n");
    scoped String typed = proc.shellOutput(RUN " 2>&1");
    bool order = false;
    i64 a = str.find(typed[0:], "got [hello]");
    i64 b = str.find(typed[0:], "got [ZwXor! ac]");
    if (a >= 0 && b > a) {
        order = str.find(typed[b:], "got [hello]") > 0 && str.find(typed[b:], "bye 3") > 0;
    }
    check(order, "editing keys and the history on a terminal");
    if (!order) {
        printf("  got: [%.*s]\n", typed.len, typed.ptr);
    }

    fs.remove("__907_run.sh");
    fs.remove("__907_prog.ktm");
    fs.remove("__907_prog");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
