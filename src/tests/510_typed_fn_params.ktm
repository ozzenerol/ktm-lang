import test;

/* A function whose parameters are typed pointers converts to a function
 * pointer whose matching parameters are void * (v0.4.0), so a sort
 * comparator or a free callback is written with its real types and no
 * casts: `i64 byAge(User *a, User *b)` goes straight to a fn(void *, void *) i64 parameter.
 * Wherever a function pointer conversion happens (an argument, an
 * assignment, an initialiser, a struct field, a return, a conditional)
 * and both ways round; the calls through it get the right pointers.
 * What is still an error is in 511. */

struct User { u8 *name; i64 age; };

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 byAge(User *a, User *b) { return a.age < b.age; }
i64 byAgeDesc(User *a, void *b) { return byAge(b, a); }
i64 nameLen(u8 *s) { i64 n = 0; while (s[n]) { n++; } return n; }

i64 gFreed = 0;
void forget(User *u) { gFreed += u.age; }

struct Sorter { fn(void *, void *) i64 less; i64 calls; };

fn(void *, void *) i64 order(bool up) {
    if (up) { return &byAge; }
    return &byAgeDesc;
}

i64 lessThrough(fn(void *, void *) i64 f, void *a, void *b) { return f(a, b); }

/* Functions taking void * callbacks (as the old PtrVec.sort/release did) */
void sortPtrs(void **a, i64 n, fn(void *, void *) i64 less) {
    for (i64 i = 0; i < n; i++) {
        for (i64 j = 0; j + 1 < n - i; j++) {
            if (less(a[j + 1], a[j])) { void *t = a[j]; a[j] = a[j + 1]; a[j + 1] = t; }
        }
    }
}
void eachPtr(void **a, i64 n, fn(void *) void f) {
    for (i64 i = 0; i < n; i++) { f(a[i]); }
}

i32 main() {
    printf("Test - typed pointer parameters for void * ones: ");
    User users[4] = {{"cy", 40}, {"al", 20}, {"bo", 30}, {"di", 10}};

    /* An argument: a void * comparator parameter given the typed one */
    void *v[4];
    for (i64 i = 0; i < 4; i++) { v[i] = &users[i]; }
    sortPtrs(v, 4, &byAge);
    bool sorted = true;
    for (i64 i = 0; i < 4; i++) { sorted = sorted && ((User *)v[i]).age == (i + 1) * 10; }
    check(sorted, "sortPtrs(v, 4, &byAge)");
    sortPtrs(v, 4, &byAgeDesc);
    check(((User *)v[0]).age == 40 && ((User *)v[3]).age == 10,
          "a mix of typed and void * parameters");

    /* An assignment, a call through it, and back to the typed type */
    fn(void *, void *) i64 less = &byAge;
    check(less(&users[3], &users[0]) == 1 && less(&users[0], &users[3]) == 0, "assignment");
    fn(User *, User *) i64 typed = less;
    check(typed(&users[1], &users[2]) == 1, "void * parameters to typed ones");
    fn(u8 *) i64 len = &nameLen;
    fn(void *) i64 lenAny = len;
    check(lenAny("four") == 4, "u8 * stands for void * too");

    /* A struct field, a return, a conditional, a parameter */
    Sorter s = {&byAge, 0};
    check(s.less(&users[1], &users[0]) == 1, "struct initialiser");
    s.less = &byAgeDesc;
    check(s.less(&users[1], &users[0]) == 0, "struct field assignment");
    check(order(true)(&users[3], &users[2]) == 1 && order(false)(&users[3], &users[2]) == 0, "return");
    bool up = gTests > 0;
    fn(void *, void *) i64 picked = up ? &byAge : &byAgeDesc;
    check(picked(&users[3], &users[0]) == 1, "conditional");
    check(lessThrough(&byAge, &users[3], &users[0]) == 1, "a function pointer parameter");

    /* Free callbacks */
    eachPtr(v, 4, &forget);
    check(gFreed == 100, "eachPtr(v, 4, &forget) with a User *");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
