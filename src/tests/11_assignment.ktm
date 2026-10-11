import test;

i64 identity(i64 i) {
    return i;
}

void assign() {
    i64 correct = 0, tests = 2;
    printf("= test: ");

    i64 i = 10;
    if (i == 10) {
        correct++;
    }

    i = identity(69);
    if (i == 69) {
        correct++;
    }
    test.printResult(correct,tests);
}

void addAssign() {
    i64 correct = 0, tests = 2;
    printf("+= test: ");
    i64 i = 10;

    i += 20;
    if (i == 30) {
        correct++;
    }

    i += identity(69);
    if (i == 99) {
        correct++;
    }
    test.printResult(correct,tests);
}

void subAssign() {
    i64 correct = 0, tests = 2;
    printf("-= test: ");
    i64 i = 10;

    i -= 20;
    if (i == -10) {
        correct++;
    }

    i -= identity(10);
    if (i == -20) {
        correct++;
    }
    test.printResult(correct,tests);
}

void mulAssign() {
    i64 correct = 0, tests = 2;
    printf("*= test: ");
    i64 i = 10;

    i *= 10;  
    if (i == 100) {
        correct++;
    }

    i *= identity(2);
    if (i == 200) {
        correct++;
    }
    test.printResult(correct,tests);
}

void divAssign() {
    i64 correct = 0, tests = 2;
    printf("/= test: ");
    i64 i = 100;

    i /= 10;
    if (i == 10) {
        correct++;
    }

    i /= identity(10);
    if (i == 1) {
        correct++;
    }

    test.printResult(correct,tests);
}

void modAssign() {
    i64 correct = 0, tests = 2;
    printf("%%= test: ");
    i64 i = 35;

    i %= 32;
    if (i == 3) {
        correct++;
    }

    i %= identity(2);
    if (i == 1) {
        correct++;
    }
    test.printResult(correct,tests);
}

void orAssign() {
    i64 correct = 0, tests = 2;
    printf("|= test: ");
    i64 i = 1;

    i |= 8;
    if (i == 9) {
        correct++;
    }

    i |= identity(4);
    if (i == 13) {
        correct++;
    }
    test.printResult(correct,tests);
}

void andAssign() {
    i64 correct = 0, tests = 2;
    printf("&= test: ");
    i64 i = 11;

    i &= 1;
    if (i == 1) {
        correct++;
    }

    i &= identity(4);
    if (i == 0) {
        correct++;
    }

    test.printResult(correct,tests);
}

void xorAssign()
{ // Toggling bits
    i64 correct = 0, tests = 2;
    printf("^= test: ");
    i64 i = 8;

    i ^= 1;
    if (i == 9) {
        correct++;
    }

    i ^= identity(1);
    if (i == 8) {
        correct++;
    }
    test.printResult(correct,tests);
}

void shrAssign() {
    i64 correct = 0, tests = 2;
    printf(">>= test: ");
    i64 i = 1024;

    i >>= 1;
    if (i == 512) {
        correct++;
    }

    i >>= identity(1);
    if (i == 256) {
        correct++;
    }
    test.printResult(correct,tests);
}

void shlAssign() {
    i64 correct = 0, tests = 2;
    printf("<<= test: ");
    i64 i = 1;

    i <<= 10;
    if (i == 1024) {
        correct++;
    }

    i <<= identity(1);
    if (i == 2048) {
        correct++;
    }
    test.printResult(correct,tests);
}

i32 main() {
    printf("Assignment tests:\n");
    assign();
    addAssign();
    subAssign();
    mulAssign();
    divAssign();
    modAssign();
    andAssign();
    orAssign();
    xorAssign();
    shrAssign();
    shlAssign();
    printf("=====\n");
    return 0;
}
