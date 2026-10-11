#include "testhelper.ktm"

/* Global symbols whose name begins with an uppercase `L` (e.g. `LIGHTGRAY`)
 * must still assemble + link. On macOS/Mach-O the assembler treats any symbol
 * starting with `L` as a local (assembler-temporary) label, so emitting a
 * bare `.globl LIGHTGRAY` failed with "non-local symbol required". The fix
 * escapes such data-global symbols with a leading `_` consistently at the
 * definition and every reference. This test just has to compile, link and
 * read/write these globals correctly. */

struct Color { u8 r, g, b, a; };

Color LIGHTGRAY = {200, 200, 200, 255};   /* class global, array-init */
i64   lArr[3]   = {10, 20, 30};            /* array global */
i64   lscalar   = 42;                      /* scalar global */
u8   *lstr      = "hello";                 /* string-pointer global */

bool classGlobal() {
    bool ok = LIGHTGRAY.r == 200 && LIGHTGRAY.g == 200 &&
                        LIGHTGRAY.b == 200 && LIGHTGRAY.a == 255;
    LIGHTGRAY.g = 99;                         /* write a field */
    ok = ok && LIGHTGRAY.g == 99;
    LIGHTGRAY.g = 200;                        /* restore */
    return ok;
}

bool arrayGlobal() {
    bool ok = lArr[0] == 10 && lArr[1] == 20 && lArr[2] == 30;
    lArr[1] = 77;
    ok = ok && lArr[1] == 77;
    lArr[1] = 20;
    return ok;
}

bool scalarGlobal() {
    bool ok = lscalar == 42;
    lscalar = 7;
    ok = ok && lscalar == 7;
    lscalar = 42;
    return ok;
}

bool stringGlobal() {
    return lstr[0] == 'h' && lstr[4] == 'o';
}

i32 main() {
    printf("Test - uppercase-L global symbols (Mach-O local-label escape):\n");
    i64 tests = 4, correct = 0;
    if (classGlobal())  { correct++; }
    if (arrayGlobal())  { correct++; }
    if (scalarGlobal()) { correct++; }
    if (stringGlobal()) { correct++; }
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
