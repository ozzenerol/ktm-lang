import json, mem, str, test;

/* json.parse rejected every \u escape. They are decoded to UTF-8 now,
 * including surrogate pairs; malformed ones are still errors. */

/* Parses `text` and returns the string at .e, or null if it didn't parse */
u8 *parseE(u8 *text) {
    json.Value *j = json.parse(text);
    if (!json.ok(j)) {
        json.release(j);
        return null;
    }
    json.Value *e = json.select(j, ".e:s");
    u8 *res = null;
    if (e) { res = str.dup(e.str); }
    json.release(j);
    return res;
}

bool is(u8 *text, u8 *want) {
    u8 *got = parseE(text);
    bool ok = got != null && !str.cmp(got, want);
    if (!ok) { printf("\n  %s -> '%s', want '%s'\n", text, got, want); }
    if (got) { mem.free(got); }
    return ok;
}

bool rejected(u8 *text) {
    json.Value *j = json.parse(text);
    bool bad = !json.ok(j);
    if (!bad) { printf("\n  %s should be rejected\n", text); }
    json.release(j);
    return bad;
}

i32 main() {
    i64 correct = 0, tests = 20;
    printf("Test - JsonParse \\u escapes: ");

    if (is("{\"e\": \"\\u0041\"}", "A")) { correct++; }
    if (is("{\"e\": \"x\\u0041y\\u0042\"}", "xAyB")) { correct++; }
    if (is("{\"e\": \"\\u00e9\"}", "\xC3\xA9")) { correct++; }            /* é, 2 bytes */
    if (is("{\"e\": \"\\u00E9\"}", "\xC3\xA9")) { correct++; }            /* upper-case hex */
    if (is("{\"e\": \"\\u007f\\u0080\"}", "\x7F\xC2\x80")) { correct++; } /* 1/2 byte edge */
    if (is("{\"e\": \"\\u07FF\\u0800\"}", "\xDF\xBF\xE0\xA0\x80")) { correct++; }
    if (is("{\"e\": \"\\u20AC\"}", "\xE2\x82\xAC")) { correct++; }        /* € */
    if (is("{\"e\": \"\\uFFFF\"}", "\xEF\xBF\xBF")) { correct++; }
    if (is("{\"e\": \"\\uD83D\\uDE00\"}", "\xF0\x9F\x98\x80")) { correct++; } /* U+1F600 */
    if (is("{\"e\": \"\\uDBFF\\uDFFF\"}", "\xF4\x8F\xBF\xBF")) { correct++; } /* U+10FFFF */
    if (is("{\"e\": \"\\u0022\\\\\\n\"}", "\"\\\n")) { correct++; }      /* mixed escapes */

    /* In an array, and as an object key */
    json.Value *j = json.parse("[\"\\u0041\\u0042\", 1]");
    if (json.ok(j) && !str.cmp(j.array.str, "AB")) { correct++; }
    json.release(j);
    j = json.parse("{\"\\u006By\": 7}");
    if (json.ok(j) && json.select(j, ".ky:i") && json.select(j, ".ky:i").intVal == 7) { correct++; }
    json.release(j);

    /* Still rejected */
    if (rejected("{\"e\": \"\\u12\"}")) { correct++; }          /* too short */
    if (rejected("{\"e\": \"\\u12G4\"}")) { correct++; }        /* not hex */
    if (rejected("{\"e\": \"\\uD83D\"}")) { correct++; }        /* lone high surrogate */
    if (rejected("{\"e\": \"\\uD83Dx\"}")) { correct++; }
    if (rejected("{\"e\": \"\\uD83D\\u0041\"}")) { correct++; } /* high + non-low */
    if (rejected("{\"e\": \"\\uDE00\"}")) { correct++; }        /* lone low surrogate */
    if (rejected("{\"e\": \"\\x41\"}")) { correct++; }          /* unknown escape */

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
