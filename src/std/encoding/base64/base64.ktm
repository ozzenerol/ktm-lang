module base64;

/* Base64 (RFC 4648, v0.20.0): the standard alphabet (+ /, padded with =)
 * for MIME, Basic auth and PEM, and the URL-safe one (- _, no padding)
 * for JWTs, tokens and URLs.
 *
 *   String s = base64.encode("hi!");          // "aGkh"
 *   String raw;
 *   if (!base64.decode(s, &raw)) { ... }     // either alphabet, padded or not */

const u8 *STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
const u8 *URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/* How many characters encode(n bytes) makes (padded) */
pub i64 encodedLen(i64 n) {
    return (n + 2) / 3 * 4;
}

static String encodeWith(u8[] data, u8 *abc, bool pad) {
    String out;
    out.reserve(encodedLen(data.len));
    i64 i = 0;
    while (i + 2 < data.len) {
        u32 v = (u32)data[i] << 16 | (u32)data[i + 1] << 8 | (u32)data[i + 2];
        out.appendByte(abc[v >> 18 & 63]);
        out.appendByte(abc[v >> 12 & 63]);
        out.appendByte(abc[v >> 6 & 63]);
        out.appendByte(abc[v & 63]);
        i += 3;
    }
    i64 rest = data.len - i;
    if (rest) {
        u32 v = (u32)data[i] << 16;
        if (rest == 2) { v |= (u32)data[i + 1] << 8; }
        out.appendByte(abc[v >> 18 & 63]);
        out.appendByte(abc[v >> 12 & 63]);
        if (rest == 2) { out.appendByte(abc[v >> 6 & 63]); }
        else if (pad) { out.appendByte('='); }
        if (pad) { out.appendByte('='); }
    }
    return out;
}

/* data in the standard alphabet, padded: a new String, yours */
pub String encode(u8[] data) {
    return encodeWith(data, STD, true);
}

/* data in the URL-safe alphabet, without padding (as JWTs use it) */
pub String encodeUrl(u8[] data) {
    return encodeWith(data, URL, false);
}

static i64 value(u8 c) {
    if (c >= 'A' && c <= 'Z') { return c - 'A'; }
    if (c >= 'a' && c <= 'z') { return c - 'a' + 26; }
    if (c >= '0' && c <= '9') { return c - '0' + 52; }
    if (c == '+' || c == '-') { return 62; }
    if (c == '/' || c == '_') { return 63; }
    return -1;
}

/* text (either alphabet, padded or not; blanks and line breaks, as PEM
 * and MIME have, are skipped) into *out, replaced: false on anything
 * else, with *out empty */
pub bool decode(u8[] text, String *out) {
    out.clear();
    out.reserve(text.len / 4 * 3 + 3);
    u32 acc = 0;
    i64 n = 0, pads = 0;
    for (i64 i = 0; i < text.len; i++) {
        u8 c = text[i];
        if (c == ' ' || c == '\n' || c == '\r' || c == '\t') { continue; }
        if (c == '=') {
            pads++;
            continue;
        }
        i64 v = value(c);
        if (v < 0 || pads) {            /* a bad character, or data after padding */
            out.clear();
            return false;
        }
        acc = acc << 6 | (u32)v;
        n++;
        if (n == 4) {
            out.appendByte((u8)(acc >> 16));
            out.appendByte((u8)(acc >> 8));
            out.appendByte((u8)acc);
            acc = 0;
            n = 0;
        }
    }
    /* 2 or 3 characters left: 1 or 2 bytes; 1 left can't be */
    /* padding fills the last group to 4: 2 or 3 characters and = or == */
    if (n == 1 || pads > 2 || (pads && n + pads != 4)) {
        out.clear();
        return false;
    }
    if (n == 2) {
        out.appendByte((u8)(acc >> 4));
    } else if (n == 3) {
        out.appendByte((u8)(acc >> 10));
        out.appendByte((u8)(acc >> 2));
    }
    return true;
}
