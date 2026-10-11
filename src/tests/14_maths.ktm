import os, test;

const i64 MATHS_MUL = 0;
const i64 MATHS_MOD = 1;
const i64 MATHS_SUB = 2;
const i64 MATHS_ADD = 3;
const i64 MATHS_DIV = 5;
const i64 MATHS_SHL = 6;
const i64 MATHS_SHR = 7;
const i64 MATHS_XMATHS_OR = 8;
const i64 MATHS_OR = 9;
const i64 MATHS_AND = 10;
const i64 MATHS_NOT = 11;

i64 testRunner(i64 i1, i64 i2, i64 op, i64 expected) {
        i64 answer = 0;
        if (op == MATHS_MUL) {
                answer = i1 * i2;
        } else if (op == MATHS_MOD) {
                answer = i1 % i2;
        } else if (op == MATHS_SUB) {
                answer = i1 - i2;
        } else if (op == MATHS_ADD) {
                answer = i1 + i2;
        } else if (op == MATHS_DIV) {
                answer = i1 / i2;
        } else if (op == MATHS_SHL) {
                answer = i1 << i2;
        } else if (op == MATHS_SHR) {
                answer = i1 >> i2;
        } else if (op == MATHS_XMATHS_OR) {
                answer = i1 ^ i2;
        } else if (op == MATHS_OR) {
                answer = i1 | i2;
        } else if (op == MATHS_AND) {
                answer = i1 & i2;
        } else if (op == MATHS_NOT) {
                answer = ~i1;
        } else {
                printf("operation: %d not supported\n", op);
                os.exit(1);
        }
        return answer == expected;
}

void mulTests() {
        i64 correct = 0;
        i64 total = 5;
        printf("Multiplication * : ");
        correct += testRunner(3, 4, MATHS_MUL, 12);
        correct += testRunner(-2, 6, MATHS_MUL, -12);
        correct += testRunner(-3, -4, MATHS_MUL, 12);
        correct += testRunner(5, 0, MATHS_MUL, 0);
        correct += testRunner(0, -5, MATHS_MUL, 0);
        test.printResult(correct, total);
}

void modTests() {
        i64 correct = 0;
        i64 total = 5;
        printf("Modulo %% : ");
        correct += testRunner(15, 4, MATHS_MOD, 3);
        correct += testRunner(20, 6, MATHS_MOD, 2);
        correct += testRunner(-13, 5, MATHS_MOD, -3);
        correct += testRunner(14, -3, MATHS_MOD, 2);
        correct += testRunner(-15, -4, MATHS_MOD, -3);
        test.printResult(correct, total);
}

void subTests() {
        i64 correct = 0;
        i64 total = 7;
        printf("Subtraction - : ");
        correct += testRunner(-12,16,MATHS_SUB,-28);
        correct += testRunner(12,-16,MATHS_SUB,28);
        correct += testRunner(0,16,MATHS_SUB,-16);
        correct += testRunner(24,16,MATHS_SUB,8);
        correct += testRunner(-8,-8,MATHS_SUB,0);
        correct += testRunner(-8,24,MATHS_SUB,-32);
        correct += testRunner(16,24,MATHS_SUB,-8);
        test.printResult(correct, total);
}

void addTests() {
        i64 correct = 0;
        i64 total = 5;
        printf("Addition + : ");
        correct += testRunner(10, 15, MATHS_ADD, 25);
        correct += testRunner(-10, 20, MATHS_ADD, 10);
        correct += testRunner(-5, -5, MATHS_ADD, -10);
        correct += testRunner(0, 10, MATHS_ADD, 10);
        correct += testRunner(20, 0, MATHS_ADD, 20);
        test.printResult(correct, total);
}

void divTests() {
        i64 correct = 0;
        i64 total = 3;
        printf("Division / : ");
        correct += testRunner(20, 5, MATHS_DIV, 4);
        correct += testRunner(-15, 3, MATHS_DIV, -5);
        correct += testRunner(-24, -4, MATHS_DIV, 6);
        test.printResult(correct,total);
}

void shlTests() {
        i64 correct = 0;
        i64 total = 3;
        printf("MATHS_SHL << : ");
        correct += testRunner(1, 2, MATHS_SHL, 4); // 1 << 2
        correct += testRunner(3, 3, MATHS_SHL, 24); // 3 << 3
        correct += testRunner(-2, 1, MATHS_SHL, -4); // -2 << 1
        test.printResult(correct, total);
}

void shrTests() {
        i64 correct = 0;
        i64 total = 3;
        printf("MATHS_SHR >> : ");
        correct += testRunner(4, 1, MATHS_SHR, 2); // 4 >> 1
        correct += testRunner(16, 3, MATHS_SHR, 2); // 16 >> 3
        correct += testRunner(-8, 2, MATHS_SHR, -2); // -8 >> 2
        test.printResult(correct, total);
}

void xorTests() {
        i64 correct = 0;
        i64 total = 3;
        printf("Xor ^: ");
        correct += testRunner(5, 3, MATHS_XMATHS_OR, 6);
        correct += testRunner(6, 2, MATHS_XMATHS_OR, 4);
        correct += testRunner(-1, 1, MATHS_XMATHS_OR, -2);
        test.printResult(correct, total);
}

void orTests() {
        i64 correct = 0;
        i64 total = 3;
        printf("Or |: ");
        correct += testRunner(5, 3, MATHS_OR, 7);
        correct += testRunner(4, 2, MATHS_OR, 6);
        correct += testRunner(-1, 1, MATHS_OR, -1);
        test.printResult(correct, total);
}

void andTests() {
        i64 correct = 0;
        i64 total = 3;
        printf("And &: ");
        correct += testRunner(5, 3, MATHS_AND, 1);
        correct += testRunner(6, 4, MATHS_AND, 4);
        correct += testRunner(-1, 1, MATHS_AND, 1);
        test.printResult(correct, total);
}

void notTests() {
        i64 correct = 0;
        i64 total = 3;
        printf("Not ~: ");
        correct += testRunner(5, 0, MATHS_NOT, -6); // MATHS_NOT operation usually only takes one operand
        correct += testRunner(-1, 0, MATHS_NOT, 0);
        correct += testRunner(0, 0, MATHS_NOT, -1);
        test.printResult(correct, total);
}

i32 main() {
    printf("Test Mathematics:\n");
        subTests();
        mulTests();
        modTests();
        addTests();
        divTests();
        shlTests();
        shrTests();
        xorTests();
        orTests();
        andTests();
        notTests();
        printf("=====\n");
        return 0;
}
