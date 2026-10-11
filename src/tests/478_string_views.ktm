import str, test;

/* Reading bytes (v0.3.0 step 5): str's functions that take a u8[] (so a
 * literal, a slice or a String) and are methods of all three: find,
 * findLast, contains, startsWith, endsWith, compare, equals, trim (a
 * view), split (views, in a list freed with `defer parts.free()`), and
 * the UTF-8 ones, decodeRune, runeCount, validUtf8. String.replace
 * changes it in place. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    printf("Test - reading a u8[]: ");
    u8[] s = "  a,bb,,ccc  ";
    u8[] t = s.trim();
    check(t.len == 9 && t.ptr == s.ptr + 2 && "\t x \n".trim().equals("x") && "   ".trim().len == 0,
          "trim is a view");
    check(t.find("bb") == 2 && t.find("x") == -1 && t.find("") == 0 && t.findLast(",") == 5 &&
          str.find("aaa", "aaaa") == -1, "find, findLast");
    check(t.contains(",,") && !t.contains("d") && t.startsWith("a,") && t.endsWith("cc") &&
          !t.endsWith("a,bb,,cccc"), "contains, startsWith, endsWith");
    check(str.compare("abc", "abd") < 0 && str.compare("b", "abc") > 0 &&
          str.compare("ab", "abc") < 0 && str.compare("", "") == 0 && "x".equals("x") &&
          !"x".equals("xy"), "compare, equals");

    u8[][] parts = t.split(",");
    defer parts.free();
    check(parts.len == 4 && parts[0].equals("a") && parts[1].equals("bb") && parts[2].len == 0 &&
          parts[3].equals("ccc") && parts[1].ptr == t.ptr + 2, "split into views");
    u8[][] one = "abc".split("");
    defer one.free();
    u8[][] two = "a--b".split("--");
    defer two.free();
    check(one.len == 1 && one[0].equals("abc") && two.len == 2 && two[1].equals("b"), "split edges");

    String n = str.from("Hello World");
    defer n.free();
    check(n.startsWith("Hello") && n.find("World") == 6 && n.trim().equals(n), "methods of a String");
    n.replace("o", "0");
    check(n.equals("Hell0 W0rld"), "replace");
    n.replace("l", "");
    n.replace("W", "Wide W");
    check(n.equals("He0 Wide W0rd") && n.ptr[n.len] == 0, "replace shorter and longer");
    n.replace("", "x");
    check(n.equals("He0 Wide W0rd"), "replace of nothing");

    u8[] u = "h\xC3\xA9\xE2\x82\xAC\xF0\x9F\x98\x80!";
    i64 at = 0;
    i32 cps[5];
    for (i64 i = 0; i < 5; i++) { cps[i] = str.decodeRune(u, &at); }
    check(cps[0] == 'h' && cps[1] == 0xE9 && cps[2] == 0x20AC && cps[3] == 0x1F600 && cps[4] == '!' &&
          str.decodeRune(u, &at) == -1, "decodeRune");
    check(u.len == 11 && u.runeCount() == 5 && u.validUtf8(), "runeCount, validUtf8");
    check(!"a\x80b".validUtf8() && "a\x80b".runeCount() == 3 && !"\xC0\xAF".validUtf8() &&
          !"\xED\xA0\x80".validUtf8() && "\xEF\xBF\xBD".validUtf8(), "invalid UTF-8");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
