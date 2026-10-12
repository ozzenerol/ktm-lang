import cstr, json, mem, reflect, str, test;

/* T json.decode<T>(text) (v0.10.0): the JSON as a value of any type
 * (structs, pointers to them, slices, scalars), on top of the reflect
 * decode; a throw frees what was filled in by then, so nothing leaks;
 * the reflect form json.decode(text, reflect.of(x)) still fills a value
 * you have. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

enum Role { user, admin };
struct Address { String city; i64 zip; };
struct User {
    i64 id;
    String name;
    u8[][] tags;
    bool active;
    Role role;
    Address *home;
    f64 score;
};

i32 main() {
    printf("Test - json.decode<T>: ");

    User u = json.decode<User>("{\"id\": 7, \"name\": \"Ada\", \"tags\": [\"math\", \"code\"], "
                               "\"active\": true, \"role\": \"admin\", "
                               "\"home\": {\"city\": \"London\", \"zip\": 1815}, \"score\": 9.5}");
    check(u.id == 7 && str.equals(u.name, "Ada") && u.tags.len == 2 && str.equals(u.tags[1], "code") &&
          u.active && u.role == Role.admin && u.home && str.equals(u.home.city, "London") &&
          u.home.zip == 1815 && u.score == 9.5, "a struct with strings, slices, an enum, a pointer");
    json.free(reflect.of(u));
    check(u.home == null && u.tags.len == 0, "json.free(reflect.of(u)) frees and zeroes it");

    User partial = json.decode<User>("{\"id\": 3}");
    check(partial.id == 3 && partial.name.len == 0 && partial.home == null && !partial.active,
          "missing fields are zero");
    json.free(reflect.of(partial));

    User *p = json.decode<User *>("{\"id\": 9, \"name\": \"Bo\"}");
    check(p && p.id == 9 && str.equals(p.name, "Bo"), "a pointer to a struct, allocated");
    json.free(reflect.of(p));

    i64[] nums = json.decode<i64[]>("[1, 2, 3, 40]");
    check(nums.len == 4 && nums[3] == 40, "a slice");
    json.free(reflect.of(nums));
    check(json.decode<f64>("2.5") == 2.5 && json.decode<i64>("-12") == -12 && json.decode<bool>("true"),
          "scalars");
    String s = json.decode<String>("\"caf\\u00e9\"");
    check(str.equals(s, "caf\xc3\xa9"), "a String with an escape");
    s.free();

    /* errors: ERR_DECODE with the path, and nothing left allocated */
    i64 before = test.heapInUse();
    i64 code = 0;
    bool pathOk = false;
    for (i64 i = 0; i < 200; i++) {
        try {
            User bad = json.decode<User>("{\"name\": \"x\", \"tags\": [\"a\", \"b\"], "
                                         "\"home\": {\"city\": \"Rome\", \"zip\": \"no\"}}");
        } catch (Error e) {
            code = e.code;
            pathOk = str.contains(cstr.view(e.message), "home.zip");
        }
    }
    check(code == json.ERR_DECODE && pathOk, "a wrong type throws ERR_DECODE naming the field");
    check(test.heapInUse() - before < 65536, "a throw frees what was decoded so far");
    code = 0;
    try { json.decode<User>("{not json"); } catch (Error e) { code = e.code; }
    check(code == json.ERR_DECODE, "not JSON throws ERR_DECODE");

    /* the reflect form is still there, for a value you already have */
    User keep;
    mem.set(&keep, 0, sizeof(User));
    json.decode("{\"id\": 5}", reflect.of(keep));
    check(keep.id == 5, "json.decode(text, reflect.of(x)) fills an existing value");
    json.free(reflect.of(keep));

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
