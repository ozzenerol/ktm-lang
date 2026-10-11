import mem, str, test;

/* ktm syntax v0.2: true and false are bool literals (1 and 0) and null
 * is a null pointer (0 as a void *, so it converts to any pointer and to
 * function pointers, like C's null). true, false and null still work. */

struct Node {
    i64 v;
    Node *next;
};

bool gOn = true;
bool gOff = false;
u8 *gStr = null;
Node *gNode = null;
fn(i64) void gFn = null;

bool even(i64 x) {
    if (x % 2) { return false; }
    return true;
}

Node *find(Node *n, i64 v) {
    for (; n != null; n = n.next) { if (n.v == v) { return n; } }
    return null;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - true, false and null: ");

    tests++;
    if (true == 1 && false == 0 && true == true && false == false && null == null) {
        correct++;
    } else { printf("\n  values\n"); }

    tests++;   /* globals initialised with them */
    if (gOn && !gOff && gStr == null && gNode == null && gFn == null) { correct++; }
    else { printf("\n  globals\n"); }

    tests++;   /* locals of every pointer kind, without a cast or a warning */
    bool b = true;
    u8 *s = null;
    Node *n = null;
    fn() void fn_ = null;
    i64 **pp = null;
    if (b && !s && !n && !fn_ && !pp) { correct++; }
    else { printf("\n  locals\n"); }

    tests++;   /* returned, compared, in conditions */
    Node a, c;
    a.v = 1;
    a.next = &c;
    c.v = 2;
    c.next = null;
    i64 loops = 0;
    while (false) { loops++; }
    if (true && !false) { loops += 10; }
    if (even(4) == true && even(3) == false && find(&a, 2) == &c &&
            find(&a, 9) == null && loops == 10) { correct++; }
    else { printf("\n  expressions\n"); }

    tests++;   /* arithmetic with bool, sizeof */
    i64 i = true + true + false;
    if (i == 2 && sizeof(true) == 1 && sizeof(null) == 8) { correct++; }
    else { printf("\n  arithmetic: %d %d %d\n", i, sizeof(true), sizeof(null)); }

    tests++;   /* null is a pointer: it can be passed where one is expected */
    mem.free(null);
    if (str.len(str.formatTo(null, "%d", true)) == 1) { correct++; }
    else { printf("\n  as an argument\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
