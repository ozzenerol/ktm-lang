import test;

enum Err { neg, in };

/* A return, break, continue or goto that left a try body never popped
 * its catch frame, so the next throw longjmp'd into the stack of a
 * function that had already returned (segfault or endless loop). */

i64 Depth() {
    if (!Fs()) { return 0; }
    i64 n = 0;
    CatchFrame *f = Fs().catchTop;
    while (f) {
        n++;
        f = f.prev;
    }
    return n;
}

void boom(i64 v) {
    throw(v);
}

i64 risky(i64 x) {
    if (x < 0) { throw(Err.neg); }
    return x + 1;
}

i64 retInTry(i64 x) {
    try {
        if (x > 0) { return x * 2; }
    } catch {
        return -1;
    }
    return -2;
}

i64 retComputed(i64 x) {
    try {
        return risky(x) * 3;
    } catch (Error e) {
        return -e.code;
    }
    return 0;
}

i64 retNested(i64 x) {
    try {
        try {
            try {
                if (x == 1) { return 100; }
            } catch {
            }
            if (x == 2) { return 200; }
        } catch {
        }
    } catch {
    }
    return 300;
}

i64 retFromCatch() {
    try {
        try {
            throw(Err.in);
        } catch {
            return 7;
        }
    } catch {
        return -7;
    }
    return 0;
}

i64 retInLoopInTry(i64 n) {
    i64 i;
    try {
        for (i = 0; i < 10; i++) {
            try {
                if (i == n) { return i; }
            } catch {
            }
        }
    } catch {
    }
    return -1;
}

i64 breakLoops() {
    i64 i, total = 0;
    for (i = 0; i < 1000; i++) {
        try {
            if (i % 2) { continue; }
            total++;
        } catch {
        }
    }
    i = 0;
    while (1) {
        try {
            if (++i == 5) { break; }
        } catch {
        }
    }
    total += i;
    i = 0;
    do {
        try {
            try {
                if (++i == 3) { break; }
            } catch {
            }
        } catch {
        }
    } while (1);
    total += i;
    return total;
}

i64 switchBreak(i64 x) {
    i64 r = 0;
    switch (x) {
        case 1:
            try {
                r = 1;
                break;
            } catch {
            }
            r = 99;
            break;
        default:
            r = 2;
    }
    return r;
}

i64 breakInsideTry() {
    i64 i, r = 0;
    try {
        for (i = 0; i < 5; i++) {
            if (i == 2) { break; }
        }
        switch (i) {
            case 2:
                r = 1;
                break;
            default:
                r = 0;
        }
        boom('Mine');
    } catch (Error e) {
        if (e.code == 'Mine') { r += 10; }
    }
    return r;
}

i64 gotos() {
    i64 r = 0, i = 0;
    try {
        try {
            goto out;
        } catch {
        }
    } catch {
    }
out:
    r += Depth();
    try {
        try {
            goto mid;
        } catch {
        }
mid:
        r += Depth() * 10;
        boom('Mid');
    } catch (Error e) {
        if (e.code == 'Mid') { r += 100; }
    }
    try {
again:
        i++;
        if (i < 3) { goto again; }
        boom('Loop');
    } catch (Error e) {
        if (e.code == 'Loop') { r += 1000 * i; }
    }
    return r;
}

i32 main() {
    printf("Test - jumping out of a try body pops its catch frame: ");
    i64 correct = 0, tests = 16, base = Depth();

    if (retInTry(5) == 10 && Depth() == base) { correct++; }
    if (retInTry(0) == -2 && Depth() == base) { correct++; }
    if (retComputed(4) == 15 && Depth() == base) { correct++; }
    if (retComputed(-1) == -Err.neg && Depth() == base) { correct++; }
    if (retNested(1) == 100 && retNested(2) == 200 && retNested(3) == 300 &&
            Depth() == base) { correct++; }
    if (retFromCatch() == 7 && Depth() == base) { correct++; }
    if (retInLoopInTry(4) == 4 && Depth() == base) { correct++; }
    if (breakLoops() == 500 + 5 + 3 && Depth() == base) { correct++; }
    if (switchBreak(1) == 1 && switchBreak(0) == 2 && Depth() == base) { correct++; }
    if (breakInsideTry() == 11 && Depth() == base) { correct++; }
    if (gotos() == 0 + 10 + 100 + 3000 && Depth() == base) { correct++; }

    /* After all of that a throw reaches the live try, not a dead frame. */
    i64 caught = 0, i;
    for (i = 0; i < 100; i++) {
        retInTry(1);
        breakLoops();
        try {
            boom(i);
        } catch (Error e) {
            if (e.code == i) { caught++; }
        }
    }
    if (caught == 100) { correct++; }

    i64 outer = 0;
    try {
        retInTry(3);
        retNested(1);
        gotos();
        boom('Out');
    } catch (Error e) {
        outer = e.code;
    }
    if (outer == 'Out') { correct++; }

    /* A return inside the outer try leaves only the inner frames. */
    i64 got = 0;
    try {
        if (retInLoopInTry(2) == 2) { boom('Ok'); }
    } catch (Error e) {
        got = e.code;
    }
    if (got == 'Ok') { correct++; }

    if (Depth() == base) { correct++; }
    if (retComputed(9) == 30) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
