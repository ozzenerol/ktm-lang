import json, test, vec;

/* v0.22.0: `ad T x;` without a value. The variable starts zeroed, as every
 * local does each time its declaration runs, and is freed at the end of
 * its block: a struct by its free() (or an @json struct's freeDecoded()),
 * a container empty or filled later, a pointer only when it holds
 * something. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

i64 gFreed = 0, gFreedSum = 0;
struct Res { i64 id; };
void Res.free() {
    gFreed++;
    gFreedSum += self.id;
}

@json struct Named { i64 id; String name; };

i64 lenOf() {
    ad vec.Vec<i64> v;
    v.push(4);
    v.push(5);
    return v.len;
}

void loop() {
    for i64 i = 1; i <= 3; i++ {
        ad Res r;
        if i == 2 {
            r.id = 10;
        }
    }
}

Res gRes;
void pointer(bool set) {
    ad Res *p;
    if set {
        p = &gRes;
        p.id = 100;
    }
}

i32 main() {
    printf("Test - ad without a value: ");

    {
        ad Res r;
        check(r.id == 0, "a struct starts zeroed");
        r.id = 7;
    }
    check(gFreed == 1 && gFreedSum == 7, "and is freed with what it holds at the end of its block");

    gFreed = 0;
    gFreedSum = 0;
    loop();
    check(gFreed == 3 && gFreedSum == 10, "in a loop: zeroed and freed once each pass");

    check(lenOf() == 2, "a container filled after an empty start");

    {
        ad Named u;
        check(u.id == 0 && u.name.len == 0, "an @json struct starts empty");
        u = json.decode<Named>("{\"id\": 3, \"name\": \"ada\"}");
        check(u.id == 3 && u.name.equals("ada"), "decoded into, freed by freeDecoded()");
    }
    {
        ad Named empty;
    }
    check(true, "an @json struct never filled is freed empty");

    gFreed = 0;
    gFreedSum = 0;
    pointer(false);
    check(gFreed == 0, "a pointer that stays null is not freed");
    pointer(true);
    check(gFreed == 1 && gFreedSum == 100, "a pointer that is set is freed");

    {
        ad Named n = json.decode<Named>("{\"id\": 1, \"name\": \"x\"}");
        check(n.id == 1, "ad with a value of an @json struct (freeDecoded)");
    }

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
