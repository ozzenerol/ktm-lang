#include "testhelper.ktm"

import json;

/* JsonRelease never freed the parse state JsonParse hangs on the node it
 * returns (32 bytes per parse), and a parse error in an array or object
 * freed only the first item of the list built so far, leaking the rest
 * with their keys and strings. Checked by the heap staying flat. */

/* Bytes in use on the heap (HeapInUse() is in testhelper.ktm) */
i64 inUse() {
    return HeapInUse();
}

/* Heap bytes left behind by parsing and releasing `text` 2000 times */
i64 growth(u8 *text, i64 flags=0) {
    json.Value *j = json.parse(text, -1, flags);   /* warm up */
    json.release(j);
    i64 before = inUse();
    for (i64 i = 0; i < 2000; i++) {
        j = json.parse(text, -1, flags);
        json.select(j, ".a:s");
        json.release(j);
    }
    return inUse() - before;
}

bool flat(u8 *text, i64 flags=0) {
    i64 g = growth(text, flags);
    if (g >= 1024) { printf("%d bytes left by %s\n", g, text); }
    return g < 1024;
}

i32 main() {
    printf("Test - JsonRelease leaks: ");
    i64 correct = 0, tests = 0;

    /* Valid documents: the parse state */
    tests++;
    if (flat("{\"a\": \"x\\n\", \"b\": [1, 2.5, {\"c\": [true, false, null]}], \"d\": {}}") &&
            flat("[123456789012345678901234567890, 1.5, \"s\"]", json.STRNUM_FLAG) &&
            flat("\"\\u00e9\\ud83d\\ude00\"") && flat("42") && flat("[]")) {
        correct++;
            }

    /* Parse errors part way through a list */
    tests++;
    if (flat("[1, 2") && flat("[\"a\", \"b\" \"c\"]") &&
            flat("[\"x\", {\"k\": \"v\", \"q\": [1,2,}]")) {
        correct++;
            }
    tests++;
    if (flat("{\"a\": \"x\", \"b\" 1}") && flat("{\"a\": \"x\", 5: 1}") &&
            flat("{\"a\": \"x\" \"b\": 1}") && flat("{\"a\":1,\"b\":2,}") &&
            flat("{\"a\": \"x\", \"b\": [1, {\"c\": \"y\"} 2]}")) {
        correct++;
            }

    /* Errors are still reported */
    tests++;
    json.Value *j = json.parse("{\"a\": \"x\", \"b\" 1}");
    json.Value *k = json.parse("[1, 2");
    if (!json.ok(j) && !json.ok(k)) { correct++; }
    json.release(j);
    json.release(k);

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
