#include "testhelper.ktm"

import mem, str;

/* The "flexible array" trick: a trailing `void body;` member is a
 * zero-size placeholder whose address points to the byte just past the
 * real fields. Allocate `sizeof(Hdr) + N`, then read/write N payload
 * bytes via `&hdr->body`. */

struct Hdr {
    i64 size;
    /* Flexibly array member */
    void  body;
};

bool layoutMatchesSizeof() {
    Hdr *h = mem.alloc(64);
    i64 base   = (u8 *)h;
    i64 bodyat = &h.body;
    i64 ok = sizeof(Hdr) == 8 && bodyat - base == 8;
    mem.free(h);
    return ok;
}

bool roundTripPayload() {
    u8 *src = "hello";
    i64 n = 5;
    /* Creating the flexible Array, all be it dynamically. You can only `MemCpy`
     * in and out of it*/
    Hdr *h = mem.alloc(sizeof(Hdr) + n);
    h.size = n;
    mem.copy(&h.body, src, n);

    u8 buf[8];
    mem.copy(buf, &h.body, h.size);
    buf[h.size] = 0;

    bool ok = h.size == 5 && !str.cmp(buf, "hello");
    mem.free(h);
    return ok;
}

struct HdrMixed {
    i64 a;
    u8  b;
    void  body;
};

bool mixedFieldsAligned() {
    HdrMixed *h = mem.alloc(64);
    i64 base   = (u8 *)h;
    i64 bodyat = &h.body;
    /* Real fields occupy 0..8 (i64) + 8..9 (u8) = 9 bytes. body sits at
     * offset 9; class size pads up to the next 8-byte boundary. */
    bool ok = bodyat - base == 9 && sizeof(HdrMixed) == 16;
    mem.free(h);
    return ok;
}

i32 main() {
    printf("Test - Trailing void placeholder fields: ");
    i64 correct = 0, tests = 3;
    if (layoutMatchesSizeof()) { correct++; }
    if (roundTripPayload()) { correct++; }
    if (mixedFieldsAligned()) { correct++; }
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
