import test;

/* A #define whose body is several strings (`#define APP PREFIX "/app"`)
 * is folded into one string token, and that token never got its decoded
 * length: a u8[] made from it was too short (or negative), and a
 * `u8 b[] = APP;` array was 0 bytes. Both now have the joined string's
 * length, with escapes decoded. */

#define PREFIX "./p"
#define APP PREFIX "/app"
#define TAB "a\tb" "c"

u8 gApp[] = APP;

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 lenOf(u8[] s) { return s.len; }

i32 main() {
    printf("Test - strings joined in a #define: ");
    check(lenOf(PREFIX) == 3, "one string");
    check(lenOf(APP) == 7, "a macro and a string");
    check(lenOf("x" APP "y") == 9, "around a macro");
    check(lenOf("x" APP "y" APP) == 16, "twice");
    check(lenOf(TAB) == 4, "with an escape");
    u8[] whole = "printf 'a\\n' > " APP "/f && "
                 "echo " APP;
    check(whole.len == 40 && whole[39] == 'p', "over two lines");
    u8 b[] = APP;
    u8 t[] = TAB;
    check(sizeof(b) == 8 && sizeof(gApp) == 8 && sizeof(t) == 5 && t[1] == '\t',
          "arrays sized by them");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
