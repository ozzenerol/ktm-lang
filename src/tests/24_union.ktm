#include "testhelper.ktm"

import mem, str;

struct BaseClass {
    i64 base;
    u8 *baseName;
};

struct SuperClass:BaseClass {
    i64 super;
    u8 *superName;
};

bool testInheritanceOneLevel() {
    i64 correct = 0, tests = 4;
    SuperClass *s = mem.alloc(sizeof(SuperClass));

    s.base = 420;
    s.baseName = "base_class";
    s.super = 69;
    s.superName = "super_name";

    if (!str.cmp(s.baseName,"base_class")) { correct++; }
    if (s.base == 420) { correct++; }
    if (!str.cmp(s.superName,"super_name")) { correct++; }
    if (s.super == 69) { correct++; }

    mem.free(s);
    return correct == tests;
}

pub struct Another:SuperClass {
    i64 another;
    u8 *anotherName;
};

bool testInheritanceTwoLevels() {
    i64 correct = 0, tests = 6;
    Another *a = mem.alloc(sizeof(Another)); 

    a.base = 420;
    a.baseName = "base_class";
    a.super = 69;
    a.superName = "super_name";
    a.another = 42;
    a.anotherName = "another_name";

    if (a.base == 420) { correct++; }
    if (!str.cmp(a.baseName,"base_class")) { correct++; }

    if (a.super == 69) { correct++; }
    if (!str.cmp(a.superName,"super_name")) { correct++; }

    if (a.another == 42) { correct++; }
    if (!str.cmp(a.anotherName,"another_name")) { correct++; }

    mem.free(a);
    return correct == tests;
}

struct UnionClass {
    i64 type;
    union {
        u8 *name;
        bool isOk;
    };
};

union Cat {
    u8 *fish;
    i64 is;
};

bool testSimpleUnion() {
    i64 correct = 0, tests = 2;
    UnionClass *u = mem.alloc(sizeof(UnionClass));
    Cat cat;
    u.type = 0;
    u.name = "Foo";

    cat.fish = "food";
    if (!str.cmp(cat.fish,"food")) {
        correct++;
    }

    if (!str.cmp(u.name,"Foo")) {
        correct++;
    }
    mem.free(u);
    return correct == tests;
}

struct UnionClass2 {
    i64 type;
    union {
        struct {
            u8 *name;
            i64 age;
        };
        struct {
            u8 *car;
            i64 milage;
        };
    };
};

/**
 * This test looks very strange but is how unions work.
 * They allocate enough space for the largest member.
 * As both of the anonymous classes are the same size,
 * accessing a member of one references the other. ie:
 * car == name and age == milage
 */
bool testComplexUnion() {
    i64 correct = 0, tests = 4;
    UnionClass2 *u = mem.alloc(sizeof(UnionClass2));
    u.name = "Brian";
    u.age = 46;

    if (!str.cmp(u.name,"Brian") && u.age == 46) { correct++; }
    /* This is how unions work, despite it looking weird*/
    if (!str.cmp(u.car,"Brian") && u.milage == 46) { correct++; }

    u.car = "Suzuki";
    u.milage = 17;

    if (!str.cmp(u.car,"Suzuki") && u.milage == 17) { correct++; }
    if (!str.cmp(u.name,"Suzuki") && u.age == 17) { correct++; }

    mem.free(u);
    return correct == tests;
}


i32 main() {
    i64 correct = 0, tests = 4;
    printf("Test: Union and class inheritance: ");
    if (testInheritanceOneLevel()) { correct++; }
    if (testInheritanceTwoLevels()) { correct++; }
    if (testSimpleUnion()) { correct++; }
    if (testComplexUnion()) { correct++; }
    PrintResult(correct,tests);
    printf("====\n");
    return 0;
}
