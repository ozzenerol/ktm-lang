import cstr, json, mem, test;

/* JSON errors gave a byte "position" that was often past the problem
 * (`{"a",1}` skipped to the end looking for ':'; `[trux]` reported an
 * "end of json buffer"), printed a NUL as an empty '' and one message
 * had a stray newline; "JSON must start with '[' or '{'" was stale. They
 * now say what was expected and give the line and column. */

i64 gBad = 0;

bool errFn(u8 *jsonVal, i64 code, u8 *want) {
    json.Value *j = json.parse(cstr.view(jsonVal));
    u8 *e = json.errorStr(j).take();
    bool ok = !json.ok(j) && j.state.error == code && e && !cstr.cmp(e, want);
    if (!ok) { printf("\"%s\": %d \"%s\"\n", jsonVal, j.state.error, e); }
    mem.free(e);
    json.release(j);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - JSON error positions: ");

    json.Value *spaced = json.parse("{\"a\" : 1}");
    tests++;
    if (errFn("{\"a\",1}", json.INVALID_KEY_VALUE_SEPARATOR,
                    "Expected ':' after an object key, got ',' at line 1, column 5") &&
            errFn("{\"a\" 1}", json.INVALID_KEY_VALUE_SEPARATOR,
                    "Expected ':' after an object key, got '1' at line 1, column 6") &&
            json.ok(spaced)) { correct++; }
    json.release(spaced);

    tests++;
    if (errFn("[trux]", json.INVALID_LITERAL,
                    "Invalid literal, expected true, false or null at line 1, column 2") &&
            errFn("[nul]", json.INVALID_LITERAL,
                    "Invalid literal, expected true, false or null at line 1, column 2") &&
            errFn("{\"a\":\n [1,\n  fals]}", json.INVALID_LITERAL,
                    "Invalid literal, expected true, false or null at line 3, column 3")) { correct++; }

    tests++;
    if (errFn("[1,2", json.INVALID_ARRAY_CHARACTER,
                    "Expected ',' or ']' in an array, got '\\0' at line 1, column 5") &&
            errFn("[1 2]", json.INVALID_ARRAY_CHARACTER,
                    "Expected ',' or ']' in an array, got '2' at line 1, column 4") &&
            errFn("{\n\"a\":1,\n}", json.INVALID_KEY_TERMINATOR_CHARACTER,
                    "Expected '\"' to start an object key, got '}' at line 3, column 1") &&
            errFn("", json.CANNOT_START_PARSE,
                    "Expected a JSON value (object, array, string, number, true, false or null)"
                    " at line 1, column 1") &&
            errFn("[\"a\\q\"]", json.INVALID_ESCAPE_CHARACTER,
                    "Invalid escape character 'q' after '\\' at line 1, column 5")) { correct++; }

    /* a control character is shown as \xNN; no message ends in a newline */
    tests++;
    if (errFn("[true\x01]", json.INVALID_ARRAY_CHARACTER,
                    "Expected ',' or ']' in an array, got '\\x01' at line 1, column 6")) { correct++; }

    /* success, null and inner nodes */
    json.Value *ok = json.parse("{\"a\": [1]}");
    tests++;
    if (json.errorStr(ok).ptr == null && json.errorStr(null).ptr == null &&
            json.errorStr(json.select(ok, ".a")).ptr == null) { correct++; }
    json.printError(null);
    json.printError(json.select(ok, ".a[0]"));
    json.release(ok);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
