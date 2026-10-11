import json, str, test;

/* JsonParse(text, len) read past `len`: the string scan, number scan
 * (and strtoll/strtod) and whitespace skip all went on to a NUL. So a
 * string the length cuts off was read to a quote after it, and a number
 * took the digits after it. Strings cut off are now
 * JSON_INVALID_STRING_NOT_TERMINATED. JsonParse(null) returns null. */

/* Parse the first `len` bytes of `text`, which goes on past them */
json.Value *cut(u8 *text, i64 len) {
    return json.parse(text, len);
}

bool unterminated(u8 *text, i64 len) {
    json.Value *j = cut(text, len);
    bool ok = !json.ok(j) && j.state.error == json.INVALID_STRING_NOT_TERMINATED;
    if (!ok) { printf("[%s]/%d: ok %d error %d\n", text, len, json.ok(j), j.state.error); }
    json.release(j);
    return ok;
}

i32 main() {
    printf("Test - JsonParse respects len: ");
    i64 correct = 0, tests = 0;
    json.Value *j;

    /* A string the length cuts off, with its quote just after */
    tests++;
    if (unterminated("\"abcd\"", 5) && unterminated("\"ab\\\"\"", 4) &&
            unterminated("[\"x\",\"yz\"]", 7) && unterminated("{\"key\":1}", 4) &&
            unterminated("\"", 1)) {
        correct++;
            }

    /* A number stops at `len` */
    tests++;
    j = cut("1234567", 3);
    if (json.ok(j) && j.type == json.INT && j.intVal == 123) { correct++; }
    json.release(j);
    tests++;
    j = cut("-12.5e3999", 7);
    if (json.ok(j) && j.type == json.FLOAT && j.floatVal == -12500.0) { correct++; }
    else { printf("%d %d\n", json.ok(j), j.type); }
    json.release(j);
    tests++;
    j = cut("[1,23]", 4);
    if (!json.ok(j)) { correct++; }
    json.release(j);

    /* Whitespace and values at the end of the length */
    tests++;
    j = cut("[1]  ", 5);
    json.Value *k = cut("true!!", 4);
    json.Value *n = cut("\"ok\"tail", 4);
    if (json.ok(j) && json.ok(k) && k.boolean == true && json.ok(n) &&
            !str.cmp(n.str, "ok")) { correct++; }
    json.release(j);
    json.release(k);
    json.release(n);

    /* null */
    tests++;
    if (json.parse(null) == null && json.parse(null, 5) == null) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
