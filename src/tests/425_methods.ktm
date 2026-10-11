#include "testhelper.ktm"

import str;

/* Methods (Y10): `T User.name(params) { ... }`, written outside the
 * struct, is a function with an implicit const `User *self` first
 * parameter. `u.name(...)` calls it on a value, `p.name(...)` or
 * `p->name(...)` on a pointer. `.` also reaches a field through one
 * pointer (`p.x` is `p->x`). A derived struct inherits its base's
 * methods; one of the same name hides the base's, which `User.name(self)`
 * still calls. `&User.name` is a `fn(User *, ...) ...` function pointer.
 * No virtual dispatch: the static type picks the method. */

struct User {
    u8 *name;
    i64 age;
};

struct Admin : User {
    i64 level;
};

union Num {
    i64 i;
    f64 f;
};

u8 gOut[256];

void User.describe() {
    str.formatTo(gOut + str.len(gOut), "%s:%d;", self.name, self.age);
}

i64 User.older(i64 years = 1) {
    self.age += years;
    return self.age;
}

/* a prototype first, the definition after its use */
i64 User.twice();

void Admin.describe() {
    str.formatTo(gOut + str.len(gOut), "admin%d:", self.level);
    User.describe(self);
}

i64 User.sum(i64 ...nums) {
    i64 t = self.age;
    for (auto n : nums) { t += n; }
    return t;
}

i64 User.fact(i64 n) {
    if (n <= 1) { return 1; }
    return n * self.fact(n - 1);
}

i64 gCleaned = 0;
i64 User.guarded(i64 code) {
    defer gCleaned++;
    try {
        if (code) { throw(code); }
    } catch {
        return -code;
    }
    return self.age;
}

User *User.me() { return self; }

static i64 User.hidden() { return 7; }

i64 Num.asInt() { return self.i; }

i64 callVia(fn(User *, i64) i64 f, User *u, i64 n) { return f(u, n); }

i64 User.twice() { return self.age * 2; }

User gUser;
User *getUser() { return &gUser; }

i32 main() {
    printf("Test - methods: ");
    i64 correct = 0, tests = 0;

    User u;
    u.name = "ann";
    u.age = 30;
    User *p = &u;

    /* on a value, on a pointer with `.` and with `->` */
    gOut[0] = 0;
    u.describe();
    p.describe();
    p.describe();
    tests++; if (!str.cmp(gOut, "ann:30;ann:30;ann:30;")) { correct++; }

    /* default argument, arguments, `self` writes through */
    tests++; if (u.older() == 31 && p.older(5) == 36 && u.age == 36) { correct++; }
    tests++; if (u.sum() == 36 && p.sum(1, 2, 3) == 42) { correct++; }
    tests++; if (u.fact(5) == 120) { correct++; }
    tests++; if (u.twice() == 72) { correct++; }
    tests++; if (u.me() == p && p.me().me().age == 36) { correct++; }
    tests++; if (u.hidden() == 7) { correct++; }

    /* try/catch and defer inside a method */
    tests++; if (u.guarded(0) == 36 && u.guarded(4) == -4 && gCleaned == 2) { correct++; }

    /* inherited, hidden, the base called explicitly */
    Admin a;
    a.name = "bob";
    a.age = 40;
    a.level = 2;
    Admin *ap = &a;
    gOut[0] = 0;
    a.describe();
    tests++; if (!str.cmp(gOut, "admin2:bob:40;")) { correct++; }
    tests++; if (ap.older() == 41 && a.age == 41) { correct++; }
    /* no virtual dispatch: through a User * it is User's */
    gOut[0] = 0;
    User *asUser = ap;
    asUser.describe();
    tests++; if (!str.cmp(gOut, "bob:41;")) { correct++; }

    /* function pointers */
    fn(User *, i64) i64 f = &User.older;
    tests++; if (f(p, 4) == 40 && callVia(&User.older, p, 0) == 40) { correct++; }
    fn(User *) void d = &User.describe;
    gOut[0] = 0;
    d(p);
    User.describe(p);
    tests++; if (!str.cmp(gOut, "ann:40;ann:40;")) { correct++; }

    /* a union, an array element, a call's result */
    Num n;
    n.i = 9;
    tests++; if (n.asInt() == 9) { correct++; }
    User arr[2];
    arr[1].name = "cy";
    arr[1].age = 3;
    tests++; if (arr[1].older() == 4 && arr[1].age == 4) { correct++; }
    gUser.age = 11;
    tests++; if (getUser().older() == 12 && getUser().twice() == 24) { correct++; }

    /* `.` on a pointer reaches the field, as `->` does */
    p.age = 50;
    p.age++;
    p.age += 2;
    i64 *ip = &p.age;
    tests++; if (u.age == 53 && *ip == 53 && p.age == p.age) { correct++; }
    tests++; if (ap.level == 2 && ap.name == a.name && getUser().age == 12) { correct++; }
    User **pp = &p;
    tests++; if ((*pp).age == 53 && pp[0].age == 53) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
