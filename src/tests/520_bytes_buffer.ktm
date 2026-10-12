import bytes, libc, str, test;

/* bytes.Buffer (v0.5.0): a byte queue that grows at the end and is
 * consumed from the front, room()/commit() for reads, readFrom/writeTo
 * over a pipe, and the big/little-endian integer helpers. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* The code a call throws, 0 when it doesn't */
enum Bad { consumeMore, consumeNeg, commitMore, be32Short, putPastEnd, atNeg };

i64 throwsCode(Bad what) {
    bytes.Buffer b = bytes.bufferNew();
    defer b.free();
    u8 small[3];
    b.append("abc");
    try {
        switch (what) {
            case Bad.consumeMore: b.consume(4);
            case Bad.consumeNeg:  b.consume(-1);
            case Bad.commitMore:  b.room(4); b.commit(b.cap + 1);
            case Bad.be32Short:   bytes.be32("abc");
            case Bad.putPastEnd:  bytes.putBe16(small, 2, 1);
            case Bad.atNeg:       bytes.le16("abcd", -1);
        }
    } catch (Error e) {
        return e.code;
    }
    return 0;
}

i32 main() {
    printf("Test - bytes.Buffer and endian helpers: ");

    bytes.Buffer b = bytes.bufferNew();
    defer b.free();
    check(b.len == 0 && b.data().len == 0, "a new Buffer is empty");

    b.append("hello ");
    b.append("world");
    b.appendByte('!');
    check(str.equals(b.data(), "hello world!"), "append and appendByte");

    b.consume(6);
    check(str.equals(b.data(), "world!"), "consume from the front");
    b.append(b.data()[0:5]);
    check(str.equals(b.data(), "world!world"), "append a view of its own data");

    /* consume everything, then the front is reused, nothing reallocated */
    u8 *was = b.ptr;
    i64 cap = b.cap;
    b.consume(b.len);
    check(b.len == 0 && b.start == 0, "consume all resets the front");
    b.append("again");
    check(b.ptr == was && b.cap == cap && str.equals(b.data(), "again"), "space is reused");

    /* a queue that never empties: appends at the end, consumes at the front,
     * stays within a small allocation (moves the data down, no growth) */
    b.clear();
    for (i64 i = 0; i < 10000; i++) {
        b.append("0123456789");
        b.consume(10);
    }
    check(b.len == 0 && b.cap <= 64, "a steady queue doesn't grow");

    /* growing keeps the bytes in order */
    b.clear();
    for (i64 i = 0; i < 1000; i++) { b.appendByte(i % 251); }
    bool ordered = b.len == 1000;
    for (i64 i = 0; ordered && i < 1000; i++) { ordered = b.data()[i] == i % 251; }
    check(ordered, "1000 bytes in order after growing");

    /* room + commit, as a read would */
    b.clear();
    u8[] r = b.room(16);
    check(r.len >= 16, "room gives at least n bytes");
    libc.memcpy(r.ptr, "abcd", 4);
    b.commit(4);
    check(str.equals(b.data(), "abcd"), "commit counts written bytes");

    /* readFrom / writeTo over a pipe */
    i32 fds[2];
    libc.pipe(fds);
    b.clear();
    b.append("through the pipe");
    i64 w = b.writeTo(fds[1]);
    check(w == 16 && b.len == 0, "writeTo writes and consumes");
    libc.close(fds[1]);
    i64 n1 = b.readFrom(fds[0], 7);
    i64 n2 = b.readFrom(fds[0]);
    i64 n3 = b.readFrom(fds[0]);
    libc.close(fds[0]);
    check(n1 == 7 && n2 == 9 && n3 == 0 && str.equals(b.data(), "through the pipe"),
          "readFrom reads in pieces, 0 at the end");

    /* integers, both byte orders */
    b.clear();
    b.appendBe16(0x0102);
    b.appendBe32(0x03040506);
    b.appendBe64(0x0708090A0B0C0D0E);
    b.appendLe16(0x0102);
    b.appendLe32(0x03040506);
    b.appendLe64(0x0708090A0B0C0D0E);
    u8[] d = b.data();
    check(d.len == 28, "28 bytes of integers");
    check(d[0] == 1 && d[1] == 2 && d[2] == 3 && d[5] == 6 && d[6] == 7 && d[13] == 14,
          "big-endian bytes");
    check(d[14] == 2 && d[15] == 1 && d[16] == 6 && d[19] == 3 && d[20] == 14 && d[27] == 7,
          "little-endian bytes");
    check(bytes.be16(d) == 0x0102 && bytes.be32(d, 2) == 0x03040506
          && bytes.be64(d, 6) == 0x0708090A0B0C0D0E, "be16/be32/be64 read back");
    check(bytes.le16(d, 14) == 0x0102 && bytes.le32(d, 16) == 0x03040506
          && bytes.le64(d, 20) == 0x0708090A0B0C0D0E, "le16/le32/le64 read back");
    check(bytes.be64("\xFF\xFF\xFF\xFF\xFF\xFF\xFF\xFE") == 0xFFFFFFFFFFFFFFFE, "be64 high bit");
    check(bytes.be16("\xAB\xCD") == 0xABCD && bytes.le32("\xFF\x00\x00\x80") == 0x800000FF,
          "high bytes are unsigned");

    u8 out[8];
    bytes.putBe32(out, 0, 0xDEADBEEF);
    bytes.putLe32(out, 4, 0xDEADBEEF);
    check(out[0] == 0xDE && out[3] == 0xEF && out[4] == 0xEF && out[7] == 0xDE, "putBe32/putLe32");
    bytes.putBe64(out, 0, 0x1122334455667788);
    check(bytes.be64(out) == 0x1122334455667788 && bytes.le64(out) == 0x8877665544332211,
          "putBe64, le64 of the same bytes");
    bytes.putLe16(out, 6, 0xBEEF);
    bytes.putBe16(out, 0, 0xBEEF);
    check(bytes.le16(out, 6) == 0xBEEF && bytes.be16(out) == 0xBEEF, "putLe16/putBe16");

    /* take: a String of the data, the Buffer empty after */
    b.clear();
    b.append("xyz-take");
    b.consume(4);
    String t = b.take();
    check(t.equals("take") && b.len == 0 && b.ptr == null, "take");
    t.free();
    b.append("usable after take");
    check(str.equals(b.data(), "usable after take"), "usable after take");
    b.free();
    b.free();
    check(b.ptr == null && b.len == 0, "free twice");

    /* misuse throws ERR_BOUNDS */
    check(throwsCode(Bad.consumeMore) == ERR_BOUNDS, "consume past the data");
    check(throwsCode(Bad.consumeNeg) == ERR_BOUNDS, "consume a negative count");
    check(throwsCode(Bad.commitMore) == ERR_BOUNDS, "commit past the room");
    check(throwsCode(Bad.be32Short) == ERR_BOUNDS, "be32 of 3 bytes");
    check(throwsCode(Bad.putPastEnd) == ERR_BOUNDS, "putBe16 past the end");
    check(throwsCode(Bad.atNeg) == ERR_BOUNDS, "a negative offset");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
