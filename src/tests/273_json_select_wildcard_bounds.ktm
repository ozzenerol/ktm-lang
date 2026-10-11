import test;

import json, mem, str;

/* JsonSelect: a '*' in an object path copied the wrong buffer, so ".*"
 * and ".price_*" never matched; a selector ending in '.' or '[' read past
 * its NUL; junk like "[1abc]", ":ixyz" and "..z" was accepted; a null
 * document or selector crashed. */

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - JsonSelect wildcards and bounds: ");
    json.Value *d = json.parse("{\"name\": \"ktm\", \"price_3\": 2, \"items\": [10, 20, {\"k\": [true]}],"
                                            " \"nested\": {\"price_9\": 9.5}}");

    json.Value *n = json.select(d, ".*", "name");
    json.Value *p3 = json.select(d, ".price_*", "3");
    json.Value *it = json.select(d, ".items[*]", 1);
    json.Value *deep = json.select(d, ".*[*].*[*]:b", "items", 2, "k", 0);
    json.Value *p9 = json.select(d, ".nested.price_*:f", "9");
    tests++;
    if (n && json.isString(n) && !str.cmp(n.str, "ktm") && p3 && p3.intVal == 2 &&
            it && it.intVal == 20 && deep && deep.boolean && p9 && p9.floatVal == 9.5) { correct++; }
    else { printf("wildcards: %p %p %p %p %p\n", n, p3, it, deep, p9); }

    /* plain paths still work */
    tests++;
    if (json.select(d, ".items[0]").intVal == 10 && json.select(d, ".items[2].k[0]:b") &&
            json.select(d, ".name:s") && !json.select(d, ".name:i") &&
            !json.select(d, ".items[3]") && !json.select(d, ".missing") &&
            json.select(d, ".items:a") && json.select(d, ".nested:o")) { correct++; }

    /* malformed selectors are rejected (and never read past the end) */
    tests++;
    if (!json.select(d, ".") && !json.select(d, "[") && !json.select(d, ".items[") &&
            !json.select(d, ".items.") && !json.select(d, ".items[1abc]") &&
            !json.select(d, ".items[-1]") && !json.select(d, ".items[]") &&
            !json.select(d, ".items[ 1]") && !json.select(d, ".name:sxyz") &&
            !json.select(d, ".name:") && !json.select(d, "..name") &&
            !json.select(d, ".items]") && !json.select(d, "name") && !json.select(d, "") &&
            !json.select(d, ".items[1") && !json.select(d, ".items[*]", -1) &&
            !json.select(d, ":*")) { correct++; }

    /* null document, selector or wildcard string */
    u8 *noStr = null;
    tests++;
    if (!json.select(null, ".name") && !json.select(d, null) &&
            !json.select(d, ".*", noStr)) { correct++; }

    /* a component longer than the 256-byte path limit */
    u8 *longSel = mem.alloc(300);
    longSel[0] = '.';
    mem.set(longSel + 1, 'a', 280);
    longSel[281] = '\0';
    tests++; if (!json.select(d, longSel)) { correct++; }
    mem.free(longSel);

    json.release(d);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
