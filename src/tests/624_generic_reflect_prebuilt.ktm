import json, libc, mem, test;

/* A generic of a prebuilt module (json, in libktm) that describes its type
 * argument with reflect.of: the description's start-up stores belong to the
 * program, as the module's _init is already compiled. json.decode<T> on a
 * struct the program never passes to reflect.of itself saw an empty type
 * ("a  can't be JSON"). */

struct Point { i64 x; i64 y; };
struct Named { u8 *name; Point at; };

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    printf("Test - generic reflect in a prebuilt module: ");

    Point p = json.decode<Point>("{\"x\": 5, \"y\": 6}");
    check(p.x == 5 && p.y == 6, "decode<Point>");

    Named n = json.decode<Named>("{\"name\": \"home\", \"at\": {\"x\": 1, \"y\": 2}}");
    check(n.at.x == 1 && n.at.y == 2 && !libc.strcmp(n.name, "home"), "decode<Named>, nested");
    mem.free(n.name);

    bool caught = false;
    try { json.decode<Point>("{\"x\": \"no\"}"); } catch (Error e) { caught = true; }
    check(caught, "a wrong field type still throws");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
