#include "testhelper.ktm"

import fs, libc, mem, proc, str;

enum Err { two };

/* `continue` inside a switch crashed the compiler ("null param"): the
 * switch pushed a loop context with no continue target. It must go to
 * the enclosing loop. */

#define CONT_SWITCH_FILE "__327_continue_switch.ktm"
#define CONT_SWITCH_OUT  "__327_continue_switch.out"

i64 catchDepth() {
    if (!Fs()) { return 0; }
    i64 n = 0;
    CatchFrame *f = Fs().catchTop;
    while (f) {
        n++;
        f = f.prev;
    }
    return n;
}

i64 forSwitch() {
    i64 i, n = 0;
    for (i = 0; i < 4; i++) {
        switch (i) {
            case 1: continue;
            fallthrough;
            default: n += 10;
        }
        n++;
    }
    return n;
}

i64 nestedSwitch() {
    i64 i, n = 0;
    for (i = 0; i < 6; i++) {
        switch (i & 1) {
            case 0:
                switch (i) {
                    case 2: continue;
                    fallthrough;
                    default: n += 100;
                }
                break;
            case 1:
                n += 1;
                break;
        }
        n += 1000;
    }
    return n;
}

i64 whileSwitch() {
    i64 i = 0, n = 0;
    while (i < 5) {
        i++;
        switch (i) {
            case 2:
            case 4:
                continue;
        }
        n += i;
    }
    return n;
}

i64 doWhileSwitch() {
    i64 i = 0, n = 0;
    do {
        i++;
        switch (i) {
            case 3: continue;
            fallthrough;
            default: break;
        }
        n += i;
    } while (i < 5);
    return n;
}

i64 breakStaysInSwitch() {
    i64 i, n = 0;
    for (i = 0; i < 3; i++) {
        switch (i) {
            case 0: break;
            case 1: continue;
            fallthrough;
            default: break;
        }
        n++;
    }
    return n;
}

i64 tryAroundSwitch() {
    i64 i, n = 0;
    for (i = 0; i < 4; i++) {
        try {
            switch (i) {
                case 1: continue;
                fallthrough;
                case 2: throw(Err.two);
                default: n += 10;
            }
            n++;
        } catch {
            n += 100;
        }
    }
    return n;
}

i64 switchAroundTry() {
    i64 i, n = 0;
    for (i = 0; i < 4; i++) {
        switch (i) {
            case 1:
                try {
                    try {
                        continue;
                    } catch {
                    }
                } catch {
                }
                fallthrough;
            default: n += 10;
        }
        n++;
    }
    return n;
}

i64 loopInsideTry() {
    i64 i, n = 0;
    try {
        for (i = 0; i < 4; i++) {
            switch (i) {
                case 1: continue;
                fallthrough;
                default: n += catchDepth();
            }
        }
    } catch {
        n = -1;
    }
    return n;
}

void cleanupContinueSwitch(void) {
    fs.remove(CONT_SWITCH_FILE);
    fs.remove(CONT_SWITCH_OUT);
    fs.remove("./a.out");
}

bool continueWithoutLoopFails() {
    u8 *src = "void main()\n{\n  i64 i = 1;\n  switch (i) {\n    case 1: continue;\n  }\n}\n";
    u8 *out;
    i64 res;
    bool ok;

    cleanupContinueSwitch();
    if (!fs.writeAll(CONT_SWITCH_FILE, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " CONT_SWITCH_FILE " > " CONT_SWITCH_OUT " 2>&1");
    out = fs.readAll(CONT_SWITCH_OUT);
    ok = res != 0 && out && libc.strstr(out, "continue, not inside a loop") &&
             !libc.strstr(out, "NULL param");
    if (!ok && out) { printf("%s\n", out); }
    mem.free(out);
    cleanupContinueSwitch();
    return ok;
}

i32 main() {
    printf("Test - continue in switch: ");
    i64 correct = 0, tests = 0;

    tests++; if (forSwitch() == 33) { correct++; }
    tests++; if (nestedSwitch() == 5203) { correct++; }
    tests++; if (whileSwitch() == 9) { correct++; }
    tests++; if (doWhileSwitch() == 12) { correct++; }
    tests++; if (breakStaysInSwitch() == 2) { correct++; }
    tests++; if (tryAroundSwitch() == 122) { correct++; }
    tests++; if (catchDepth() == 0) { correct++; }
    tests++; if (switchAroundTry() == 33) { correct++; }
    tests++; if (catchDepth() == 0) { correct++; }
    tests++; if (loopInsideTry() == 3) { correct++; }
    tests++; if (catchDepth() == 0) { correct++; }
    tests++; if (continueWithoutLoopFails()) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
