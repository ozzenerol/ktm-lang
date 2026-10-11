#include "testhelper.ktm"

import mem, str;

struct Class1 {
    i64 val;
};

struct Class2 {
    Class1 *c1;
    i64 val2;
};

struct Student {
    i64 id, age;
    u8 name[8];
};

struct SuperStudent {
    Student *s;
    i64 id;
};

i32 main() {
    printf("Test - class definition: ");
    i64 correct = 0, tests = 6;
    Class2 *c2 = mem.alloc(sizeof(Class2));
    c2.val2 = 10;

    if (c2.val2 == 10) {
        ++correct;
    }

    SuperStudent *spr = mem.alloc(sizeof(SuperStudent));

    Student *s = mem.alloc(sizeof(Student));
    s.id = 420;
    s.name[0] = 'J';
    s.name[1] = 'a';
    s.name[2] = 'm';
    s.name[3] = 'e';
    s.name[4] = 's';
    s.name[5] = '\0';
    s.age = 31;

    if (s.id == 420) { ++correct; }
    if (s.age == 31) { ++correct; }
    if (!str.cmpLen(s.name,"James",5)) { ++correct; }

    spr.s = mem.alloc(sizeof(Student));
    spr.id = 420;
    spr.s.id = 419;
    spr.s.name[0] = 'W';
    spr.s.name[1] = 'i';
    spr.s.name[2] = 'l';
    spr.s.age = 40;
    spr.s.name[3] = '\0';
    ++spr.s.age;

    if (!str.cmpLen(spr.s.name,"Wil",3)) { ++correct; }
    if (spr.s.age == 41) { ++correct; }

    PrintResult(correct,tests);
    mem.free(c2);
    mem.free(s);
    printf("====\n");
    return 0;
}
