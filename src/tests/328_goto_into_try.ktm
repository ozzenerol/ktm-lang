import fs, libc, mem, proc, str, test;

enum Err { done };

/* A goto into a try body from outside it (or from its catch handler)
 * skipped KTM_PushFrame, and the body's normal exit then popped a frame
 * that was never pushed. It is a compile error now; jumps inside a try
 * body and out of it stay legal. */

#define GOTO_TRY_FILE "__328_goto_into_try.ktm"
#define GOTO_TRY_OUT  "__328_goto_into_try.out"

/* A throw now reaches the nearest live catch (a frame left behind by a
 * jump would take it somewhere else) */
bool throwLands() {
    i64 got = 0;
    try {
        throw(77);
    } catch (Error e) {
        got = e.code;
    }
    return got == 77;
}

i64 withinBody(i64 x) {
    i64 n = 0;
    try {
        if (x) { goto skip; }
        n += 1;
skip:
        n += 10;
        if (n < 30) { goto skip; }
    } catch {
        n = -1;
    }
    return n;
}

i64 outOfBody(i64 x) {
    i64 n = 0;
    try {
        if (x) { goto out; }
        n = 1;
    } catch {
        n = -1;
    }
    return n;
out:
    return 100;
}

i64 innerToOuter() {
    i64 n = 0;
    try {
        try {
            goto outer_label;
        } catch {
        }
        n = -1;
outer_label:
        n += 1;
    } catch {
        n = -2;
    }
    return n;
}

i64 intoCatchHandler(i64 x) {
    i64 n = 0;
    if (x) { goto handler; }
    try {
        n = 1;
    } catch {
handler:
        n += 50;
    }
    return n;
}

i64 throwAfterJumps() {
    i64 n = 0;
    try {
        withinBody(1);
        outOfBody(1);
        innerToOuter();
        throw(Err.done);
    } catch {
        n = 1;
    }
    return n;
}

void cleanupGotoTry(void) {
    fs.remove(GOTO_TRY_FILE);
    fs.remove(GOTO_TRY_OUT);
    fs.remove("./a.out");
}

bool compileFailsWith(u8 *src, u8 *expected) {
    u8 *out;
    i64 res;
    bool ok;

    cleanupGotoTry();
    if (!fs.writeAll(GOTO_TRY_FILE, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " GOTO_TRY_FILE " > " GOTO_TRY_OUT " 2>&1");
    out = fs.readAll(GOTO_TRY_OUT);
    ok = res != 0 && out && libc.strstr(out, expected) != null;
    if (!ok && out) { printf("%s\n", out); }
    mem.free(out);
    cleanupGotoTry();
    return ok;
}

i32 main() {
    printf("Test - goto into try: ");
    i64 correct = 0, tests = 0;

    tests++; if (withinBody(0) == 31 && withinBody(1) == 30) { correct++; }
    tests++; if (outOfBody(0) == 1 && outOfBody(1) == 100) { correct++; }
    tests++; if (innerToOuter() == 1) { correct++; }
    tests++; if (intoCatchHandler(0) == 1 && intoCatchHandler(1) == 50) { correct++; }
    tests++; if (throwAfterJumps() == 1 && throwLands()) { correct++; }
    i64 before = test.heapInUse();
    for (i64 i = 0; i < 20000; i++) {
        withinBody(i & 1);
        outOfBody(i & 1);
        innerToOuter();
        intoCatchHandler(i & 1);
        throwAfterJumps();
    }
    tests++; if (test.heapInUse() - before < 64 * 1024 && throwLands()) { correct++; }

    tests++;
    if (compileFailsWith(
"void main()
{
  i64 x = 1;
  if (x) { goto inside; }
  try {
inside:
    printf(\"in\\n\");
  } catch {
  }
}
", "`goto inside` jumps into a try body")) { correct++; }

    tests++;
    if (compileFailsWith(
"void main()
{
  try {
    throw(0x41);
again:
    printf(\"again\\n\");
  } catch {
    goto again;
  }
}
", ":8:")) { correct++; }

    tests++;
    if (compileFailsWith(
"void main()
{
  i64 i = 0;
  try {
    printf(\"x\\n\");
back:
    i++;
  } catch {
  }
  if (i < 3) { goto back; }
}
", "`goto back` jumps into a try body")) { correct++; }

    tests++;
    if (compileFailsWith(
"void main()
{
  try {
    goto deeper;
    try {
      while (1) {
        switch (1) {
          case 1:
deeper:
            break;
        }
        break;
      }
    } catch {
    }
  } catch {
  }
}
", "`goto deeper` jumps into a try body")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
