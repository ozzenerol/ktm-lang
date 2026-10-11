import json, str, test;

/* JsonParse only accepted a non-empty object or array at the top level:
 * `{}`, `[]`, `42`, `"text"`, `true` and `null` were errors (moving past the
 * last character of the input was treated as running off the end). */

i64 gCorrect = 0;

/* Parses `text`, checks it is ok and has type `type` */
json.Value *ok(u8 *text, i64 type) {
    json.Value *j = json.parse(text);
    if (json.ok(j) && j.type == type) {
        gCorrect++;
    } else {
        printf("\n  '%s' failed (type %d)\n", text, j.type);
    }
    return j;
}

void bad(u8 *text) {
    json.Value *j = json.parse(text);
    if (!json.ok(j)) { gCorrect++; }
    else { printf("\n  '%s' should be rejected\n", text); }
    json.release(j);
}

i32 main() {
    i64 tests = 41;
    json.Value *j;
    printf("Test - JsonParse top-level values: ");

    j = ok("{}", json.OBJECT);
    if (j.object == null) { gCorrect++; }
    json.release(j);
    j = ok("[]", json.ARRAY);
    if (j.array == null) { gCorrect++; }
    json.release(j);
    json.release(ok("  { }  ", json.OBJECT));
    json.release(ok("\n[\t]\n", json.ARRAY));

    j = ok("42", json.INT);
    if (j.intVal == 42) { gCorrect++; }
    json.release(j);
    j = ok("7", json.INT);       /* single digit */
    if (j.intVal == 7) { gCorrect++; }
    json.release(j);
    j = ok("-12", json.INT);
    if (j.intVal == -12) { gCorrect++; }
    json.release(j);
    j = ok("2.5", json.FLOAT);
    if (j.floatVal == 2.5) { gCorrect++; }
    json.release(j);
    j = ok("\"text\"", json.STRING);
    if (!str.cmp(j.str, "text")) { gCorrect++; }
    json.release(j);
    j = ok("\"\"", json.STRING);
    if (!str.cmp(j.str, "")) { gCorrect++; }
    json.release(j);
    j = ok("true", json.BOOL);
    if (j.boolean == true) { gCorrect++; }
    json.release(j);
    j = ok(" false ", json.BOOL);
    if (j.boolean == false) { gCorrect++; }
    json.release(j);
    json.release(ok("null", json.NULL));

    /* Non-empty objects and arrays still work, also as the last thing */
    j = ok("{\"a\": [1, 2]}", json.OBJECT);
    if (json.select(j, ".a[1]:i").intVal == 2) { gCorrect++; }
    json.release(j);
    j = ok("[{}, [], \"x\"]", json.ARRAY);
    if (j.array.type == json.OBJECT && j.array.next.type == json.ARRAY) { gCorrect++; }
    json.release(j);
    json.release(ok("{\"a\": true}", json.OBJECT));
    json.release(ok("[null]", json.ARRAY));

    /* The explicit length is respected */
    j = json.parse("[1]trailing", 3);
    if (json.ok(j) && j.array.intVal == 1) { gCorrect++; }
    json.release(j);
    j = json.parse("truex", 4);
    if (json.ok(j) && j.type == json.BOOL) { gCorrect++; }
    json.release(j);

    /* Still errors */
    bad("");
    bad("   ");
    bad("{");
    bad("[");
    bad("[1,");
    bad("\"abc");
    bad("tru");
    bad("nul");
    bad("nulx");
    bad("x");

    test.printResult(gCorrect, tests);
    printf("====\n");
    return 0;
}
