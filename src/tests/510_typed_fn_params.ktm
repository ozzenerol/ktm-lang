import list, vec, test;

/* A function whose parameters are typed pointers converts to a function
 * pointer whose matching parameters are void * (v0.4.0), so a sort
 * comparator or a free callback is written with its real types and no
 * casts: `i64 byAge(User *a, User *b)` goes straight to PtrVec.sort.
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

i32 main() {
    printf("Test - typed pointer parameters for void * ones: ");
    User users[4] = {{"cy", 40}, {"al", 20}, {"bo", 30}, {"di", 10}};

    /* An argument: PtrVec.sort with the typed comparator */
    vec.PtrVec *v = vec.ptrNew();
    for (i64 i = 0; i < 4; i++) { v.push(&users[i]); }
    v.sort(&byAge);
    bool sorted = true;
    for (i64 i = 0; i < 4; i++) { sorted = sorted && ((User *)v.entries[i]).age == (i + 1) * 10; }
    check(sorted, "PtrVec.sort(&byAge)");
    v.sort(&byAgeDesc);
    check(((User *)v.entries[0]).age == 40 && ((User *)v.entries[3]).age == 10,
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

    /* Free callbacks of the stdlib */
    v.release(&forget);
    check(gFreed == 100, "PtrVec.release(&forget) with a User *");
    list.List *l = list.new();
    l.append(&users[0]);
    l.append(&users[1]);
    gFreed = 0;
    l.release(&forget);
    check(gFreed == 60, "List.release(&forget) with a User *");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
