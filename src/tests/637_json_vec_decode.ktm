import cstr, json, mem, reflect, str, test, vec;

/* v0.13.2: json.decode fills a vec.Vec<T> from a JSON array, as it does
 * a slice (null and a missing key leave it empty), and json.free frees
 * its items and its block and zeroes it. A struct of one's own with the
 * same fields is still an object. */

enum Severity { LOW, MIDDLE, HIGH, URGENT };
struct ReleaseItem { String name; Severity severity; };
struct Release {
    u8 major;
    vec.Vec<ReleaseItem> items;
    vec.Vec<i64> nums;
    vec.Vec<String> tags;
    vec.Vec<vec.Vec<i64>> grid;
};
struct LookAlike { i64 *data; i64 len; i64 cap; };
struct HasLookAlike { LookAlike v; };

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* decode<Release>(text) throws ERR_DECODE naming want */
bool fails(u8[] text, u8[] want, i64 flags = 0) {
    bool ok = false;
    try {
        Release r = json.decode<Release>(text, flags);
        json.free(reflect.of(r));
    } catch (Error e) {
        ok = e.code == json.ERR_DECODE && str.contains(cstr.view(e.message), want);
        if (!ok) { printf("\n  got %s", e.message); }
    }
    return ok;
}

i32 main() {
    printf("Test - json.decode into vec.Vec: ");
    Release r = json.decode<Release>("{\"major\": 1, "
        "\"items\": [{\"name\": \"a\", \"severity\": \"HIGH\"}, {\"name\": \"bb\", \"severity\": 0}], "
        "\"nums\": [1, -2, 3], \"tags\": [\"x\", \"yz\"], \"grid\": [[1, 2], [], [3]]}");
    check(r.major == 1, "the other fields still decode");
    check(r.items.len == 2, "a Vec of structs gets one item per array element");
    ReleaseItem first = r.items.get(0);
    ReleaseItem second = r.items.get(1);
    check(first.name.equals("a") && first.severity == Severity.HIGH, "item 0: String and enum by name");
    check(second.name.equals("bb") && second.severity == Severity.LOW, "item 1: enum by number");
    check(r.nums.len == 3 && r.nums.get(0) == 1 && r.nums.get(1) == -2 && r.nums.get(2) == 3,
          "a Vec<i64>");
    String t1 = r.tags.get(1);
    check(r.tags.len == 2 && t1.equals("yz"), "a Vec<String>");
    vec.Vec<i64> g0 = r.grid.get(0);
    vec.Vec<i64> g1 = r.grid.get(1);
    vec.Vec<i64> g2 = r.grid.get(2);
    check(r.grid.len == 3 && g0.len == 2 && g0.get(1) == 2 && g1.len == 0 && g2.get(0) == 3,
          "a Vec of Vecs");

    /* its own methods work on the decoded Vec: push grows the block */
    r.nums.push(4);
    check(r.nums.len == 4 && r.nums.get(3) == 4, "push onto a decoded Vec");

    json.free(reflect.of(r));
    check(r.items.data == null && r.items.len == 0 && r.items.cap == 0 && r.grid.len == 0,
          "json.free zeroes the Vecs");

    /* empty, null and missing all give an empty Vec */
    Release e = json.decode<Release>("{\"items\": [], \"nums\": null}");
    check(e.items.len == 0 && e.nums.len == 0 && e.nums.data == null && e.tags.len == 0,
          "an empty array, null and a missing key give an empty Vec");
    e.items.push(first);
    check(e.items.len == 1, "push onto a Vec decoded from []");
    e.items.free();
    json.free(reflect.of(e));

    /* top level */
    vec.Vec<i64> top = json.decode<vec.Vec<i64>>("[7, 8, 9]");
    check(top.len == 3 && top.get(2) == 9, "json.decode<vec.Vec<i64>> at the top level");
    json.free(reflect.of(top));
    check(top.data == null && top.len == 0, "json.free on a top-level Vec");

    /* the reflect form */
    vec.Vec<String> words;
    mem.set(&words, 0, sizeof(vec.Vec<String>));
    json.decode("[\"p\", \"q\"]", reflect.of(words));
    String w0 = words.get(0);
    check(words.len == 2 && w0.equals("p"), "json.decode(text, reflect.of(v)) into a Vec");
    json.free(reflect.of(words));

    /* STRICT applies to the items */
    check(fails("{\"major\": 1, \"nums\": [], \"tags\": [], \"grid\": [], "
                "\"items\": [{\"name\": \"a\", \"severity\": 1, \"other\": 1}]}", "items[0]", json.STRICT),
          "STRICT: an unknown key in an item names items[0]");
    check(fails("{\"nums\": [1, \"two\"]}", "nums[1]"), "a wrong item type names nums[1]");
    check(fails("{\"nums\": {\"data\": 1}}", "an array"), "an object for a Vec is an error");
    check(fails("{\"grid\": [[1], [2, true]]}", "grid[1][1]"), "nested paths name both indexes");

    /* a struct of one's own with the same fields stays an object */
    HasLookAlike h = json.decode<HasLookAlike>("{\"v\": {\"len\": 2, \"cap\": 3}}");
    check(h.v.len == 2 && h.v.cap == 3 && h.v.data == null, "a look-alike struct is still an object");

    /* no leaks: decode + free, and a throw halfway through an array */
    i64 before = test.heapInUse();
    for (i64 i = 0; i < 2000; i++) {
        Release l = json.decode<Release>("{\"items\": [{\"name\": \"leak\", \"severity\": 1}], "
                                         "\"tags\": [\"a\"], \"grid\": [[1, 2, 3]]}");
        json.free(reflect.of(l));
        try {
            Release bad = json.decode<Release>("{\"tags\": [\"a\", \"b\", 3]}");
        } catch (Error err) {
        }
    }
    check(test.heapInUse() - before < 65536, "decode, free and a throw leak nothing");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
