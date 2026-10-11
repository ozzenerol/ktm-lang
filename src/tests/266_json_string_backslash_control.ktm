import json, mem, str, test;

/* A JSON string ending in an escaped backslash ("C:\\") was rejected:
 * the end-of-string scan took `\"` for an escaped quote. Raw control
 * characters (bytes below 0x20) were accepted inside strings; RFC 8259
 * requires them escaped, so they are now JSON_INVALID_CONTROL_CHARACTER. */

bool parses(u8 *text, u8 *want)
{ // parses, and JsonToString gives `want`
    json.Value *j = json.parse(text);
    bool ok = json.ok(j);
    if (ok) {
        u8 *s = json.toStr(j);
        ok = !str.cmp(s, want);
        if (!ok) { printf("[%s] gave [%s] want [%s]\n", text, s, want); }
        mem.free(s);
    } else {
        printf("[%s] rejected: error %d\n", text, j.state.error);
    }
    json.release(j);
    return ok;
}

bool rejected(u8 *text, i64 len, i64 error) {
    json.Value *j = json.parse(text, len);
    bool ok = !json.ok(j) && j.state.error == error;
    if (!ok) { printf("[%s] error %d want %d\n", text, j.state.error, error); }
    json.release(j);
    return ok;
}

i32 main() {
    printf("Test - JSON strings: trailing backslash, control characters: ");
    i64 correct = 0, tests = 0, i;

    tests++;
    if (parses("{\"path\":\"C:\\\\\"}", "{\"path\": \"C:\\\\\"}") &&
            parses("[\"\\\\\"]", "[\"\\\\\"]") &&
            parses("{\"\\\\\":1}", "{\"\\\\\": 1}") &&
            parses("[\"\\\\\",\"x\"]", "[\"\\\\\",\"x\"]") &&
            parses("[\"\\\\\\\"\"]", "[\"\\\\\\\"\"]") &&
            parses("\"a\\\\\\\\b\\\\\"", "\"a\\\\\\\\b\\\\\"")) {
        correct++;
            }

    /* The value is right too */
    tests++;
    json.Value *j = json.parse("{\"p\":\"C:\\\\\",\"q\":\"\\\"x\\\"\"}");
    if (json.ok(j) && !str.cmp(json.select(j, ".p:s").str, "C:\\") &&
            !str.cmp(json.select(j, ".q:s").str, "\"x\"")) { correct++; }
    json.release(j);

    /* Every raw control character is rejected, in values and keys */
    tests++;
    u8 buf[16];
    bool ok = true;
    for (i = 1; i < 0x20; i++) {
        str.copy(buf, "[\"a?b\"]");
        buf[3] = i;
        if (!rejected(buf, 7, json.INVALID_CONTROL_CHARACTER)) { ok = false; }
        str.copy(buf, "{\"k?\":1}");
        buf[3] = i;
        if (!rejected(buf, 8, json.INVALID_CONTROL_CHARACTER)) { ok = false; }
    }
    if (ok) { correct++; }

    /* Escaped control characters, DEL and UTF-8 are fine */
    tests++;
    if (parses("[\"a\\tb\\n\\u0001\"]", "[\"a\\tb\\n\\u0001\"]") &&
            parses("[\"\x7f\xc3\xa9\"]", "[\"\x7f\xc3\xa9\"]")) {
        correct++;
            }

    /* The error message names it */
    tests++;
    j = json.parse("[\"a\tb\"]");
    u8 *e = json.errorStr(j);
    if (e && !str.cmp(e, "Unescaped control character '\\x09' in a string "
                                         "(write it as \\u0009) at line 1, column 4")) { correct++; }
    else { printf("message: %s\n", e); }
    mem.free(e);
    json.release(j);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
