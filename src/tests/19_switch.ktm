import test;

bool testBreakInLoopCrazy() {
    i64 i,j,k;
    switch (1) {
        case 1:
            for (i = 0; i < 10; ++i) {
                for (j = 0; j < 10; ++j) {
                    for (k = 0; k < 10; ++k) {
                        if (k == 5) { break; }
                    }
                    if (j == 6) { break; }
                }
                if (i == 7) { break; }
            }
        case 2:  return false;
        default: return false;
    }

    return i == 7 && 
                 j == 6 && 
                 k == 5;
}

bool testBreakInLoop() {
    i64 i = 0;
    switch (1) {
        case 1:
            for (; i < 10; ++i) {
                if (i == 5) {
                    break; // This break should be part of the loop
                }
            }
        case 2:  return false;
        default: return false;
    }
    return i == 5;
}

bool unNamedCase() {
    i32 num = 0;
    for (i32 i = 0; i <= 5; ++i) {
        switch (i) {
            case: num++; //0
            case: num++; //1
            case 3: num++; //3
            case: num++; //4
            case: num++; //5
        }
    }
    return num == 5;
}

bool testOutOfRange() {
    i64 num = 2000;
    i64 isSet = 0;

    switch (num) {
        case 10: isSet = -1;
        case 20: isSet = -1;
        case 30: isSet = -1;
        default: isSet = 1;
    }

    return isSet == 1;
}

bool testNoBoundsSwitch() {
    i64 num = 0;
    for (i64 i = 0; i < 5; ++i) {
        switch [i] {
            case:   num++;
            case 1: num++;
            case 2: num++;
            case 4: num++;
            default: break;
        }
    }

    return num == 4;
}

/* `1...3` with no spaces used to lex `1.` as a float and fail. */
bool testRangeWithoutSpaces() {
    i64 small = 0, big = 0, hex = 0, letters = 0;
    for (i64 i = 0; i < 40; ++i) {
        switch (i) {
            case 1...3:       small++;
            case 7...9:       big++;
            case 0x10...0x1F: hex++;
        }
    }
    for (u8 *p = "a1b2c"; *p; ++p) {
        switch (*p) {
            case 'a'...'z': letters++;
        }
    }
    return small == 3 && big == 3 && hex == 16 && letters == 3;
}

i32 main(i32 argc, u8 **argv) {
    printf("Test - Switch statement: ");
    i64 correct = 0, tests = 8, count = 0;
    i64 i = 0;

    for (; i < 10; ++i) {
        switch (i) {
            case 1:  count++;
            case 2:  count++;
            case 3:  count++;
            case 7:  count++;
            case 8:  count++;
            case 10: count++;
            case 9:  count++;
            default: count--;
        }
    }
    if (count == 2) {
        correct++;
    }

    count ^= count;
    u8 *name = "hello", *ptr = name;
    while (*ptr) {
        switch (*ptr) {
            case 'h': count++;
            case 'e': count++;
            case 'l': count++;
            case 'o': count++;
            case 'z': count++;
            default:  count--;
        }
        ++ptr;
    }

    if (count == 5) {
        correct++;
    }

    if (testBreakInLoop())      { correct++; }
    if (testBreakInLoopCrazy()) { correct++; }
    if (unNamedCase())          { correct++; }
    if (testNoBoundsSwitch())   { correct++; }
    if (testOutOfRange())       { correct++; }
    if (testRangeWithoutSpaces()) { correct++; }

    test.printResult(correct,tests);
    return 0;
}
