module hex;

/* Hexadecimal (v0.20.0): two digits a byte.
 *
 *   String s = hex.encode("\x01\xab");       // "01ab"
 *   String raw;
 *   if (!hex.decode("01AB", &raw)) { ... }   // either case */

const u8 *DIGITS = "0123456789abcdef";

/* data as lowercase hex: a new String, yours */
pub String encode(u8[] data) {
    String out;
    out.reserve(data.len * 2);
    for (i64 i = 0; i < data.len; i++) {
        out.appendByte(DIGITS[data[i] >> 4]);
        out.appendByte(DIGITS[data[i] & 15]);
    }
    return out;
}

static i64 digit(u8 c) {
    if (c >= '0' && c <= '9') { return c - '0'; }
    if (c >= 'a' && c <= 'f') { return c - 'a' + 10; }
    if (c >= 'A' && c <= 'F') { return c - 'A' + 10; }
    return -1;
}

/* text (an even number of hex digits, either case) into *out, replaced:
 * false on anything else, with *out empty */
pub bool decode(u8[] text, String *out) {
    out.clear();
    if (text.len % 2) { return false; }
    out.reserve(text.len / 2);
    for (i64 i = 0; i < text.len; i += 2) {
        i64 h = digit(text[i]), l = digit(text[i + 1]);
        if (h < 0 || l < 0) {
            out.clear();
            return false;
        }
        out.appendByte((u8)(h * 16 + l));
    }
    return true;
}
