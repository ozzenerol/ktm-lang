#include "testhelper.ktm"

import json, mem, str;

/* JsonParse ignored its flags argument: JSON_STRNUM_FLAG (numbers kept
 * as their text, JSON_STRNUM / ->strnum) behaved like flags 0. */

bool strNumIs(json.Value *j, u8 *want) {
    return j && j.type == json.STRNUM && !str.cmp(j.strnum, want);
}

bool toStringIs(json.Value *j, u8 *want) {
    u8 *s = json.toStr(j);
    bool ok = s && !str.cmp(s, want);
    if (!ok) { printf("got `%s`, want `%s`\n", s, want); }
    mem.free(s);
    return ok;
}

i32 main() {
    printf("Test - JsonParse flags: ");
    i64 correct = 0, tests = 13;
    json.Value *j, *e;

    u8 *text = "{\"big\": 123456789012345678901234567890, \"f\": 1.10, "
                         "\"neg\": -42, \"exp\": 1e+15, \"hex\": -5e-3, \"one\": 7, "
                         "\"s\": \"5\", \"arr\": [1, 2.5,3], \"b\": true, \"n\": null}";

    /* Flags 0 and the default parse numbers */
    j = json.parse(text, -1, 0);
    if (json.ok(j) && json.select(j, ".neg:i").intVal == -42 &&
            json.select(j, ".f:f").floatVal == 1.1) { correct++; }
    json.release(j);
    j = json.parse(text);
    if (json.ok(j) && json.select(j, ".one:i").intVal == 7) { correct++; }
    json.release(j);

    /* JSON_STRNUM_FLAG keeps every number as written */
    j = json.parse(text, -1, json.STRNUM_FLAG);
    if (json.ok(j)) { correct++; }
    if (strNumIs(json.select(j, ".big"), "123456789012345678901234567890")) { correct++; }
    if (strNumIs(json.select(j, ".f"), "1.10") &&
            strNumIs(json.select(j, ".neg"), "-42") &&
            strNumIs(json.select(j, ".exp"), "1e+15") &&
            strNumIs(json.select(j, ".hex"), "-5e-3") &&
            strNumIs(json.select(j, ".one"), "7")) { correct++; }
    /* The typed selectors no longer match a number */
    if (!json.select(j, ".one:i") && !json.select(j, ".f:f")) { correct++; }
    /* Other values are unchanged; a quoted "5" is still a string */
    e = json.select(j, ".s");
    if (e && e.type == json.STRING && !str.cmp(e.str, "5") &&
            json.select(j, ".b:b").boolean == true && json.select(j, ".n:!")) { correct++; }
    e = json.select(j, ".arr:a");
    if (e && strNumIs(e.array, "1") && strNumIs(e.array.next, "2.5") &&
            strNumIs(e.array.next.next, "3") && !e.array.next.next.next) { correct++; }
    json.release(j);
    /* JsonToString writes the text back unquoted */
    j = json.parse("[1, 2.50,-3e2, {\"x\": 1E5}]", -1, json.STRNUM_FLAG);
    if (toStringIs(j, "[1,2.50,-3e2,{\"x\": 1E5}]")) { correct++; }
    json.release(j);

    /* A top-level number, and the length argument */
    j = json.parse("  -0.000001  ", -1, json.STRNUM_FLAG);
    if (json.ok(j) && strNumIs(j, "-0.000001")) { correct++; }
    json.release(j);
    j = json.parse("[12345,6]trailing", 9, json.STRNUM_FLAG);
    if (json.ok(j) && strNumIs(j.array, "12345") &&
            strNumIs(j.array.next, "6")) { correct++; }
    json.release(j);

    /* Invalid numbers are still errors */
    j = json.parse("[1.2.3]", -1, json.STRNUM_FLAG);
    if (!json.ok(j) && j.state.error == json.INVALID_NUMBER) { correct++; }
    json.release(j);
    j = json.parse("{\"a\": 12z}", -1, json.STRNUM_FLAG);
    if (!json.ok(j) && j.state.error == json.INVALID_NUMBER) { correct++; }
    json.release(j);

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
