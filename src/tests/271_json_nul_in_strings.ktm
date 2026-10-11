import test;

import json, mem, str;

/* A \u0000 in a JSON string or key cut it short: the parser decoded it
 * into a '\0' and everything after it was lost, also in JsonToString.
 * Json now has `str_len` and `key_len` (at the end of the class) with
 * the full length; JsonToString writes the '\0' back as \u0000.
 * JsonSelect still matches a key up to its first '\0'. */

bool bytes(u8 *s, i64 len, u8 *want, i64 wantLen) {
    if (len != wantLen) {
        printf("len %d want %d\n", len, wantLen);
        return false;
    }
    for (i64 i = 0; i < len; i++) {
        if (s[i] != want[i]) { return false; }
    }
    return true;
}

i32 main() {
    printf("Test - JSON \\u0000 in strings and keys: ");
    i64 correct = 0, tests = 0;

    json.Value *j = json.parse("[\"a\\u0000b\", \"\\u0000\", \"plain\", \"\"]");
    tests++;
    json.Value *e = j.array;
    if (json.ok(j) && bytes(e.str, e.strLen, "a\0b", 3) &&
            bytes(e.next.str, e.next.strLen, "\0", 1) &&
            e.next.next.strLen == 5 && e.next.next.next.strLen == 0) {
        correct++;
            }
    tests++;
    u8 *s = json.toStr(j);
    if (!str.cmp(s, "[\"a\\u0000b\",\"\\u0000\",\"plain\",\"\"]")) { correct++; }
    else { printf("[%s]\n", s); }
    /* ...which parses back the same */
    tests++;
    json.Value *k = json.parse(s);
    u8 *s2 = json.toStr(k);
    if (json.ok(k) && !str.cmp(s, s2)) { correct++; }
    mem.free(s);
    mem.free(s2);
    json.release(k);
    json.release(j);

    /* Keys */
    j = json.parse("{\"a\\u0000b\": 1, \"c\": \"x\\u0000\"}");
    tests++;
    if (json.ok(j) && bytes(j.object.key, j.object.keyLen, "a\0b", 3) &&
            j.object.next.keyLen == 1 &&
            bytes(j.object.next.str, j.object.next.strLen, "x\0", 2)) {
        correct++;
            }
    tests++;
    s = json.toStr(j);
    if (!str.cmp(s, "{\"a\\u0000b\": 1,\"c\": \"x\\u0000\"}")) { correct++; }
    else { printf("[%s]\n", s); }
    mem.free(s);
    /* A key with a '\0' is selected by what comes before it */
    tests++;
    json.Value *a = json.select(j, ".a:i");
    if (a && a.intVal == 1) { correct++; }
    json.release(j);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
