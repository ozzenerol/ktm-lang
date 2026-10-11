#include "./testhelper.ktm"

import fs, libc, str;

#define TEST_FILE "__file__.txt"
#define TEST_DATA "hello\n"

bool failAndCleanup(void) {
    fs.remove(TEST_FILE);
    return false;
}

bool successAndCleanup(void) {
    fs.remove(TEST_FILE);
    return true;
}

bool testWriteFile(void) {
    auto flags = libc.O_CREAT|libc.O_RDWR;
    auto ok = fs.writeAll(TEST_FILE,TEST_DATA,6,flags);
    if (ok == false) { return failAndCleanup(); }

    auto buffer = fs.readAll(TEST_FILE);
    auto len = str.len(buffer);
    if (len != 6) { return failAndCleanup(); }
    if (str.cmpLen(buffer,TEST_DATA,len) != 0) { return failAndCleanup(); }

    return successAndCleanup();
}

bool testTruncFile(void)
{ /* We write and write the same thing with the truncation flag set meaning
     * we should only have one copy of TEST_DATA */
    auto flags = libc.O_CREAT|libc.O_RDWR|libc.O_TRUNC;

    auto ok = fs.writeAll(TEST_FILE,TEST_DATA,6,flags);
    if (ok == false) { return failAndCleanup(); }
    ok = fs.writeAll(TEST_FILE,TEST_DATA,6,flags);
    if (ok == false) { return failAndCleanup(); }
    ok = fs.writeAll(TEST_FILE,TEST_DATA,6,flags);
    if (ok == false) { return failAndCleanup(); }
    ok = fs.writeAll(TEST_FILE,TEST_DATA,6,flags);
    if (ok == false) { return failAndCleanup(); }

    auto buffer = fs.readAll(TEST_FILE);
    auto len = str.len(buffer);
    if (len != 6) { return failAndCleanup(); }
    if (str.cmpLen(buffer,TEST_DATA,len) != 0) { return failAndCleanup(); }

    return successAndCleanup();
}

bool testAppendFile(void) {
    auto flags = libc.O_CREAT|libc.O_RDWR|libc.O_APPEND;

    auto ok = fs.writeAll(TEST_FILE,TEST_DATA,6,flags);
    if (ok == false) { return failAndCleanup(); }
    ok = fs.writeAll(TEST_FILE,TEST_DATA,6,flags);
    if (ok == false) { return failAndCleanup(); }
    ok = fs.writeAll(TEST_FILE,TEST_DATA,6,flags);
    if (ok == false) { return failAndCleanup(); }
    ok = fs.writeAll(TEST_FILE,TEST_DATA,6,flags);
    if (ok == false) { return failAndCleanup(); }

    auto buffer = fs.readAll(TEST_FILE);

    auto len = str.len(buffer);
    if (len != 6*4) { return failAndCleanup(); }
    if (str.cmpLen(buffer,TEST_DATA TEST_DATA TEST_DATA TEST_DATA,6*4) != 0) { 
        return failAndCleanup();
    }

    return successAndCleanup();
}

bool testReadOnlyFile(void) {
    auto flags = libc.O_CREAT|libc.O_RDONLY;
    auto ok = fs.writeAll(TEST_FILE,TEST_DATA,6,flags);
    /* We want the write to fail as the user should not have permissions to be 
     * able to write to the file as the write flag has not been set. */
    if (ok != false) { return failAndCleanup(); }
    return successAndCleanup();
}

i32 main() {
    printf("Test - basic io: ");
    i64 correct = 0, tests = 4;
    if (testWriteFile()) { correct++; }
    if (testTruncFile()) { correct++; }
    if (testAppendFile()) { correct++; }
    if (testReadOnlyFile()) { correct++; }
    PrintResult(correct,tests);
    printf("====\n");
    return 0;
}
