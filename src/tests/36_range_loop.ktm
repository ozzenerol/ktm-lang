import test;

import mem, vec;

const i64 TEST_CAPACITY = 32;

struct Container {
    i64 *entries;
    i64 size;
};

bool testContainerLoop(void) {
    Container *c = mem.alloc(sizeof(Container));
    c.entries = mem.alloc(sizeof(i64) * TEST_CAPACITY);
    c.size = 0;
    c.entries[c.size++] = 11;
    c.entries[c.size++] = 22;
    c.entries[c.size++] = 32;
    c.entries[c.size++] = 43;
    i64 cases[] = {11,22,32,43};
    i64 i = 0;

    for (auto it : c) {
        if (cases[i] != it) {
            break;
        }
        i++;
    }

    mem.free(c.entries);
    mem.free(c);
    return i == sizeof(cases)/sizeof(cases[0]);
}

struct StaticContainer {
    i64 entries[8];
    i64 size;
};

bool testStaticContainerEntriesLoop(void) {
    StaticContainer *c = mem.alloc(sizeof(StaticContainer));
    c.size = 0;
    c.entries[c.size++] = 91;
    c.entries[c.size++] = 79;
    c.entries[c.size++] = 33;
    c.entries[c.size++] = 47;
    i64 cases[] = {91,79,33,47};
    i64 i = 0;

    for (auto it : c) {
        if (cases[i] != it) {
            break;
        }
        i++;
    }

    mem.free(c);
    return i == sizeof(cases)/sizeof(cases[0]);
}

struct StructWithArray {
    i64 entries[8];
};

bool testStructWithArrayLoop(void) {
    StructWithArray *c = mem.alloc(sizeof(StructWithArray));
    i64 size = 0;
    c.entries[size++] = 91;
    c.entries[size++] = 79;
    c.entries[size++] = 33;
    c.entries[size++] = 47;

    c.entries[size++] = 102;
    c.entries[size++] = 14;
    c.entries[size++] = 90;
    c.entries[size++] = 77;
    i64 cases[] = {91,79,33,47,102,14,90,77};
    i64 i = 0;

    /* Can range over arrays in structs */
    for (auto it : c.entries) {
        if (cases[i] != it) {
            break;
        }
        i++;
    }

    mem.free(c);
    return i == sizeof(cases)/sizeof(cases[0]);
}

bool testFloatVecLoop(void) {
    vec.FloatVec *fvec = vec.floatNew();

    f64 cases[] = {8.8, 4.2, 3.8};
    i64 i = 0;
    fvec.push(8.8);
    fvec.push(4.2);
    fvec.push(3.8);

    for (auto it : fvec) {
        if (cases[i] != it) {
            break;
        }
        i++;
    }

    fvec.release();
    return i == sizeof(cases)/sizeof(cases[0]);
}

struct BoxInt {
    i64 i;
};

BoxInt *bInt(i64 i) {
    BoxInt *b = mem.alloc(sizeof(BoxInt));
    b.i = i;
    return b;
}

bool testPtrVecLoop(void) {
    vec.PtrVec *vec = vec.ptrNew();
    vec.push(bInt(1));
    vec.push(bInt(4));
    vec.push(bInt(8));
    vec.push(bInt(9));
    i64 cases[] = {1,4,8,9};
    i64 i = 0;

    for (BoxInt *b : vec) {
        if (cases[i] != b.i) {
            break;
        }
        i++;
        mem.free(b);
    }
    return i == sizeof(cases)/sizeof(cases[0]);
}

bool testArrayLoop(void) {
    f64 arr[]   = {9.2, 8.1, 7.8, 5.6, 3.33, 1.12313};
    f64 cases[] = {9.2, 8.1, 7.8, 5.6, 3.33, 1.12313};
    i64 i = 0;

    for (auto it : arr) {
        if (it != cases[i]) {
            break;
        }
        i++;
    }

    return i == sizeof(cases)/sizeof(cases[0]);
}

struct C2 {
    vec.IntVec *v;
};

struct C1 {
    i32 padding;
    C2 *c2;
};

bool testNested(void) {
    C1 c1;
    c1.c2 = mem.alloc(sizeof(C2));
    c1.c2.v = vec.intNew();

    c1.c2.v.push(10);
    c1.c2.v.push(100);
    c1.c2.v.push(101);
    c1.c2.v.push(1011);

    i64 cases[] = {10,100,101,1011};
    i64 i = 0;

    for (auto it : c1.c2.v) {
        if (cases[i] != it) {
            break;
        }
        i++;
    }

    c1.c2.v.release();
    mem.free(c1.c2);
    return i == sizeof(cases)/sizeof(cases[0]);
}

i32 main(void) {
    printf("Test - Range loop: \n");
    i64 correct = 0, tests = 7;
    if (testContainerLoop()) { correct++; }
    if (testStaticContainerEntriesLoop()) { correct++; }
    if (testStructWithArrayLoop()) { correct++; }
    if (testFloatVecLoop()) { correct++; }
    if (testPtrVecLoop()) { correct++; }
    if (testArrayLoop()) { correct++; }
    if (testNested()) { correct++; }
    test.printResult(correct,tests);
    printf("====\n");
    return 0;
}
