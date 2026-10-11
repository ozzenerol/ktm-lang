#include "testhelper.ktm"

import fzf;

const i64 STRINGS_LEN = 9;

bool testStrings(u8 **strings, u8 *pattern, i32 expectedMatches, i32 case_mode, bool fuzzy) {
    i32 matches = 0;
    for (auto i = 0; i < STRINGS_LEN; ++i) {
        auto res = fzf.match(strings[i],pattern,case_mode,fuzzy);
        if (res) {
            matches++;
            fzf.release(res);
        }
    }
    return matches == expectedMatches;
}

bool testNoMatch(u8 **strings) {
    return testStrings(strings,"HEY HOW ARE YOU?",0,fzf.CASE_SMART,true);
}

bool testSuffix(u8 **strings) {
    return testStrings(strings,"ktm$",2,fzf.CASE_SMART,true);
}

bool testSuffixCaseInsensative(u8 **strings) {
    return testStrings(strings,"M$",2,fzf.CASE_IGNORE,true);  /* the .ktm names */
}

bool testPrefix(u8 **strings) {
    return testStrings(strings,"^He",2,fzf.CASE_SMART,true);
}

bool testPrefixCaseInsensative(u8 **strings) {
    return testStrings(strings,"^w",4,fzf.CASE_IGNORE,true);
}

bool textMatchExact(u8 **strings) {
    return testStrings(strings,"'hey.c",1,fzf.CASE_RESPECT,false);
}

bool textMatchExactCaseInsensative(u8 **strings) {
    return testStrings(strings,"'hey.c",2,fzf.CASE_IGNORE,false);
}

i32 main() {
    printf("Test - FzF fuzzy finding: ");
    i32 tests = 7, correct = 0;
    u8 *strings[STRINGS_LEN] = {
        "Hello.ktm",
        "Hey.c",
        "hey.c",
        "woof",
        "foo",
        "WHO",
        "wHo",
        "another.ktm",
        "woo"
    };

    if (testNoMatch(strings)) { correct++; }
    if (testSuffix(strings)) { correct++; }
    if (testSuffixCaseInsensative(strings)) { correct++; }
    if (testPrefix(strings)) { correct++; }
    if (testPrefixCaseInsensative(strings)) { correct++; }
    if (textMatchExact(strings)) { correct++; }
    if (textMatchExactCaseInsensative(strings)) { correct++; }

    PrintResult(correct,tests);
    printf("====\n");
    return 0;
}
