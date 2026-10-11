import cstr, test;

/* `u8 buf[] = "abc"` at file scope was emitted with no section
 * directive by the AOT backends, so it landed in whatever section the
 * global before it left open: after the string literals, in a
 * read-only section, where writing to it crashed. It now goes in
 * .data like other initialised globals. */

u8 gFirst[] = "abc";
u8 gPadded[8] = "de";
static u8 gStatic[] = "xyz";
u8 *gPtr = "lit";
u8 gAfterPtr[] = "mno";
u8 gEsc[] = "a\tb";

i32 main() {
    i64 correct = 0, tests = 0;

    printf("Test - file-scope char arrays initialised from a string are writable: ");

    gFirst[0] = 'X';
    tests++; if (!cstr.cmp(gFirst, "Xbc")) { correct++; }
    gPadded[2] = 'f';
    gPadded[6] = 'Y';
    tests++; if (!cstr.cmp(gPadded, "def") && gPadded[6] == 'Y' && !gPadded[7]) { correct++; }
    gStatic[2] = 'Z';
    tests++; if (!cstr.cmp(gStatic, "xyZ")) { correct++; }
    tests++; if (!cstr.cmp(gPtr, "lit")) { correct++; }
    gAfterPtr[1] = 'N';
    tests++; if (!cstr.cmp(gAfterPtr, "mNo")) { correct++; }
    tests++; if (sizeof(gFirst) == 4 && sizeof(gPadded) == 8) { correct++; }
    gEsc[0] = 'A';
    tests++; if (!cstr.cmp(gEsc, "A\tb")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
