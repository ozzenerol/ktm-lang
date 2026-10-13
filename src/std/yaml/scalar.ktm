module yaml;
import libc, mem, str;

/* YAML 1.2's core schema, which says what a plain (unquoted) scalar is:
 * null (empty, ~, null), a bool, an int (decimal, 0x hex, 0o octal) or a
 * float; anything else is a string. Quoted and block scalars are always
 * strings. */

/* Is s an integer, by its form? 0: no; 1: yes, its magnitude in *mag
 * and its sign in *neg; 2: an integer too big for a u64 */
i64 intForm(u8[] s, u64 *mag, bool *neg)
{
    i64 i = 0;
    u64 base = 10;
    *neg = false;
    *mag = 0;
    if (s.len > 2 && s.ptr[0] == '0' && (s.ptr[1] == 'x' || s.ptr[1] == 'o')) {
        base = s.ptr[1] == 'x' ? 16 : 8;
        i = 2;
    } else if (s.len && (s.ptr[0] == '-' || s.ptr[0] == '+')) {
        *neg = s.ptr[0] == '-';
        i = 1;
    }
    if (i >= s.len) { return 0; }
    u64 v = 0;
    bool over = false;
    for (; i < s.len; i++) {
        u8 ch = s.ptr[i];
        u64 d;
        if ('0' <= ch <= '9') { d = ch - '0'; }
        else if ('a' <= ch <= 'f') { d = ch - 'a' + 10; }
        else if ('A' <= ch <= 'F') { d = ch - 'A' + 10; }
        else { return 0; }
        if (d >= base) { return 0; }
        if (v > (0xFFFFFFFFFFFFFFFF - d) / base) { over = true; }
        else { v = v * base + d; }
    }
    *mag = v;
    return over ? 2 : 1;
}

static bool isDigit(u8 ch)
{
    return '0' <= ch <= '9';
}

/* [-+]?(\.[0-9]+|[0-9]+(\.[0-9]*)?)([eE][-+]?[0-9]+)?, or .inf/.nan;
 * *special: 1 for an infinity, 2 for a NaN */
static bool floatForm(u8[] s, i64 *special)
{
    u8 *b = s.ptr;
    i64 i = 0, n = s.len;
    *special = 0;
    if (i < n && (b[i] == '-' || b[i] == '+')) { i++; }
    u8[] rest = b[i:n];
    if (str.equals(rest, ".inf") || str.equals(rest, ".Inf") || str.equals(rest, ".INF")) {
        *special = 1;
        return true;
    }
    if (!i && (str.equals(s, ".nan") || str.equals(s, ".NaN") || str.equals(s, ".NAN"))) {
        *special = 2;
        return true;
    }
    i64 digits = 0;
    while (i < n && isDigit(b[i])) { i++; digits++; }
    if (i < n && b[i] == '.') {
        i++;
        while (i < n && isDigit(b[i])) { i++; digits++; }
    }
    if (!digits) { return false; }
    if (i < n && (b[i] == 'e' || b[i] == 'E')) {
        i++;
        if (i < n && (b[i] == '-' || b[i] == '+')) { i++; }
        if (i >= n || !isDigit(b[i])) { return false; }
        while (i < n && isDigit(b[i])) { i++; }
    }
    return i == n;
}

/* The kind of the plain scalar s: NULL, BOOL, INT (fits an i64), FLOAT
 * or STRING */
i64 scalarKind(u8[] s)
{
    if (!s.len) { return NULL; }
    switch (s.ptr[0]) {
        case '~': if (s.len == 1) { return NULL; }
        case 'n': if (str.equals(s, "null")) { return NULL; }
        case 'N': if (str.equals(s, "Null") || str.equals(s, "NULL")) { return NULL; }
        case 't': if (str.equals(s, "true")) { return BOOL; }
        case 'T': if (str.equals(s, "True") || str.equals(s, "TRUE")) { return BOOL; }
        case 'f': if (str.equals(s, "false")) { return BOOL; }
        case 'F': if (str.equals(s, "False") || str.equals(s, "FALSE")) { return BOOL; }
    }
    u64 mag;
    bool neg;
    switch (intForm(s, &mag, &neg)) {
        case 1:
            if (mag <= (neg ? 0x8000000000000000 : 0x7FFFFFFFFFFFFFFF)) { return INT; }
            return FLOAT;
        case 2:
            return FLOAT;
    }
    i64 special;
    if (floatForm(s, &special)) { return FLOAT; }
    return STRING;
}

/* The number s (an INT or FLOAT scalar) as an f64 */
f64 scalarFloat(u8[] s)
{
    i64 special;
    if (floatForm(s, &special) && special) {
        if (special == 2) { return libc.strtod("nan", null); }
        if (s.ptr[0] == '-') { return libc.strtod("-inf", null); }
        return libc.strtod("inf", null);
    }
    u64 mag;
    bool neg;
    i64 form = intForm(s, &mag, &neg);
    bool based = s.len > 2 && s.ptr[0] == '0' && (s.ptr[1] == 'x' || s.ptr[1] == 'o');
    if (form == 1 && mag <= 999999999999999) {
        f64 f = (f64)mag;
        return neg ? -f : f;
    }
    if (form && based) {   /* a big 0x/0o: strtod reads no 0o */
        f64 f = 0, base = s.ptr[1] == 'x' ? 16 : 8;
        for (i64 i = 2; i < s.len; i++) {
            u8 ch = s.ptr[i];
            i64 d = ch <= '9' ? ch - '0' : (ch | 0x20) - 'a' + 10;
            f = f * base + d;
        }
        return f;
    }
    /* strtod needs the text on its own */
    u8 small[64], *text = small;
    if (s.len >= 64) { text = mem.alloc(s.len + 1); }
    mem.copy(text, s.ptr, s.len);
    text[s.len] = 0;
    f64 f = libc.strtod(text, null);
    if (text != small) { mem.free(text); }
    return f;
}

/* cp as UTF-8 into out (4 bytes), its length */
i64 utf8Encode(u8 *out, i64 cp)
{
    if (cp < 0x80) {
        out[0] = cp;
        return 1;
    }
    if (cp < 0x800) {
        out[0] = 0xC0 | (cp >> 6);
        out[1] = 0x80 | (cp & 0x3F);
        return 2;
    }
    if (cp < 0x10000) {
        out[0] = 0xE0 | (cp >> 12);
        out[1] = 0x80 | ((cp >> 6) & 0x3F);
        out[2] = 0x80 | (cp & 0x3F);
        return 3;
    }
    out[0] = 0xF0 | (cp >> 18);
    out[1] = 0x80 | ((cp >> 12) & 0x3F);
    out[2] = 0x80 | ((cp >> 6) & 0x3F);
    out[3] = 0x80 | (cp & 0x3F);
    return 4;
}
