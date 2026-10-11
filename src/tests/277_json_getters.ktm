import test;

import json, mem, str;

/* New getters: JsonObjectGet (exact key), JsonArrayGet (index) and
 * JsonLen (items of an array/object). Checked against JsonSelect on
 * random documents, plus the cases JsonSelect can't express. */

i64 gSeed = 5;
i64 nextRand() {
    gSeed = (gSeed * 6364136223846793005 + 1442695040888963407);
    return (gSeed >> 33) & 0x7FFFFFFF;
}

/* A random JSON text: keys from a small set, so some repeat */
u8 *randValue(u8 *s, i64 depth) {
    i64 r = nextRand() % 10;
    if (depth > 3 || r < 4) {
        switch (nextRand() % 5) {
            case 0: return str.appendFormat(s, "%d", nextRand() % 1000 - 500);
            case 1: return str.appendFormat(s, "\"v%d\"", nextRand() % 50);
            case 2: return str.appendFormat(s, "true");
            case 3: return str.appendFormat(s, "null");
            default: return str.appendFormat(s, "%d.5", nextRand() % 100);
        }
    }
    i64 n = nextRand() % 6;
    if (r < 7) {
        s = str.appendFormat(s, "[");
        for (i64 i = 0; i < n; ++i) {
            if (i) { s = str.appendFormat(s, ","); }
            s = randValue(s, depth + 1);
        }
        return str.appendFormat(s, "]");
    }
    s = str.appendFormat(s, "{");
    for (i64 i = 0; i < n; ++i) {
        if (i) { s = str.appendFormat(s, ","); }
        s = str.appendFormat(s, "\"k%d\":", nextRand() % 4);
        s = randValue(s, depth + 1);
    }
    return str.appendFormat(s, "}");
}

i64 gChecks = 0;

/* Compare the getters with JsonSelect at `node` and below */
bool check(json.Value *node) {
    json.Value *it;
    i64 n = 0;
    if (json.isArray(node)) {
        for (it = node.array; it; it = it.next) {
            if (json.arrayGet(node, n) != it || json.select(node, "[*]", n) != it) { return false; }
            if (!check(it)) { return false; }
            n++;
            gChecks++;
        }
        if (json.arrayGet(node, n) != null || json.arrayGet(node, -1) != null) { return false; }
    } else if (json.isObject(node)) {
        for (it = node.object; it; it = it.next) {
            /* JsonSelect and JsonObjectGet both give the first node with a key */
            u8 sel[32];
            str.formatTo(sel, ".%s", it.key);
            if (json.objectGet(node, it.key) != json.select(node, sel)) { return false; }
            if (json.objectGet(node, it.key).key != it.key &&
                    str.cmp(json.objectGet(node, it.key).key, it.key)) { return false; }
            if (!check(it)) { return false; }
            n++;
            gChecks++;
        }
        if (json.objectGet(node, "nope") != null) { return false; }
    } else {
        if (json.len(node) != 0 || json.arrayGet(node, 0) || json.objectGet(node, "k0")) { return false; }
    }
    return json.len(node) == n;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - JSON getters: ");

    bool ok = true;
    for (i64 i = 0; i < 300 && ok; ++i) {
        u8 *text = randValue(null, 0);
        json.Value *d = json.parse(text);
        if (!json.ok(d) || !check(d)) {
            printf("mismatch in %s\n", text);
            ok = false;
        }
        json.release(d);
        mem.free(text);
    }
    tests++; if (ok && gChecks > 500) { correct++; }

    /* keys JsonSelect can't spell, duplicates, the empty key */
    json.Value *d = json.parse("{\"a.b\": 1, \"x[0]\": 2, \"t:s\": 3, \"\": 4, \"dup\": 5, \"dup\": 6,"
                                            " \"arr\": [10, [20, 21], {}], \"e\": []}");
    tests++;
    if (json.objectGet(d, "a.b").intVal == 1 && json.objectGet(d, "x[0]").intVal == 2 &&
            json.objectGet(d, "t:s").intVal == 3 && json.objectGet(d, "").intVal == 4 &&
            json.objectGet(d, "dup").intVal == 5 && !json.select(d, ".a.b") &&
            json.len(d) == 8) { correct++; }

    json.Value *arr = json.objectGet(d, "arr");
    tests++;
    if (json.len(arr) == 3 && json.arrayGet(arr, 0).intVal == 10 &&
            json.arrayGet(json.arrayGet(arr, 1), 1).intVal == 21 &&
            json.len(json.arrayGet(arr, 2)) == 0 && json.len(json.objectGet(d, "e")) == 0 &&
            !json.arrayGet(arr, 3) && !json.arrayGet(arr, -1) &&
            !json.arrayGet(arr, 0x7FFFFFFFFFFFFFFF)) { correct++; }

    /* wrong node types and null */
    tests++;
    if (!json.objectGet(arr, "0") && !json.arrayGet(d, 0) && !json.objectGet(null, "a") &&
            !json.objectGet(d, null) && !json.arrayGet(null, 0) && json.len(null) == 0 &&
            json.len(json.arrayGet(arr, 0)) == 0) { correct++; }

    json.release(d);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
