import cstr, json, mem, reflect, test;

/* v0.4.0 R4: json.decode fills a value of any type from JSON by its
 * reflect description, and json.free frees what it allocated. Fields by
 * name (private ones skipped), nested structs, pointers (allocated), u8 *,
 * Strings, u8[], slices of anything, fixed arrays (at most their length;
 * a u8 array also from a string), enums by name or number, ints from
 * integers only, floats from any number. Unknown keys are ignored and
 * missing fields kept, unless STRICT. Errors throw ERR_DECODE with the
 * path. */

enum Role { guest, admin };
struct Addr { u8[] city; i32 zip; };
struct Item { u8[] sku; f64 price; };
struct Order {
    i64 id;
    String who;
    Role role;
    bool paid;
    Addr ship;
    Addr *bill;
    Item[] items;
    i64 codes[3];
    u8 tag[8];
    u8 *note;
    i16 small;
};

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

Order fresh() {
    Order o;
    mem.set(&o, 0, sizeof(Order));
    return o;
}

/* decode(text) into a fresh Order throws ERR_DECODE starting with want */
bool fails(u8[] text, u8[] want, i64 flags = 0) {
    Order o = fresh();
    bool ok = false;
    try {
        json.decode(text, reflect.of(o), flags);
    } catch (Error e) {
        ok = e.code == json.ERR_DECODE && cstr.view(e.message).startsWith(want);
        if (!ok) { printf("\n  got %s", e.message); }
    }
    json.free(reflect.of(o));
    return ok;
}

i32 main() {
    printf("Test - json.decode: ");
    Order o = fresh();
    json.decode("{\"id\": 42, \"who\": \"Ada\", \"role\": \"admin\", \"paid\": true,
        \"ship\": {\"city\": \"Iasi\", \"zip\": 700000}, \"bill\": {\"city\": \"Cluj\", \"zip\": 400000},
        \"items\": [{\"sku\": \"a1\", \"price\": 2}, {\"sku\": \"b2\", \"price\": 3.5}],
        \"codes\": [7, 8], \"tag\": \"new\", \"note\": \"fragile\", \"small\": -300,
        \"extra\": {\"ignored\": [1, 2]}}", reflect.of(o));
    check(o.id == 42 && o.who.equals("Ada") && o.role == Role.admin && o.paid, "scalars, String, enum");
    check(o.ship.city.equals("Iasi") && o.ship.zip == 700000 && o.bill && o.bill.city.equals("Cluj"),
          "a nested struct and a pointer to one");
    check(o.items.len == 2 && o.items[0].sku.equals("a1") && o.items[0].price == 2.0 &&
          o.items[1].price == 3.5, "a slice of structs, an int as a float");
    check(o.codes[0] == 7 && o.codes[1] == 8 && o.codes[2] == 0, "a fixed array, shorter");
    check(!cstr.cmp(o.tag, "new") && !cstr.cmp(o.note, "fragile") && o.small == -300,
          "a u8 array and a u8 * from strings, an i16");
    json.free(reflect.of(o));
    check(o.who.len == 0 && o.items.len == 0 && o.bill == null && o.note == null, "free zeroes");

    o = fresh();
    o.id = 5;
    o.paid = true;
    json.decode("{\"role\": 1, \"bill\": null}", reflect.of(o));
    check(o.id == 5 && o.paid && o.role == Role.admin && o.bill == null,
          "missing fields are kept, an enum by number, null");
    json.free(reflect.of(o));

    check(fails("{\"id\": \"7\"}", "id: expected an integer, got a string"), "an int");
    check(fails("{\"id\": 1.5}", "id: expected an integer, got a number"), "a float for an int");
    check(fails("{\"ship\": {\"zip\": true}}", "ship.zip: expected an integer, got a bool"), "nested path");
    check(fails("{\"items\": [{\"price\": 1}, {\"price\": \"x\"}]}", "items[1].price: expected a number"),
          "an item's path");
    check(fails("{\"role\": \"boss\"}", "role: Role has no member \"boss\""), "an enum name");
    check(fails("{\"codes\": [1, 2, 3, 4]}", "codes: expected at most 3 items, got 4"), "too many items");
    check(fails("{\"tag\": \"much too long\"}", "tag: expected at most 7 bytes, got 13"), "a long string");
    check(fails("[1]", "expected an object, got an array"), "the root");
    check(fails("{\"id\": ", "not JSON: "), "not JSON");
    check(fails("{\"id\": 1, \"nope\": 2}", "who: missing", json.STRICT), "STRICT: a missing field");
    check(fails("{\"id\":1,\"who\":\"\",\"role\":0,\"paid\":false,\"ship\":{\"city\":\"\",\"zip\":0},"
                "\"bill\":null,\"items\":[],\"codes\":[],\"tag\":\"\",\"note\":null,\"small\":0,\"x\":1}",
                "x: no such field in Order", json.STRICT), "STRICT: an unknown key");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
