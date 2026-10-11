import cstr, json, mem, test;

/* json.toStr on a node from json.select also printed the nodes after it
 * and its key ("b": 2,"c": 3 for .b). It now serialises only that node's
 * value; a whole document prints as before. */

bool is(json.Value *n, u8 *want) {
    u8 *s = json.toStr(n);
    bool ok = !cstr.cmp(s, want);
    if (!ok) { printf("got %s, want %s\n", s, want); }
    mem.free(s);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - JsonToString single node: ");
    json.Value *d = json.parse("{\"a\": 1, \"b\": [2, {\"x\": null}, \"s\"], \"c\": {\"k\": true}, \"d\": 1.5}");

    tests++;
    if (is(d, "{\"a\": 1,\"b\": [2,{\"x\": null},\"s\"],\"c\": {\"k\": true},\"d\": 1.5}")) { correct++; }

    tests++;
    if (is(json.select(d, ".a"), "1") && is(json.select(d, ".b"), "[2,{\"x\": null},\"s\"]") &&
            is(json.select(d, ".b[0]"), "2") && is(json.select(d, ".b[1]"), "{\"x\": null}") &&
            is(json.select(d, ".b[2]"), "\"s\"") && is(json.select(d, ".c"), "{\"k\": true}") &&
            is(json.select(d, ".c.k"), "true") && is(json.select(d, ".d"), "1.5") &&
            is(json.select(d, ".b[1].x"), "null")) { correct++; }

    /* the selected node is left as it was */
    json.Value *b = json.select(d, ".b");
    u8 *again = json.toStr(d);
    tests++;
    if (b.next && !cstr.cmp(b.key, "b") &&
            !cstr.cmp(again, "{\"a\": 1,\"b\": [2,{\"x\": null},\"s\"],\"c\": {\"k\": true},\"d\": 1.5}")) {
        correct++;
            }
    mem.free(again);

    /* top-level arrays and scalars */
    json.Value *arr = json.parse("[[1, 2], [], {}]");
    json.Value *num = json.parse("42");
    tests++;
    if (is(arr, "[[1,2],[],{}]") && is(json.select(arr, "[0]"), "[1,2]") &&
            is(json.select(arr, "[1]"), "[]") && is(json.select(arr, "[2]"), "{}") &&
            is(num, "42")) { correct++; }

    json.release(arr);
    json.release(num);
    json.release(d);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
