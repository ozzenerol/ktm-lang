import str, test;

/* String's methods (v0.3.0 step 4): append (also of a view of itself),
 * appendByte, appendFormat (`%s` takes a u8[] or a String), insert and
 * remove (bounds-checked: ERR_BOUNDS), clear, reserve, toLower, toUpper,
 * reverse. It grows as needed and keeps its NUL. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool is(String *s, u8 *want) {
    return s.len == str.len(want) && !str.cmp(s.cstr(), want) && s.ptr[s.len] == 0;
}

i32 main() {
    printf("Test - String methods: ");
    String s;
    defer s.free();

    s.append("ab");
    s.append(s);
    s.append(s[1:3]);
    check(is(&s, "ababba"), "append, of itself");
    for (i64 i = 0; i < 1000; i++) { s.appendByte('z'); }
    check(s.len == 1006 && s.cap >= 1006 && s.ptr[1005] == 'z' && s.ptr[1006] == 0, "grows");
    s.clear();
    i64 cap = s.cap;
    check(s.len == 0 && s.cap == cap && is(&s, ""), "clear keeps its room");

    u8[] view = "view";
    s.appendFormat("%d %s [%5s] %s", 42, view, "lit", view[1:3]);
    check(is(&s, "42 view [  lit] ie"), "appendFormat");
    s.clear();
    s.append("held");
    String other = str.from("x");
    defer other.free();
    other.appendFormat("<%s>", s);
    check(is(&other, "x<held>"), "appendFormat of a String");

    s.clear();
    s.append("ace");
    s.insert(1, "b");
    s.insert(3, "d");
    s.insert(0, ">");
    s.insert(s.len, "<");
    check(is(&s, ">abcde<"), "insert");
    s.insert(1, s[1:3]);
    check(is(&s, ">ababcde<"), "insert a view of itself");
    s.remove(0, 1);
    s.remove(s.len - 1, s.len);
    s.remove(1, 3);
    check(is(&s, "abcde"), "remove");
    s.remove(2, 2);
    check(is(&s, "abcde"), "remove nothing");

    try {
        s.insert(9, "x");
        check(false, "insert past the end");
    } catch (Error e) {
        check(e.code == ERR_BOUNDS && !str.cmp(e.message, "slice 9:5 out of range for length 5"),
              "insert past the end");
    }
    try {
        s.remove(3, 2);
        check(false, "remove backwards");
    } catch (Error e) {
        check(e.code == ERR_BOUNDS && !str.cmp(e.message, "slice 3:2 out of range for length 5"),
              "remove backwards");
    }
    check(is(&s, "abcde"), "unchanged by a failed call");

    s.toUpper();
    check(is(&s, "ABCDE"), "toUpper");
    s.append("-Xy1");
    s.toLower();
    check(is(&s, "abcde-xy1"), "toLower");
    s.reverse();
    check(is(&s, "1yx-edcba"), "reverse");
    s.reserve(500);
    check(s.cap >= s.len + 500 && is(&s, "1yx-edcba"), "reserve");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
