import proc, test;

/* A lone `;` in a function body ended the body: the next statement was
 * "Syntax error ... perhaps you meant `}`?". `case 1: ;` and
 * `default: ;` crashed the compiler, and `for (i = 0; i < n; i++) ;`
 * (an empty body) was dropped whole, init included. The empty statement
 * now works everywhere a statement can be, without swallowing the next
 * one. */

i64 gCalls = 0;
void hit() { gCalls++; }

i64 atStart() { ; return 1; }
i64 inMiddle() { i64 x = 2; ; x++; ;; x++; return x; }
void atEnd() { hit(); ; }
void onlyEmpty() { ; ;; }
i64 nested() {
    i64 n = 0;
    { ; }
    { ; n++; ; }
    { { ;; } n++; }
    return n;
}

i64 emptyBodies() {
    i64 i = 0, n = 0;
    if (1) {}
    if (0) { n += 100; } else {}
    while (i++ < 3) {}
    n += i;                       /* 4 */
    for (i = 0; i < 5; i++) {}
    n += i;                       /* 5 */
    i64 j = 0;
    do {} while (j++ < 2);
    n += j;                       /* 3 */
    return n;
}

i64 inSwitch(i64 v) {
    i64 r = 0;
    switch (v) {
        case 1: ; r = 10;
        case 2: ; ; break;
        case 3: r = 30; ;
        default: ; r = -1;
    }
    return r;
}

i64 afterLabel() {
    i64 n = 0;
    goto L;
    n = 99;
L: ;
    n++;
    return n;
}

i32 main() {
    printf("Test - empty statement in a body: ");
    i64 correct = 0, tests = 9;

    if (atStart() == 1) { correct++; }
    if (inMiddle() == 4) { correct++; }
    atEnd();
    onlyEmpty();
    if (gCalls == 1) { correct++; }
    if (nested() == 2) { correct++; }
    if (emptyBodies() == 12) { correct++; }
    if (inSwitch(1) == 10 && inSwitch(2) == 0 && inSwitch(3) == 30 &&
            inSwitch(7) == -1) { correct++; }
    if (afterLabel() == 1) { correct++; }

    /* `;;` right after a declaration doesn't take the next statement */
    i64 k = 5;;
    ;;
    k++;
    if (k == 6) { correct++; }

    /* The REPL takes them too */
    if (proc.shell("printf 'i64 x = 2;\\n;\\n;;\\nprintf(\"%%d\\\\n\", x + 1);\\n' | "
                         "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                         "-repl 2>&1 | grep -qx '3'") == 0) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
