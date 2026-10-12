import fs, libc, mem, proc, reflect, test;

/* v0.4.0 R2: an enum value's name at run time, and back. reflect.name(e)
 * gives the member's name ("" for a value that isn't one), reflect.nameOf
 * does it from a Type and an i64, reflect.enumValue finds a member by
 * name. printf of an enum is still its number. reflect.name of something
 * that isn't an enum is an error. */

#define SRC_FILE "__507_src.ktm"

enum Color { red, green = 5, blue };
enum Small : u8 { off, on };

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

u8[] colorName(Color c) { return reflect.name(c); }

i32 main() {
    printf("Test - reflect enum names: ");
    Color c = Color.blue;
    check(reflect.name(c).equals("blue") && reflect.name(Color.red).equals("red"), "name");
    check(colorName(Color.green).equals("green") && colorName((Color)6).equals("blue"),
          "through a parameter");
    check(reflect.name((Color)3).len == 0, "a value that isn't a member");
    check(reflect.name(Small.on).equals("on"), "a u8 enum");
    i64 v = -1;
    check(reflect.enumValue(reflect.typeOf(Color), "green", &v) && v == 5, "enumValue");
    check(!reflect.enumValue(reflect.typeOf(Color), "pink", &v) && v == 5, "no such member");
    check(!reflect.enumValue(reflect.typeOf(i64), "red", &v), "not an enum");
    check(reflect.nameOf(reflect.typeOf(Color), 6).equals("blue") &&
          reflect.nameOf(reflect.typeOf(i64), 0).len == 0, "nameOf");
    reflect.Type *t = reflect.typeOf(Color);
    check(t.members.len == 3 && t.members[1].name.equals("green") && t.members[1].value == 5,
          "the members");

    fs.writeAll(SRC_FILE, "import reflect;\nvoid main() { i64 n = 1; u8[] s = reflect.name(n); }\n");
    u8 *out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "-S -o /dev/null " SRC_FILE " 2>&1").take();
    fs.remove(SRC_FILE);
    check(out && libc.strstr(out, "error: reflect.name takes an enum value, not a i64"), "of an i64");
    mem.free(out);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
