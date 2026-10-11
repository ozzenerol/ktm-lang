#include "testhelper.ktm"

import mem, str;

struct Person {
    u8 name[10];
    i64 age;
};

auto personNew(u8 name[10], i64 age) {
    Person *p = mem.alloc(sizeof(Person));
    p.age = age;
    mem.copy(p.name,name,sizeof(p.name));
    return p;
}

auto createRandom() {
    return personNew("PersonA", 42);
}

auto add(i64 i, i64 x) {
    return i + x;
}

auto identity(i64 i) {
    return i;
}

bool variableAutos() {
    auto correct = 0, tests = 5;
    auto x = 1<<2,
             f = 4.5,
             ch = 'hello', 
             p = (Person *)mem.alloc(sizeof(Person));
    if (x + 4 == 8)    { correct++; }
    if (f + 4.5 == 9)  { correct++; }
    if (ch == 'hello') { correct++; }
    p.age = 42;
    mem.copy(p.name,"PersonB",7);
    if (p.age == 42) { correct++; }
    if (!str.cmpLen(p.name,"PersonB",7)) { correct++; }
    return correct == tests;
}

bool functionCalls() {
    i64 correct = 0, tests = 3;
    auto p = createRandom();
    if (p.age == 42 && !str.cmpLen(p.name,"PersonA",7)) { correct++; }
    if (identity(10) == 10) { correct++; }
    if (add(420,36) == 456) { correct++; }
    return correct == tests;
}

i32 main() {
    printf("Test - auto keyword: ");
    i64 correct = 0, tests = 2;
    if (variableAutos()) { correct++; }
    if (functionCalls()) { correct++; }
    PrintResult(correct,tests);
    printf("====\n");
    return 0;
}
