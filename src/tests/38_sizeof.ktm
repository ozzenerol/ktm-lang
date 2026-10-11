#include "./testhelper.ktm"

bool sizeofString() {
    if (sizeof("hello") != 6) { return false; }
    if (sizeof("h") != 2) { return false; }
    if (sizeof("hello world") != 12) { return false; }
    if (sizeof("hello" " world") != 12) { return false; }
    if (sizeof("hey" "how " "are " " you?") != 17) { return false; }
    return true;
}

bool sizeofChars() {
    /* This is very quirky */
    if (sizeof('f') != 1) { return false; }
    return true;
}

bool sizeofEscapedChars() {
    if (sizeof("\f\f\f") != 4) { return false; }
    if (sizeof("\\\\\\") != 4) { return false; }
    if (sizeof("\0\0\0") != 4) { return false; }
    if (sizeof("\n\n\n") != 4) { return false; }
    if (sizeof("\r\r\r") != 4) { return false; }
    if (sizeof("\t\t\t") != 4) { return false; }
    if (sizeof("\v\v\v") != 4) { return false; }
    if (sizeof("\"\"\"") != 4) { return false; }
    return true;
}

bool sizeofBytes() {
    /* Hex escapes count one byte each, plus the NUL like any string */
    if (sizeof("\xa3\xFF") != 3) { return false; }
    return true;
}

bool sizeofBuiltinTypes() {
        if (sizeof(void)   != 0) { return false; }
        if (sizeof(bool) != 1) { return false; }
        if (sizeof(i8)   != 1) { return false; }
        if (sizeof(u8)   != 1) { return false; }
        if (sizeof(i16)  != 2) { return false; }
        if (sizeof(u16)  != 2) { return false; }
        if (sizeof(i32)  != 4) { return false; }
        if (sizeof(u32)  != 4) { return false; }
        if (sizeof(i64)  != 8) { return false; }
        if (sizeof(u64)  != 8) { return false; }
        if (sizeof(f64)  != 8) { return false; }
        return true;
}

struct Type1 {
    i64 a;     /* 8 */
    i16 b;     /* 2 */
    u8 buf[8]; /* 8 */
};

struct Type2 {
    i64 a; /* 8 */
};

struct Type3 {
    i64 a;   /* 8 */
    i16 b;   /* 2.. */
    u8 *buf; /* 8 */
};

bool sizeofTypes() {
    /* Structs are aligned */
    if (sizeof(Type1) != 24) { return false; }
    if (sizeof(Type2) != 8) { return false; }

    return true;
}

bool sizeofPointer()
{ /* Our pointers are all of size 8 */
    u8 *u8Ptr;
    i16 *i16Ptr;
    i32 *i32Ptr;
    i64 *i64Ptr;

    if (sizeof(u8Ptr) != 8) { return false; }
    if (sizeof(i16Ptr) != 8) { return false; }
    if (sizeof(i32Ptr) != 8) { return false; }
    if (sizeof(i64Ptr) != 8) { return false; }

    return true;
}

bool sizeofArrays() {
    i8  array1[] = {1,2,3};
    i16 array2[] = {1,2,3};
    i32 array3[] = {1,2,3};
    i64 array4[] = {1,2,3};
    Type3 array5[] = {
        {1,1,"a"},
        {2,2,"b"},
        {3,3,"c"},
    };
    u8 *strs[] = {"hello", " world\n"};

    if (sizeof(array1) != sizeof(i8)  * 3) { return false; }
    if (sizeof(array2) != sizeof(i16) * 3) { return false; }
    if (sizeof(array3) != sizeof(i32) * 3) { return false; }
    if (sizeof(array4) != sizeof(i64) * 3) { return false; }
    if (sizeof(array5) != sizeof(Type3) * 3) { return false; }


    if (sizeof(array1) / sizeof(array1[0]) != 3) { return false; }
    if (sizeof(array2) / sizeof(array2[0]) != 3) { return false; }
    if (sizeof(array3) / sizeof(array3[0]) != 3) { return false; }
    if (sizeof(array4) / sizeof(array4[0]) != 3) { return false; }
    if (sizeof(array5) / sizeof(array5[0]) != 3) { return false; }
    if (sizeof(strs) / sizeof(strs[0]) != 2) { return false; }

    if (sizeof(strs)    != sizeof(u8 *) * 2) { return false; }
    if (sizeof(strs[0]) != 8) { return false; }
    if (sizeof(strs[1]) != 8) { return false; }

    return true;
}

i32 main() {
    printf("Test - sizeof(...): \n");
    i64 tests = 8;
    i64 correct = 0;

    if (sizeofString()) {
        correct++;
    } else {
        printf("Failed - SizeofString()\n");
    }

    if (sizeofChars()) {
        correct++;
    } else {
        printf("Failed - SizeofChars()\n");
    }

    if (sizeofEscapedChars()) {
        correct++;
    } else {
        printf("Failed - SizeofEscapedChars()\n");
    }

    if (sizeofBytes()) {
        correct++;
    } else {
        printf("Failed - SizeofBytes()\n");
    }

    if (sizeofBuiltinTypes()) {
        correct++;
    } else {
        printf("Failed - SizeofBuiltinTypes()\n");
    }

    if (sizeofTypes()) {
        correct++;
    } else {
        printf("Failed - SizeofTypes()\n");
    }

    if (sizeofPointer()) {
        correct++;
    } else {
        printf("Failed - SizeofPointer()\n");
    }

    if (sizeofArrays()) {
        correct++;
    } else {
        printf("Failed - SizeofArrays()\n");
    }

    PrintResult(correct,tests);
    printf("====\n");
    return 0;
}
