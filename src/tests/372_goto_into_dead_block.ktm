#include "testhelper.ktm"

/* A goto to a label inside a block that follows a return crashed ktm
 * (AOT and JIT): IR lowering dropped every statement after the return
 * unless it was a bare label, so the label's block stayed empty, and
 * merging that empty block freed a list head still in use. Statements
 * that hold a label are now lowered into a fresh block that nothing
 * falls into, as after a goto. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* the repro */
i64 plainBlock(i64 x) {
    if (x) { goto inner; }
    return 1;
    {
inner:
        x--;
    }
    return x;
}

/* the label sits in an if whose condition is never tested */
i64 ifBlock(i64 x) {
    if (x) { goto inner; }
    return 1;
    if (x > 5) {
inner:
        x--;
    }
    return x * 10;
}

/* into the middle of a loop body: the loop goes on as usual */
i64 intoLoop(i64 x) {
    i64 n = 0;
    if (x) { goto inner; }
    return -1;
    while (x > 0) {
inner:
        n++;
        x--;
    }
    return n;
}

/* two blocks deep */
i64 nested(i64 x) {
    if (x) { goto deep; }
    return 0;
    {
        if (x > 100) {
deep:
            x += 1;
        }
    }
    return x;
}

/* from inside a switch */
i64 fromSwitch(i64 x) {
    switch (x) {
        case 1: return 10;
        default: goto in;
    }
    return 0;
    {
in:
        x *= 3;
    }
    return x;
}

/* an empty labelled block after a return */
i64 gCount = 0;
void emptyBlock(i64 x) {
    if (x) { goto end; }
    gCount++;
    return;
    {
end:
    }
}

/* a dead block without a label is still dropped */
i64 noLabel(i64 x) {
    return 1;
    {
        x++;
    }
    return x;
}

i32 main() {
    printf("Test - goto into dead block: ");

    check(plainBlock(0) == 1 && plainBlock(5) == 4, "plain block");
    check(ifBlock(0) == 1 && ifBlock(5) == 40 && ifBlock(9) == 80, "if block");
    check(intoLoop(0) == -1 && intoLoop(3) == 3, "into a loop body");
    check(nested(0) == 0 && nested(7) == 8, "two blocks deep");
    check(fromSwitch(1) == 10 && fromSwitch(4) == 12, "from a switch");
    emptyBlock(0);
    emptyBlock(1);
    check(gCount == 1, "empty labelled block");
    check(noLabel(3) == 1, "dead block without a label");

    PrintResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
