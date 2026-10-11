#include "testhelper.ktm"

/* A tagged class or union defined inside a class body crashed ktm: the
 * field parser left the `class`/`union` keyword unread, so the tag was
 * never seen and the definition had no fields. Without a field name its
 * fields are used as the outer class's own (like an anonymous one) and
 * the tag is a type of its own; with a field name it is that field's
 * type. */

struct Y {
    i64 a;
    union U2 { i32 i; f32 f; };
    i64 z;
};

struct Box {
    i32 tag;
    struct Pair { i64 x, y; };
    i32 end;
};

union Num {
    i64 whole;
    union Half { i32 lo; i32 alt; };
};

struct Named {
    i8 c;
    union NU { i32 i; f32 f; } u, v;
    struct NP { i32 p, q; } pt;
    NU *up;
};

struct Anon {
    i64 a;
    union { i32 i; f32 f; };
    i64 z;
};

i32 main() {
    printf("Test - tagged class or union inside a class: ");
    i64 correct = 0, tests = 0;

    Y y;
    y.a = 1; y.i = 2; y.z = 3;
    tests++; if (sizeof(Y) == 24 && y.a == 1 && y.i == 2 && y.z == 3) { correct++; }
    y.f = 1.5;
    tests++; if (y.f == 1.5 && y.a == 1 && y.z == 3) { correct++; }
    tests++; if ((u8 *)&y.i - (u8 *)&y == 8 && (u8 *)&y.z - (u8 *)&y == 16) { correct++; }

    /* The tag is a type of its own, with its own offsets */
    U2 u2;
    u2.i = 7;
    tests++; if (sizeof(U2) == 4 && (u8 *)&u2.f == (u8 *)&u2 && u2.i == 7) { correct++; }

    Box b = {1, 2, 3, 4};
    tests++; if (sizeof(Box) == 32 && b.tag == 1 && b.x == 2 && b.y == 3 && b.end == 4) { correct++; }
    tests++; if ((u8 *)&b.x - (u8 *)&b == 8 && (u8 *)&b.end - (u8 *)&b == 24) { correct++; }
    Pair p = {5, 6};
    tests++; if (sizeof(Pair) == 16 && p.x == 5 && p.y == 6 && (u8 *)&p.y - (u8 *)&p == 8) { correct++; }

    Num n;
    n.whole = 0;
    n.lo = 9;
    tests++; if (sizeof(Num) == 8 && n.whole == 9 && n.alt == 9) { correct++; }
    Num n2 = {11};
    tests++; if (n2.whole == 11) { correct++; }

    Named nm;
    nm.c = 1; nm.u.i = 2; nm.v.f = 2.5; nm.pt.p = 3; nm.pt.q = 4;
    nm.up = &nm.u;
    tests++;
    if (sizeof(Named) == 32 && nm.u.i == 2 && nm.v.f == 2.5 &&
            nm.pt.q == 4 && nm.up.i == 2 && sizeof(NU) == 4 && sizeof(NP) == 8) { correct++; }
    Named nm2 = {1, {2}, {3}, {4, 5}, null};
    tests++; if (nm2.u.i == 2 && nm2.v.i == 3 && nm2.pt.p == 4 && nm2.pt.q == 5) { correct++; }

    Anon an = {1, 2, 3};
    tests++; if (sizeof(Anon) == 24 && an.a == 1 && an.i == 2 && an.z == 3) { correct++; }

    Y *yp = &y;
    yp.i = 42;
    tests++; if (yp.i == 42 && yp.z == 3) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
