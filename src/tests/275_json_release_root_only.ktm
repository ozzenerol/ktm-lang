import test;

import json, mem, str;

/* JsonRelease on a node from JsonSelect freed it and every node after
 * it, leaving the document pointing at freed memory. It now only frees a
 * root (what JsonParse returned); null or an inner node is ignored.
 * JsonGetStrerror/JsonPrintError crashed on an inner node (no state); they
 * now return null / print nothing for null or an inner node. valgrind
 * checks the AOT binary for invalid reads and leaks. */

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - JsonRelease root only: ");
    json.Value *d = json.parse("{\"a\": [1, {\"x\": \"y\"}], \"b\": \"str\", \"c\": {\"k\": null}}");

    json.Value *a = json.select(d, ".a");
    json.Value *x = json.select(d, ".a[1].x");
    json.release(a);               /* views: ignored */
    json.release(x);
    json.release(json.select(d, ".c"));
    json.release(null);
    u8 *s = json.toStr(d);
    tests++;
    if (!str.cmp(s, "{\"a\": [1,{\"x\": \"y\"}],\"b\": \"str\",\"c\": {\"k\": null}}") &&
            !str.cmp(json.select(d, ".a[1].x").str, "y")) { correct++; }
    else { printf("document changed: %s\n", s); }
    mem.free(s);

    /* error functions on null and inner nodes */
    tests++;
    if (json.errorStr(null) == null && json.errorStr(a) == null &&
            json.errorStr(d) == null) { correct++; }
    json.printError(null);
    json.printError(x);
    json.release(d);

    /* failed parses still release fully (valgrind: no leaks) */
    u8 *bad[4] = {"{\"a\": [1, 2", "[1, {\"k\": tru}]", "{\"a\" 1}", "[\"x\", ]"};
    bool ok = true;
    for (i64 i = 0; i < 4; ++i) {
        json.Value *e = json.parse(bad[i]);
        u8 *msg = json.errorStr(e);
        if (json.ok(e) || !msg) { ok = false; }
        mem.free(msg);
        json.release(e);
    }
    tests++; if (ok) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
