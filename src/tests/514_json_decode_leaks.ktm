import json, mem, reflect, test;

/* v0.4.0 R4: what json.decode allocates, json.free gives back, also when
 * decode throws half way (what it filled in so far is freed by json.free
 * like the rest). Measured by the heap in use, and the decode/encode round
 * trip keeps the value. */

struct Inner { String s; i64[] xs; };
struct Outer { Inner *p; Inner arr[2]; Inner[] list; u8 *c; u8[] b; };

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    printf("Test - json.decode leaks nothing: ");
    u8[] text = "{\"p\": {\"s\": \"one\", \"xs\": [1, 2, 3]}, \"arr\": [{\"s\": \"two\", \"xs\": [4]}],
        \"list\": [{\"s\": \"three\", \"xs\": []}, {\"s\": \"four\", \"xs\": [5, 6]}],
        \"c\": \"cee\", \"b\": \"bee\"}";
    Outer o;
    mem.set(&o, 0, sizeof(Outer));
    json.decode(text, reflect.of(o));
    String once = json.encode(reflect.of(o));
    json.free(reflect.of(o));
    mem.set(&o, 0, sizeof(Outer));
    json.decode(once, reflect.of(o));
    String twice = json.encode(reflect.of(o));
    check(once.equals(twice) && o.list[1].xs[1] == 6, "the round trip keeps the value");
    json.free(reflect.of(o));
    once.free();
    twice.free();

    i64 before = test.heapInUse();
    for (i64 i = 0; i < 300; i++) {
        mem.set(&o, 0, sizeof(Outer));
        json.decode(text, reflect.of(o));
        json.free(reflect.of(o));
    }
    i64 delta = test.heapInUse() - before;
    check(delta < 65536, "decode + free in a loop");
    if (delta >= 65536) { printf("\n  %d bytes", delta); }

    before = test.heapInUse();
    for (i64 i = 0; i < 300; i++) {
        mem.set(&o, 0, sizeof(Outer));
        try {
            json.decode("{\"p\": {\"s\": \"x\", \"xs\": [1]}, \"list\": [{\"s\": \"y\", \"xs\": [true]}]}",
                        reflect.of(o));
        } catch (Error e) {}
        json.free(reflect.of(o));
    }
    delta = test.heapInUse() - before;
    check(delta < 65536, "a decode that throws half way, then free");
    if (delta >= 65536) { printf("\n  %d bytes", delta); }

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
