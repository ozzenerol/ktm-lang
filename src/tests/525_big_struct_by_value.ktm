import test;

/* Structs past 32 KB by value (F34): on arm64 their copies used ldr/str
 * offsets beyond the scaled-immediate range, which the assembler
 * rejected (AOT) or the encoder cut to their low 12 bits (JIT: wrong
 * addresses). 40 KB and 1 MB structs are passed, returned and assigned,
 * with the fields at the far end read and written; an odd size has a
 * tail that isn't a whole word. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

const i64 BIG = 40000;
const i64 MEGA = 1048576;

struct Huge {
    i64 head;
    u8 mid[BIG];
    i64 tail;
};

struct Odd {
    u8 b[40003];
};

struct Mega {
    i64 head;
    u8 data[MEGA];
    i64 tail;
};

struct Pair {
    i64 a, b;
};

i64 hugeSum(Huge h) {
    i64 s = h.head + h.tail;
    for (i64 i = 0; i < BIG; i++) { s += h.mid[i]; }
    return s;
}

/* writes to its copy: the caller's stays as it was */
i64 hugeScribble(Huge h) {
    h.tail = -1;
    h.mid[BIG - 1] = 0;
    return h.tail;
}

Huge hugeMake(i64 seed) {
    Huge h;
    h.head = seed;
    for (i64 i = 0; i < BIG; i++) { h.mid[i] = (seed + i) & 0x7f; }
    h.tail = seed * 1000;
    return h;
}

/* a small struct back while a big one goes in: the return buffer's
 * address is kept above the big copy across the call */
Pair hugeEnds(Huge h) {
    Pair p;
    p.a = h.head;
    p.b = h.tail + h.mid[BIG - 1];
    return p;
}

/* two big ones: the second copy sits past the first */
i64 hugeTwo(Huge x, Huge y) {
    return x.tail - y.tail + x.mid[BIG - 1] - y.mid[0];
}

/* a big one after eight register arguments: passed on the stack */
i64 hugeNinth(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, i64 k, Huge h) {
    return a + b + c + d + e + f + g + k + h.tail + h.mid[BIG - 2];
}

i64 oddSum(Odd o) {
    i64 s = 0;
    for (i64 i = 0; i < 40003; i++) { s += o.b[i]; }
    return s;
}

Odd oddMake() {
    Odd o;
    for (i64 i = 0; i < 40003; i++) { o.b[i] = i % 251; }
    return o;
}

i64 megaEnds(Mega m) {
    return m.head + m.tail + m.data[0] + m.data[MEGA - 1];
}

Mega gMega;

Mega megaBump(Mega m) {
    m.tail++;
    m.data[MEGA - 1]++;
    return m;
}

i32 main() {
    printf("Test - big structs by value: ");

    Huge h = hugeMake(3);
    i64 want = 3 + 3000;
    for (i64 i = 0; i < BIG; i++) { want += (3 + i) & 0x7f; }
    check(h.tail == 3000 && h.mid[BIG - 1] == ((3 + BIG - 1) & 0x7f), "returned by value: far fields");
    check(hugeSum(h) == want, "passed by value: every byte arrives");
    check(hugeScribble(h) == -1 && h.tail == 3000 && h.mid[BIG - 1] == ((3 + BIG - 1) & 0x7f),
          "the callee writes to its own copy");

    Huge c = h;
    c.tail = 77;
    check(hugeSum(c) == want - 3000 + 77 && h.tail == 3000, "assigned: a separate copy");

    Pair p = hugeEnds(h);
    check(p.a == 3 && p.b == 3000 + ((3 + BIG - 1) & 0x7f), "big in, small struct back");

    Huge g = hugeMake(5);
    check(hugeTwo(h, g) == 3000 - 5000 + ((3 + BIG - 1) & 0x7f) - 5, "two big ones by value");
    check(hugeNinth(1, 2, 3, 4, 5, 6, 7, 8, h) == 36 + 3000 + ((3 + BIG - 2) & 0x7f),
          "a big one passed on the stack");

    Odd o = oddMake();
    i64 owant = 0;
    for (i64 i = 0; i < 40003; i++) { owant += i % 251; }
    check(oddSum(o) == owant && o.b[40002] == 40002 % 251, "an odd size: the tail bytes too");

    gMega.head = 11;
    gMega.tail = 22;
    gMega.data[0] = 33;
    gMega.data[MEGA - 1] = 44;
    check(megaEnds(gMega) == 110, "1 MB by value");
    Mega m = megaBump(gMega);
    check(m.tail == 23 && m.data[MEGA - 1] == 45 && m.head == 11 && m.data[0] == 33,
          "1 MB returned by value");
    check(gMega.tail == 22 && gMega.data[MEGA - 1] == 44, "the 1 MB original is unchanged");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
