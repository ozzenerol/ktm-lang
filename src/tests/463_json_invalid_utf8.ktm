import cstr, json, libc, mem, test;

/* json.parse let bytes that aren't UTF-8 through inside strings. They are
 * json.INVALID_UTF8 now, at the sequence's first byte; valid UTF-8 (2, 3
 * and 4 bytes, in keys and values) still parses. */

i64 gCorrect = 0, gTests = 0;

void parses(u8 *text, u8 *want) {
    gTests++;
    json.Value *j = json.parse(text);
    if (json.ok(j)) {
        u8 *s = json.toStr(j);
        if (!cstr.cmp(s, want)) { gCorrect++; }
        else { printf("\n  [%s] gave [%s]\n", text, s); }
        mem.free(s);
    } else {
        printf("\n  [%s] rejected: %s\n", text, json.errorStr(j));
    }
    json.release(j);
}

void rejects(u8 *what, u8 *text, u8 *message) {
    gTests++;
    json.Value *j = json.parse(text);
    u8 *msg = json.ok(j) ? "parsed" : json.errorStr(j);
    if (!json.ok(j) && j.state.error == json.INVALID_UTF8 && libc.strstr(msg, message)) { gCorrect++; }
    else { printf("\n  %s: error %d [%s]\n", what, j.state.error, msg); }
    json.release(j);
}

i32 main() {
    printf("Test - json.parse rejects invalid UTF-8: ");

    parses("[\"caf\xC3\xA9\"]", "[\"caf\xC3\xA9\"]");
    parses("[\"\xE2\x82\xAC 5\"]", "[\"\xE2\x82\xAC 5\"]");
    parses("{\"\xF0\x9F\x98\x80\":\"\xF4\x8F\xBF\xBF\"}", "{\"\xF0\x9F\x98\x80\": \"\xF4\x8F\xBF\xBF\"}");

    rejects("stray continuation", "[\"a\x80\"]", "starting with '\\x80' in a string at line 1, column 4");
    rejects("cut off", "[\"\xC3\"]", "'\\xC3' in a string at line 1, column 3");
    rejects("overlong 2", "[\"\xC0\xAF\"]", "'\\xC0'");
    rejects("overlong 3", "[\"\xE0\x80\xAF\"]", "'\\xE0'");
    rejects("overlong 4", "[\"\xF0\x80\x80\xAF\"]", "'\\xF0'");
    rejects("surrogate", "[\"\xED\xA0\x80\"]", "'\\xED'");
    rejects("past U+10FFFF", "[\"\xF4\x90\x80\x80\"]", "'\\xF4'");
    rejects("never UTF-8", "[\"\xFF\"]", "'\\xFF'");
    rejects("in a key", "{\"\xE9t\xE9\":1}", "line 1, column 3");
    rejects("a later line", "[\n  \"ok\",\n  \"b\xE9d\"]", "line 3, column 5");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
