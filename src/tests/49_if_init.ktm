#include "testhelper.ktm"

/* C++17-style `if` with an init-statement and/or a declaration used
 * directly as the condition: `if (init; cond)`. The declarations are
 * scoped to the `if` (its then and else bodies). */

struct Foo { u32 x; };

i32 main() {
    i64 tests = 9, correct = 0;

    /* plain if is unchanged */
    if (1) { correct++; }
    if (0) {} else { correct++; }

    /* a declaration used as the condition */
    if (i64 a = 5) { correct++; }            /* a = 5 is truthy */
    if (i64 b = 0) {} else { correct++; }     /* b = 0 is falsy  */

    /* init-statement followed by a separate condition expression */
    if (i64 c = 10; c > 3) { correct++; }

    /* init-statement followed by a declaration-condition (the if.cpp shape) */
    Foo foo;
    if (Foo *f = &foo; i64 x = 32) {
        f.x = x;
        if (f.x == 32) { correct++; }
    }

    /* an expression init-statement (not a declaration) */
    i64 g = 0;
    if (g = 7; g == 7) { correct++; }

    /* else body also sees the if-scoped declaration */
    if (i64 d = 0; d) { correct++; } else if (d == 0) { correct++; }

    /* the init var is scoped to the if: reusing the name afterwards is fine */
    i64 a = 99;
    if (a == 99) { correct++; }

    printf("Test - if with initialiser (`if (init; cond)`):\n");
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
