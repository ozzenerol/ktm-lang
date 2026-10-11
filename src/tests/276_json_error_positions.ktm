#include "testhelper.ktm"

import json, mem, str;

/* JSON errors gave a byte "position" that was often past the problem
 * (`{"a",1}` skipped to the end looking for ':'; `[trux]` reported an
 * "end of json buffer"), printed a NUL as an empty '' and one message
 * had a stray newline; "JSON must start with '[' or '{'" was stale. They
 * now say what was expected and give the line and column. */

i64 gBad = 0;

bool Err(u8 *json, i64 code, u8 *want) {
    json.Value *j = json.parse(json);
    u8 *e = json.errorStr(j);
    bool ok = !json.ok(j) && j.state.error == code && e && !str.cmp(e, want);
    if (!ok) { printf("\"%s\": %d \"%s\"\n", json, j.state.error, e); }
    mem.free(e);
    json.release(j);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - JSON error positions: ");

    json.Value *spaced = json.parse("{\"a\" : 1}");
    tests++;
    if (Err("{\"a\",1}", json.INVALID_KEY_VALUE_SEPARATOR,
                    "Expected ':' after an object key, got ',' at line 1, column 5") &&
            Err("{\"a\" 1}", json.INVALID_KEY_VALUE_SEPARATOR,
                    "Expected ':' after an object key, got '1' at line 1, column 6") &&
            json.ok(spaced)) { correct++; }
    json.release(spaced);

    tests++;
    if (Err("[trux]", json.INVALID_LITERAL,
                    "Invalid literal, expected true, false or null at line 1, column 2") &&
            Err("[nul]", json.INVALID_LITERAL,
                    "Invalid literal, expected true, false or null at line 1, column 2") &&
            Err("{\"a\":\n [1,\n  fals]}", json.INVALID_LITERAL,
                    "Invalid literal, expected true, false or null at line 3, column 3")) { correct++; }

    tests++;
    if (Err("[1,2", json.INVALID_ARRAY_CHARACTER,
                    "Expected ',' or ']' in an array, got '\\0' at line 1, column 5") &&
            Err("[1 2]", json.INVALID_ARRAY_CHARACTER,
                    "Expected ',' or ']' in an array, got '2' at line 1, column 4") &&
            Err("{\n\"a\":1,\n}", json.INVALID_KEY_TERMINATOR_CHARACTER,
                    "Expected '\"' to start an object key, got '}' at line 3, column 1") &&
            Err("", json.CANNOT_START_PARSE,
                    "Expected a JSON value (object, array, string, number, true, false or null)"
                    " at line 1, column 1") &&
            Err("[\"a\\q\"]", json.INVALID_ESCAPE_CHARACTER,
                    "Invalid escape character 'q' after '\\' at line 1, column 5")) { correct++; }

    /* a control character is shown as \xNN; no message ends in a newline */
    tests++;
    if (Err("[true\x01]", json.INVALID_ARRAY_CHARACTER,
                    "Expected ',' or ']' in an array, got '\\x01' at line 1, column 6")) { correct++; }

    /* success, null and inner nodes */
    json.Value *ok = json.parse("{\"a\": [1]}");
    tests++;
    if (json.errorStr(ok) == null && json.errorStr(null) == null &&
            json.errorStr(json.select(ok, ".a")) == null) { correct++; }
    json.printError(null);
    json.printError(json.select(ok, ".a[0]"));
    json.release(ok);

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
