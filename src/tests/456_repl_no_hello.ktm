import cstr, fs, libc, proc, test;

/* `#define KTM_NO_REPL_HELLO` in ~/.ktm_rc.ktm hides the REPL's welcome
 * message, as the docs say (the REPL checked KTM_REPL_NO_HELLO). The
 * greeting is only for a terminal, so the REPL runs under script(1):
 * util-linux's or BSD's, which take their arguments differently. */

#define SH_FILE "__456_run.sh"

#define RUN "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) sh " SH_FILE

i32 main() {
    i64 correct = 0, tests = 2;
    printf("Test - #define KTM_NO_REPL_HELLO hides the REPL greeting: ");

    u8 *sh = "d=ktm-456; rm -rf $d; mkdir $d\n"
             "[ \"$1\" = quiet ] && echo '#define KTM_NO_REPL_HELLO' > $d/.ktm_rc.ktm\n"
             "k=\"../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -repl\"\n"
             "(sleep 2; printf '\\004'; sleep 1) | if script -V >/dev/null 2>&1; then\n"
             "  HOME=$d script -qfec \"$k\" /dev/null\n"
             "else\n"
             "  HOME=$d script -q /dev/null $k\n"
             "fi\n"
             "rm -rf $d\n";
    fs.writeAll(SH_FILE, cstr.view(sh));

    /* without the define the greeting shows: the REPL did get a terminal */
    u8 *out = proc.shellOutput(RUN " loud 2>&1").take();
    if (out && libc.strstr(out, "Ctrl-D exits")) { correct++; }
    else { printf("\n  no define: [%s]\n", out); }
    out = proc.shellOutput(RUN " quiet 2>&1").take();
    if (out && libc.strstr(out, ">>>") && !libc.strstr(out, "Ctrl-D exits")) { correct++; }
    else { printf("\n  with the define: [%s]\n", out); }
    fs.remove(SH_FILE);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
