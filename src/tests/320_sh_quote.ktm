import cstr, mem, proc, test;

/* proc.shellQuote(cstr.view(s)).take() single-quotes s for sh, so text can go into Sh and Shlurp
 * commands as one word with nothing expanded. Each string goes through
 * Shlurp("printf '%%s.' %s", q) and must come back unchanged (the "."
 * keeps Shlurp from dropping a trailing newline). */

extern "c" u8 *mkdtemp(u8 *template);
extern "c" i32 access(u8 *path, i32 mode);

bool roundTrip(u8 *s) {
    u8 *q = proc.shellQuote(cstr.view(s)).take();
    u8 *got = proc.shellOutput("printf '%%s.' %s", q).take();
    u8 *want = cstr.formatTo(null, "%s.", s);
    bool ok = got && !cstr.cmp(got, want);
    if (!ok) { printf("round trip of [%s] via [%s]: got [%s]\n", s, q, got); }
    mem.free(q);
    mem.free(got);
    mem.free(want);
    return ok;
}

bool quoteIs(u8 *s, u8 *want) {
    u8 *q = proc.shellQuote(cstr.view(s)).take();
    bool ok = !cstr.cmp(q, want);
    if (!ok) { printf("ShQuote: got [%s] want [%s]\n", q, want); }
    mem.free(q);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 8, i, bad;
    u8 dir[64], path[128], all[256], *q, *cmd;
    printf("Test - ShQuote: ");

    if (quoteIs("abc", "'abc'") && quoteIs("", "''") && quoteIs(null, "''") &&
            quoteIs("it's", "'it'\\''s'") && quoteIs("''", "''\\'''\\'''")) {
        correct++;
            }

    if (roundTrip("hello world") && roundTrip("  two  spaces ") &&
            roundTrip("") && roundTrip("-n") && roundTrip("%s %d %%")) {
        correct++;
            }

    if (roundTrip("it's") && roundTrip("'") && roundTrip("''") &&
            roundTrip("'a'b'") && roundTrip("\"double\" quotes") &&
            roundTrip("mixed '\"' quotes")) { correct++; }

    /* Nothing is expanded: variables, command substitution, globs, ~ */
    if (roundTrip("$HOME") && roundTrip("${HOME}") && roundTrip("`echo hi`") &&
            roundTrip("$(echo hi)") && roundTrip("*") && roundTrip("~") &&
            roundTrip("a;b|c&d>e<f") && roundTrip("!$?#")) { correct++; }

    if (roundTrip("\\") && roundTrip("back\\slash\\") && roundTrip("\\'") &&
            roundTrip("a\nb") && roundTrip("trailing newline\n") &&
            roundTrip("\n\n") && roundTrip("tab\there")) { correct++; }

    /* Every byte but NUL */
    for (i = 1; i < 256; i++) { all[i - 1] = i; }
    all[255] = '\0';
    if (roundTrip(all)) { correct++; }

    /* A string that would run a command if it weren't quoted */
    cstr.copy(dir, "./ktm-shquote-XXXXXX");
    if (mkdtemp(dir)) {
        cstr.formatTo(path, "%s/pwned", dir);
        u8 *evil = cstr.formatTo(null, "x'; touch %s; echo '$(touch %s)`touch %s`",
                                                path, path, path);
        q = proc.shellQuote(cstr.view(evil)).take();
        i64 st = proc.shellf("printf '%%s' %s > /dev/null", q);
        if (st == 0 && roundTrip(evil) && access(path, 0) != 0) { correct++; }
        else { printf("command ran: status %d\n", st); }
        mem.free(q);
        mem.free(evil);
        proc.shellf("rm -rf '%s'", dir);
    } else {
        printf("mkdtemp failed\n");
    }

    /* One word to the shell, spaces and all */
    q = proc.shellQuote("a b  c").take();
    cmd = cstr.formatTo(null, "test %s = 'a b  c'", q);
    bad = proc.shellf(cmd);
    if (bad == 0 && proc.shellf("set -- %s; test $# -eq 1", q) == 0) { correct++; }
    else { printf("Sh with a quoted word: %d\n", bad); }
    mem.free(cmd);
    mem.free(q);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
