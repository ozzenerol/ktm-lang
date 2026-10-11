import proc, test;

/* Sh and Shlurp ran fmt as-is when no arguments followed, so
 * Sh("echo '100%%'") printed 100%% and the same command meant different
 * things with and without arguments. fmt is now always formatted. */

bool is(String got, u8[] want) {
    bool ok = got.ptr && got.equals(want);
    if (!ok) { printf("got '%s' want '%s'\n", got, want); }
    got.free();
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 6;
    printf("Test - Sh and Shlurp format without arguments: ");

    if (proc.shellf("exit $(printf '%%d' 7)") == 7) { correct++; }
    if (proc.shellf("exit $(printf '%%d' %d)", 9) == 9) { correct++; }
    if (is(proc.shellOutput("echo 100%%"), "100%")) { correct++; }
    if (is(proc.shellOutput("printf '%%s-%%s' a b"), "a-b")) { correct++; }
    if (is(proc.shellOutput("echo %d%%", 5), "5%")) { correct++; }
    if (is(proc.shellOutput("echo plain"), "plain")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
