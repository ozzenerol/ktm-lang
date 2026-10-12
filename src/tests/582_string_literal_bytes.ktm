import test;

/* A string literal keeps every byte it is written with, NULs and control
 * bytes included, in a compiled program as in the JIT: on macOS the AOT
 * build put literals in __cstring, which the linker splits at each NUL
 * and merges, so a literal with NULs inside lost bytes (v0.8.0). */

u8 gArr[12] = "\x00\x01\x00\x00\x7f";
u8 *gPtr = "a\x00\x00\x00b";

bool same(u8[] s, u8[] want) {
    if (s.len != want.len) { return false; }
    for (i64 i = 0; i < s.len; i++) {
        if (s[i] != want[i]) { return false; }
    }
    return true;
}

i32 main() {
    printf("Test - string literal bytes: ");
    i64 correct = 0, tests = 0;

    u8[] s = "\x00\x08\x00\x00\x00\x02hi";
    u8 want[8] = {0, 8, 0, 0, 0, 2, 104, 105};
    tests++; if (same(s, want[0:8])) { correct++; }

    u8[] ctl = "\t\n\r\x1b\x7f\xff\0x";
    u8 want2[8] = {9, 10, 13, 27, 127, 255, 0, 120};
    tests++; if (same(ctl, want2[0:8])) { correct++; }

    tests++; if (gArr[0] == 0 && gArr[1] == 1 && gArr[3] == 0 && gArr[4] == 127 && gArr[11] == 0) { correct++; }
    tests++; if (gPtr[0] == 'a' && gPtr[1] == 0 && gPtr[3] == 0 && gPtr[4] == 'b' && gPtr[5] == 0) { correct++; }
    u8[] plain = "plain \"quoted\" text";
    tests++; if (plain.len == 19 && plain[6] == '"') { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
