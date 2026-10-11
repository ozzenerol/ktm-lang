import json, mem, str, test;

/* json.parse accepted text after the top-level value (`[1]x`, `1 2`,
 * `truex`, `{"a":1}}`), skipped anything between a key and its ':'
 * (`{"a" , "b": 1}` was {"a": 1}), and took \v and \f for whitespace.
 * Only JSON's whitespace (space, tab, LF, CR) may follow the value or sit
 * between a key and ':'. */

bool errFn(u8 *text, i64 len, i64 error) {
    json.Value *j = json.parse(text, len);
    bool ok = !json.ok(j) && j.state.error == error;
    if (!ok) { printf("[%s]: error %d want %d\n", text, j.state.error, error); }
    json.release(j);
    return ok;
}

bool okFn(u8 *text, u8 *want, i64 len=-1) {
    json.Value *j = json.parse(text, len);
    bool ok = json.ok(j);
    if (ok) {
        u8 *s = json.toStr(j);
        ok = !str.cmp(s, want);
        if (!ok) { printf("[%s] gave [%s]\n", text, s); }
        mem.free(s);
    } else {
        printf("[%s] rejected: %d\n", text, j.state.error);
    }
    json.release(j);
    return ok;
}

i32 main() {
    printf("Test - JSON trailing content and separators: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (errFn("[1]x", -1, json.INVALID_JSON_TYPE_CHAR) &&
            errFn("1 2", -1, json.INVALID_JSON_TYPE_CHAR) &&
            errFn("truex", -1, json.INVALID_JSON_TYPE_CHAR) &&
            errFn("nullx", -1, json.INVALID_JSON_TYPE_CHAR) &&
            errFn("\"a\"x", -1, json.INVALID_JSON_TYPE_CHAR) &&
            errFn("{\"a\":1}}", -1, json.INVALID_JSON_TYPE_CHAR) &&
            errFn("[][]", -1, json.INVALID_JSON_TYPE_CHAR) &&
            errFn("[1]//x", -1, json.INVALID_JSON_TYPE_CHAR) &&
            errFn("[1]\x00[2]", 7, json.INVALID_JSON_TYPE_CHAR)) {
        correct++;
            }

    /* Exactly ':' after a key */
    tests++;
    if (errFn("{\"a\" , \"b\": 1}", -1, json.INVALID_KEY_VALUE_SEPARATOR) &&
            errFn("{\"a\" x : 1}", -1, json.INVALID_KEY_VALUE_SEPARATOR) &&
            errFn("{\"a\" 1}", -1, json.INVALID_KEY_VALUE_SEPARATOR) &&
            errFn("{\"a\",1}", -1, json.INVALID_KEY_VALUE_SEPARATOR)) {
        correct++;
            }

    /* \v and \f are not whitespace */
    tests++;
    if (errFn("[\x0b" "1]", -1, json.INVALID_JSON_TYPE_CHAR) &&
            errFn("[\x0c" "1]", -1, json.INVALID_JSON_TYPE_CHAR) &&
            errFn("[1]\x0b", -1, json.INVALID_JSON_TYPE_CHAR)) {
        correct++;
            }

    /* Whitespace everywhere it may be */
    tests++;
    if (okFn(" \t\r\n[ 1 , 2 ]\n ", "[1,2]") &&
            okFn("{ \"a\" \t:\r\n 1 , \"b\" : [ ] }", "{\"a\": 1,\"b\": []}") &&
            okFn("{\"a\":1}\n", "{\"a\": 1}") && okFn("1 ", "1") && okFn("true", "true") &&
            okFn("[1]", "[1]") && okFn("{}", "{}") && okFn("[[[]]]", "[[[]]]")) {
        correct++;
            }

    /* `len` ends the input: what is after it doesn't count */
    tests++;
    if (okFn("[1]xyz", "[1]", 3) && okFn("{\"a\":1}}", "{\"a\": 1}", 7)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
