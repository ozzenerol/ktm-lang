module json;
import libc, mem, str;

const i64 JSON_PARSER_STRING = 0;

const i64 JSON_PARSER_NUMERIC = 1;

const i64 JSON_PARSER_ARRAY = 2;

const i64 JSON_PARSER_OBJECT = 3;

const i64 JSON_PARSER_BOOL = 4;

const i64 JSON_PARSER_NULL = 5;

const i64 JSON_SEL_INVALD = (0);

const i64 JSON_SEL_OBJ = (1);

const i64 JSON_SEL_ARRAY = (2);

const i64 JSON_SEL_TYPECHECK = (3);

const i64 JSON_SEL_MAX_BUF = (256);

pub struct State {
        u8 ch;
        i64 offset;
        i64 error;
        i64 line;   /* 1-based line and byte column of `offset` */
        i64 col;
};

pub struct Value;

pub struct Value {
        State *state;
        Value *next;
        i64 type;
        u8 *key;
        union
        {
                Value *array;
                Value *object;
                u8 *str;
                u8 *strnum;
                f64 floatVal;
                i64 intVal;
                bool boolean;
        };
        /* Bytes in `str` (a JSON_STRING) and in `key`, before their '\0': a
            * string can hold \u0000, which the '\0' termination would cut off.
            * Set them when you change `str` or `key` (0 means "up to the '\0'").
            * JsonSelect matches keys up to their first '\0'. */
        i64 strLen;
        i64 keyLen;
};

struct JsonParser {
        i32 type;
        u8 *buffer;
        i64 offset;
        i64 buflen;
        i32 error;
        Value *J;
        Value *ptr;
        i32 flags;
        State *state;
        i64 depth; /* arrays/objects open around the current value */
};

struct JsonStringBuilder {
        u8 *buf;
        i64 len;
};

static Value *jsonNew() {
        Value *J = mem.alloc(sizeof(Value));
        J.type = NULL;
        J.key = null;
        J.next = null;
        J.state = null;
        J.strLen = 0;
        J.keyLen = 0;
        return J;
}

static State *jsonStateNew() {
        State *jsonState = mem.alloc(sizeof(State));
        jsonState.error = OK;
        jsonState.ch = '\0';
        jsonState.offset = 0;
        return jsonState;
}

static void jsonUnsafeAdvanceBy(JsonParser *p, i64 jmp) {
        p.offset += jmp;
}

static bool jsonCanAdvanceBy(JsonParser *p, i64 jmp) {
        return (p.offset + jmp) < p.buflen;
}

/* Moving past the last character is fine (a top-level value can end the
    * input), moving past the end is not */
static void jsonAdvance(JsonParser *p) {
        if (p.offset < p.buflen) {
                ++p.offset;
                return;
        }
        p.error = EOF;
}

/* Are there `n` more characters, starting at the current one? */
static bool jsonHasChars(JsonParser *p, i64 n) {
        return p.offset + n <= p.buflen;
}

static bool jsonAdvanceToError(JsonParser *p, i64 jmp, i32 errorCode) {
        p.error = errorCode;
        jsonUnsafeAdvanceBy(p, jmp);
        return 0;
}

static u8 jsonPeek(JsonParser *p) {
        if (p.offset >= p.buflen) { return '\0'; }
        return p.buffer[p.offset];
}

static u8 jsonUnsafePeekAt(JsonParser *p, i64 idx) {
        return p.buffer[idx];
}

/* JSON's whitespace is exactly space, tab, LF and CR (not \v or \f) */
static void jsonAdvanceWhitespace(JsonParser *p) {
        while (p.offset < p.buflen) {
                u8 ch = p.buffer[p.offset];
                if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') { break; }
                p.offset++;
        }
}

/* Length of the number at the current offset, RFC 8259's grammar
    * -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?, ending at the input's
    * end, whitespace, ',', ']' or '}'. Anything else (`01`, `+1`, `.5`,
    * `1.`, `0x1F`, `1e`, `1a`, `--1`) sets JSON_INVALID_NUMBER and returns
    * 0. *_is_int: no fraction and no exponent. */
static i64 jsonNumberLen(JsonParser *p, bool *_is_int) {
        i64 i = p.offset, n = p.buflen;
        u8 *b = p.buffer;
        *_is_int = true;
        if (i < n && b[i] == '-') { i++; }
        if (i >= n || !('0' <= b[i] <= '9')) { goto bad; }
        if (b[i] == '0') {
                i++;
        } else {
                while (i < n && '0' <= b[i] <= '9') { i++; }
        }
        if (i < n && b[i] == '.') {
                *_is_int = false;
                i++;
                if (i >= n || !('0' <= b[i] <= '9')) { goto bad; }
                while (i < n && '0' <= b[i] <= '9') { i++; }
        }
        if (i < n && (b[i] == 'e' || b[i] == 'E')) {
                *_is_int = false;
                i++;
                if (i < n && (b[i] == '+' || b[i] == '-')) { i++; }
                if (i >= n || !('0' <= b[i] <= '9')) { goto bad; }
                while (i < n && '0' <= b[i] <= '9') { i++; }
        }
        if (i < n && b[i] != ' ' && b[i] != '\t' && b[i] != '\n' &&
                        b[i] != '\r' && b[i] != ',' && b[i] != ']' && b[i] != '}') {
                goto bad;
        }
        return i - p.offset;

bad:
        /* Point the error at the offending character */
        if (i > n) { i = n; }
        p.offset = i;
        p.error = INVALID_NUMBER;
        return 0;
}

/* Does the integer `text` (an optional '-' then digits, no leading
    * zeros) fit in an i64? */
static bool jsonIntFits(u8 *text, i64 len) {
        u8 *max = "9223372036854775807";
        if (*text == '-') {
                max = "9223372036854775808";
                text++;
                len--;
        }
        if (len != 19) { return len < 19; }
        return str.cmpLen(text, max, 19) <= 0;
}

static void jsonParseNumber(JsonParser *p) {
        auto current = p.ptr;
        bool isInt;
        i64 numLen = jsonNumberLen(p, &isInt);
        if (!numLen) { return; }

        /* strtoll / strtod need the text on its own: `len` may end the input
            * mid-buffer, with more digits after it */
        u8 small[64], *text = small;
        if (numLen >= 64) { text = mem.alloc(numLen + 1); }
        mem.copy(text, p.buffer + p.offset, numLen);
        text[numLen] = '\0';

        /* An integer outside i64 is read as an f64 (JSON_FLOAT), as JavaScript
            * does; strtoll would saturate it silently. 1e400 is inf, which
            * JsonToString writes as null. */
        if (isInt && jsonIntFits(text, numLen)) {
                current.intVal = libc.strtoll(text, null, 10);
                current.type = INT;
        } else {
                current.floatVal = libc.strtod(text, null);
                current.type = FLOAT;
        }
        if (text != small) { mem.free(text); }
        jsonUnsafeAdvanceBy(p, numLen);
}

/* With JSON_STRNUM_FLAG: keep a number as the text it was written as
    * (`J->strnum`), so nothing is lost to i64 / f64 conversion. It is
    * checked like any other number. */
static void jsonParseStrNum(JsonParser *p) {
        auto current = p.ptr;
        bool isInt;
        i64 numLen = jsonNumberLen(p, &isInt);
        if (!numLen) { return; }

        current.strnum = mem.alloc(numLen + 1);
        mem.copy(current.strnum, p.buffer + p.offset, numLen);
        current.strnum[numLen] = '\0';
        current.type = STRNUM;
        jsonUnsafeAdvanceBy(p, numLen);
}

/**
    * Parse json boolean
    * 1: true
    * 0: false
    * -1: error
    */
static i64 jsonParseBool(JsonParser *p) {
        u8 peek = jsonPeek(p);
        i64 retval = -1;

        /* Check if the next character in the buffer is 't' for true */
        if (peek == 't') {
                if (jsonHasChars(p, 4) && !str.cmpLen(p.buffer+p.offset,"true",4)) {
                        retval = true;
                        jsonUnsafeAdvanceBy(p, 4);
                }
        }
        /* Check if the next character in the buffer is 'f' for false */
        else if (peek == 'f') {
                if (jsonHasChars(p, 5) && !str.cmpLen(p.buffer+p.offset,"false",5)) {
                        retval = false;
                        jsonUnsafeAdvanceBy(p, 5);
                }
        }

        /* Failed to parse boolean: report it where the literal starts */
        if (retval == -1) {
                p.error = INVALID_LITERAL;
        }
        return retval;
}

/**
    * Parse json null
    * 1: success
    * -1: error
    */
static i32 jsonParseNull(JsonParser *p) {
        if (!jsonHasChars(p, 4) || str.cmpLen(p.buffer + p.offset,"null",4)) {
                p.error = INVALID_LITERAL; /* where the literal starts */
                return -1;
        }
        jsonUnsafeAdvanceBy(p, 4);
        return 1;
}

static bool jsonSetExpectedType(JsonParser *p) {
        auto peek = jsonPeek(p);
        switch (peek) {
                case '{': p.type = JSON_PARSER_OBJECT;
                case '[': p.type = JSON_PARSER_ARRAY;
                case 'n': p.type = JSON_PARSER_NULL;
                case 't':
                case 'f': p.type = JSON_PARSER_BOOL;
                case '"': p.type = JSON_PARSER_STRING;
                default: {
                        if (('0'<=peek<='9') || peek == '-' || peek == '+' || peek == '.') {
                                p.type = JSON_PARSER_NUMERIC;
                                break;
                        } else {
                                p.error = INVALID_JSON_TYPE_CHAR;
                                return false;
                        }
                }
        }
        return true;
}

/* Reads the 4 hex digits after the 'u' of a \u escape, leaving the parser on
    * the last one. Returns the code unit, or -1 if they aren't 4 hex digits. */
static i64 jsonParseHex4(JsonParser *p) {
        i64 i, val = 0;
        u8 ch;
        if (!jsonCanAdvanceBy(p, 4)) {
                return -1;
        }
        for (i = 1; i <= 4; i++) {
                ch = jsonUnsafePeekAt(p, p.offset + i);
                val <<= 4;
                if ('0' <= ch <= '9')      { val |= ch - '0'; }
                else if ('a' <= ch <= 'f') { val |= ch - 'a' + 10; }
                else if ('A' <= ch <= 'F') { val |= ch - 'A' + 10; }
                else { return -1; }
        }
        jsonUnsafeAdvanceBy(p, 4);
        return val;
}

/* Writes code point `cp` as UTF-8 to `dst`, returns the number of bytes */
static i64 jsonUtf8Encode(u8 *dst, i64 cp) {
        if (cp < 0x80) {
                dst[0] = cp;
                return 1;
        } else if (cp < 0x800) {
                dst[0] = 0xC0 | (cp >> 6);
                dst[1] = 0x80 | (cp & 0x3F);
                return 2;
        } else if (cp < 0x10000) {
                dst[0] = 0xE0 | (cp >> 12);
                dst[1] = 0x80 | ((cp >> 6) & 0x3F);
                dst[2] = 0x80 | (cp & 0x3F);
                return 3;
        }
        dst[0] = 0xF0 | (cp >> 18);
        dst[1] = 0x80 | ((cp >> 12) & 0x3F);
        dst[2] = 0x80 | ((cp >> 6) & 0x3F);
        dst[3] = 0x80 | (cp & 0x3F);
        return 4;
}

/* The string at the current offset, decoded; its length (which a
    * \u0000 inside makes more than its StrLen) goes in *_len */
static u8 *jsonParseString(JsonParser *p, i64 *_len) {
        bool run = true;
        i64 start = p.offset;
        i64 end = p.offset;

        if (jsonPeek(p) == '"') {
                jsonAdvance(p);
                end++;
        }

        /* approximate string length: to the closing quote, stepping over each
            * escape as a pair (testing the byte before a quote for a '\\' took
            * the end of "C:\\" for an escaped quote). Never past `buflen`: a
            * string the input cuts off is unterminated */
        while (end < p.buflen && jsonUnsafePeekAt(p, end) != '\0') {
                u8 cur = jsonUnsafePeekAt(p, end);
                if (cur == '"') {
                        break;
                } else if (cur == '\\') {
                        // escape sequence so add another byte
                        if (end + 1 >= p.buflen || jsonUnsafePeekAt(p, end + 1) == '\0') {
                                end = p.buflen; /* cut off inside an escape: unterminated */
                                break;
                        }
                        end++;
                }
                end++;
        }

        if (end >= p.buflen || jsonUnsafePeekAt(p, end) == '\0') {
                p.offset = end;
                if (p.offset > p.buflen) { p.offset = p.buflen; }
                p.error = INVALID_STRING_NOT_TERMINATED;
                return null;
        }

        i64 len = 0;
        u8 *str = mem.alloc(sizeof(u8) * end - start);

        while (run && jsonPeek(p) != '\0') {
                switch (jsonPeek(p)) {
                        case '\\':
                                jsonAdvance(p);
                                switch (jsonPeek(p)) {
                                        case '\\':
                                        case '"':
                                        case '/': str[len++] = jsonPeek(p);
                                        case 'b': str[len++] = '\b';
                                        case 'f': str[len++] = '\f';
                                        case 'n': str[len++] = '\n';
                                        case 'r': str[len++] = '\r';
                                        case 't': str[len++] = '\t';
                                        case 'u': {
                                                i64 cp = jsonParseHex4(p);
                                                if (cp < 0) {
                                                        p.error = INVALID_HEX;
                                                        goto err;
                                                }
                                                if (0xDC00 <= cp <= 0xDFFF) { /* lone low surrogate */
                                                        p.error = INVALID_UTF16_SURROGATE;
                                                        goto err;
                                                }
                                                if (0xD800 <= cp <= 0xDBFF) { /* needs a \uDC00-\uDFFF after it */
                                                        if (!jsonCanAdvanceBy(p, 2) ||
                                                                        jsonUnsafePeekAt(p, p.offset + 1) != '\\' ||
                                                                        jsonUnsafePeekAt(p, p.offset + 2) != 'u') {
                                                                p.error = INVALID_UTF16_SURROGATE;
                                                                goto err;
                                                        }
                                                        jsonUnsafeAdvanceBy(p, 2);
                                                        i64 lo = jsonParseHex4(p);
                                                        if (lo < 0) {
                                                                p.error = INVALID_HEX;
                                                                goto err;
                                                        }
                                                        if (!(0xDC00 <= lo <= 0xDFFF)) {
                                                                p.error = INVALID_UTF16_SURROGATE;
                                                                goto err;
                                                        }
                                                        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                                                }
                                                len += jsonUtf8Encode(str + len, cp);
                                        }
                                        default:
                                                p.error = INVALID_ESCAPE_CHARACTER;
                                                goto err;
                                }
                        case '"':
                                run = 0;
                        default:
                                if (jsonPeek(p) < 0x20) { /* RFC 8259: control characters escaped */
                                        p.error = INVALID_CONTROL_CHARACTER;
                                        goto err;
                                }
                                str[len++] = jsonPeek(p);
                }

                jsonAdvance(p);
        }
        if (jsonPeek(p) == '"') {
                p.error = INVALID_STRING_NOT_TERMINATED;
                goto err;
        }

        str[len] = '\0';
        *_len = len;
        return str;

err:
        if (str) {
                mem.free(str);
        }
        return null;
}

static u8 *jsonFloatToStr(u8 *buf, f64 f)
{ // %.15g, %.16g or %.17g, the first that reads back as `f`, with a '.'
        // so it parses as a float. 17 digits always do; starting at 15 (not 1)
        // costs one or two tries instead of up to 17, and %g still drops
        // trailing zeros (0.1 is "0.1"). C's snprintf: StrPrint's exact %g is
        // the same text but several times slower. `buf` holds 64 bytes.
        i64 prec = 15, len;
        u8 *e;
        u64 bits;
        mem.copy(&bits, &f, sizeof(f64));
        if (((bits >> 52) & 0x7FF) == 0x7FF) { /* inf/nan: JSON has none, null as JavaScript */
                str.copy(buf, "null");
                return buf;
        }
        while (true) {
                libc.snprintf(buf,64,"%.*g",(i32)prec,f);
                if (prec >= 17 || libc.strtod(buf,null) == f) { break; }
                prec++;
        }
        if (!str.count(buf,'.') && !str.count(buf,'n')) { // not inf/nan
                len = str.len(buf);
                e = str.firstOf(buf,"e");
                if (!e) { e = buf + len; }
                libc.memmove(e+2,e,len - (e-buf) + 1);
                e[0] = '.';
                e[1] = '0';
        }
        return buf;
}

/* Appends the `len` bytes of `str` as a JSON string literal, quotes
    * included; a '\0' among them is written \u0000 */
static void jsonCatQuoted(JsonStringBuilder *js, u8 *str, i64 len) {
        if (!str) {
                str = "";
                len = 0;
        } else if (!len) { /* a node made or changed by hand */
                len = str.len(str);
        }
        u8 *buf = mem.alloc(len * 6 + 3), *dst = buf, *hex = "0123456789abcdef";
        u8 *end = str + len;
        u8 ch;
        *dst++ = '"';
        while (str < end) {
                ch = *str++;
                switch (ch) {
                        case '"':  *dst++ = '\\'; *dst++ = '"';
                        case '\\': *dst++ = '\\'; *dst++ = '\\';
                        case '\b': *dst++ = '\\'; *dst++ = 'b';
                        case '\f': *dst++ = '\\'; *dst++ = 'f';
                        case '\n': *dst++ = '\\'; *dst++ = 'n';
                        case '\r': *dst++ = '\\'; *dst++ = 'r';
                        case '\t': *dst++ = '\\'; *dst++ = 't';
                        default:
                                if (ch < 0x20) { /* other control characters */
                                        *dst++ = '\\'; *dst++ = 'u'; *dst++ = '0'; *dst++ = '0';
                                        *dst++ = hex[ch >> 4];
                                        *dst++ = hex[ch & 0xF];
                                } else {
                                        *dst++ = ch;
                                }
                }
        }
        *dst++ = '"';
        *dst = '\0';
        js.buf = str.appendFormatLen(js.buf,&js.len,"%s",buf);
        mem.free(buf);
}

/* `J` and, with `siblings`, the nodes after it (an array's or object's
    * items, keys included); without it just J's value. */
static void jsonToStringInternal(Value *J, JsonStringBuilder *js,
                                                                                                                            bool siblings=true) {
        u8 fbuf[64];
        if (J == null) {
                js.buf = str.appendFormatLen(js.buf,&js.len,"(nil)");
                return;
        }

        while (J) {
                if (J.key && siblings) {
                        jsonCatQuoted(js,J.key,J.keyLen);
                        js.buf = str.appendFormatLen(js.buf,&js.len,": ");
                }
                switch (J.type) {
                        case INT:
                                js.buf = str.appendFormatLen(js.buf,&js.len,"%d",J.intVal);
                        case FLOAT:
                                js.buf = str.appendFormatLen(js.buf,&js.len,"%s",jsonFloatToStr(fbuf,J.floatVal));
                        case STRNUM:
                                js.buf = str.appendFormatLen(js.buf,&js.len,"%s",J.strnum);
                        case STRING:
                                jsonCatQuoted(js,J.str,J.strLen);
                        case ARRAY:
                                js.buf = str.appendFormatLen(js.buf,&js.len,"[");
                                if (J.array) { jsonToStringInternal(J.array,js); } /* null: empty */
                                js.buf = str.appendFormatLen(js.buf,&js.len,"]");
                        case OBJECT:
                                js.buf = str.appendFormatLen(js.buf,&js.len,"{");
                                if (J.object) { jsonToStringInternal(J.object,js); } /* null: empty */
                                js.buf = str.appendFormatLen(js.buf,&js.len,"}");
                        case BOOL:
                                if (J.boolean == true) {
                                        js.buf = str.appendFormatLen(js.buf,&js.len,"true");
                                } else {
                                        js.buf = str.appendFormatLen(js.buf,&js.len,"false");
                                }
                        case NULL:
                                js.buf = str.appendFormatLen(js.buf,&js.len,"null");
                        default:
                                break;
                }
                if (!siblings) {
                        break;
                }
                if (J.next) {
                        js.buf = str.appendFormatLen(js.buf,&js.len,",");
                }
                J = J.next;
        }
}

pub u8 *toStr(Value *json)
{// The JSON text of json's value - only that node, not the nodes after
    // it or its key, so a node from JsonSelect gives just its value. A new
    // string to Free.
        u8 *buf = mem.alloc(sizeof(u8)*1<<10);
        i64 len = 0;
        JsonStringBuilder sb;
        sb.buf = buf;
        sb.len = len;
        jsonToStringInternal(json,&sb,false);
        sb.buf[sb.len] = '\0';
        return sb.buf;
}

/* Free J, the nodes after it and everything below them */
static void jsonReleaseList(Value *J) {
        Value *ptr = J;
        Value *next = null;

        while (ptr) {
                next = ptr.next;
                if (ptr.key) {
                        mem.free(ptr.key);
                }

                switch (ptr.type) {
                        case STRNUM:
                                if (ptr.strnum) {
                                        mem.free(ptr.strnum);
                                }

                        case STRING:
                                if (ptr.str) {
                                        mem.free(ptr.str);
                                }

                        case ARRAY:
                                jsonReleaseList(ptr.array);

                        case OBJECT:
                                jsonReleaseList(ptr.object);

                        case FLOAT:
                        case INT:
                        case BOOL:
                        case NULL:
                                break;
                }

                if (ptr.state) { /* only on the node JsonParse returns */
                        mem.free(ptr.state);
                }
                mem.free(ptr);
                ptr = next;
        }
}

pub void release(Value *J)
{// Free a whole document: J must be what JsonParse returned (the root).
    // null, or a node inside a document (from JsonSelect, or an item of an
    // array or object), is ignored: those are views into the document.
        if (J == null || J.state == null) {
                return;
        }
        jsonReleaseList(J);
}

/**
    * Very similar to parsing an object where starting char is '[',
    * if array is empty return null
    */
static Value *jsonParseArray(JsonParser *p) {
        /* move past '[' */
        jsonAdvance(p);
        jsonAdvanceWhitespace(p);

        /* array empty we can skip */
        if (jsonPeek(p) == ']') {
                jsonAdvance(p);
                return null;
        }

        u8 ch = '\0';
        Value *J;
        Value *val = jsonNew();
        p.ptr = val;

        while (true) {
                J = p.ptr;
                jsonAdvanceWhitespace(p);

                if (!jsonSetExpectedType(p) || !jsonParseValue(p)) {
                        break;
                }


                jsonAdvanceWhitespace(p);
                ch = jsonPeek(p);
                if (ch != ',') {
                        /* Step past the ']' even when it ends the input, so the caller
                            * sees what follows it */
                        if (ch == ']') {
                                jsonAdvance(p);
                                break;
                        } else {
                                jsonReleaseList(val); /* the whole list parsed so far, keys and values */
                                p.error = INVALID_ARRAY_CHARACTER;
                                return null;
                        }
                }
                jsonAdvance(p);
                J.next = jsonNew();
                p.ptr = J.next;
        }

        p.ptr = val;
        return val;
}

Value *jsonParseObject(JsonParser *p) {
        jsonAdvance(p);
        jsonAdvanceWhitespace(p);
        if (jsonPeek(p) == '}') {
                jsonAdvance(p);
                return null;
        }
        u8 ch = '\0';
        Value *J,*val;
        val = jsonNew();
        p.ptr = val;

        while (true) {
                J = p.ptr;
                jsonAdvanceWhitespace(p);

                if (jsonPeek(p) != '"') {
                        jsonReleaseList(val); /* the whole list parsed so far, keys and values */
                        p.error = INVALID_KEY_TERMINATOR_CHARACTER;
                        return null;
                }

                J.key = jsonParseString(p, &J.keyLen);
                if (p.error != OK) {
                        jsonReleaseList(val); /* the whole list parsed so far, keys and values */
                        return null;
                }
                /* only whitespace between the key and ':', the error is at what isn't */
                jsonAdvanceWhitespace(p);
                if (jsonPeek(p) != ':') {
                        jsonReleaseList(val); /* the whole list parsed so far, keys and values */
                        p.error = INVALID_KEY_VALUE_SEPARATOR;
                        return null;
                }

                jsonAdvance(p);
                jsonAdvanceWhitespace(p);

                if (!jsonSetExpectedType(p) || !jsonParseValue(p)) {
                        break;
                }

                jsonAdvanceWhitespace(p);
                ch = jsonPeek(p);
                if (ch != ',') {
                        if (ch == '}') { /* as for ']' in an array */
                                jsonAdvance(p);
                                break;
                        } else {
                                jsonReleaseList(val); /* the whole list parsed so far, keys and values */
                                p.error = INVALID_JSON_TYPE_CHAR;
                                return null;
                        }
                }

                jsonAdvance(p);
                J.next = jsonNew();
                p.ptr = J.next;
        }

        return val;
}

/**
    * Selects which parser to use depending on the type which has been set on the
    * previous parse
    */
static bool jsonParseValue(JsonParser *p) {
        auto J = p.ptr;
        switch (p.type) {
                case JSON_PARSER_NUMERIC: {
                        if (p.flags & STRNUM_FLAG) {
                                        jsonParseStrNum(p);
                        } else {
                                        jsonParseNumber(p);
                        }
                }

                case JSON_PARSER_STRING:
                        J.type = STRING;
                        J.str = jsonParseString(p, &J.strLen);

                case JSON_PARSER_NULL:
                        if (jsonParseNull(p) == 1) {
                                        J.type = NULL;
                        } else if (p.error == OK) {
                                        p.error = INVALID_TYPE;
                        }

                case JSON_PARSER_OBJECT:
                        J.type = OBJECT;
                        if (++p.depth > MAX_DEPTH) {
                                p.error = NESTING_TOO_DEEP;
                                J.object = null;
                        } else {
                                J.object = jsonParseObject(p);
                        }
                        p.depth--;

                case JSON_PARSER_BOOL:
                        J.type = BOOL;
                        J.boolean = jsonParseBool(p);

                case JSON_PARSER_ARRAY:
                        J.type = ARRAY;
                        if (++p.depth > MAX_DEPTH) {
                                p.error = NESTING_TOO_DEEP;
                                J.array = null;
                        } else {
                                J.array = jsonParseArray(p);
                        }
                        p.depth--;
        }

        return p.error == OK;
}

Value *jsonParseWithLenAndFlags(u8 *raw, i64 len, i32 flags) {
        auto J = jsonNew();
        JsonParser p;
        p.flags = flags;
        p.offset = 0;
        p.buffer = raw;
        p.buflen = len;
        p.type = -1;
        p.J = null;
        p.ptr = null;
        p.error = OK; 
        p.J = J;
        p.depth = 0;

        jsonAdvanceWhitespace(&p);

        /* Any JSON value can be the top-level one (RFC 8259) */
        p.ptr = J;
        if (jsonPeek(&p) == '\0' || !jsonSetExpectedType(&p)) {
                p.error = CANNOT_START_PARSE;
        } else if (jsonParseValue(&p)) {
                /* Only whitespace may follow the value (`[1]x`, `1 2`, `truex` and
                    * `{"a":1}}` were accepted) */
                jsonAdvanceWhitespace(&p);
                if (p.offset < p.buflen) {
                        p.error = INVALID_JSON_TYPE_CHAR;
                }
        }

        J.state = jsonStateNew();
        J.state.error = p.error;
        J.state.ch = jsonPeek(&p);
        J.state.offset = p.offset;
        J.state.line = 1;
        J.state.col = 1;
        for (i64 i = 0; i < p.offset && i < len && raw[i]; ++i) {
                if (raw[i] == '\n') {
                        J.state.line++;
                        J.state.col = 1;
                } else {
                        J.state.col++;
                }
        }
        return J;
}

/* Parse `len` bytes of `raw` (-1: up to the NUL) into a tree of Json
    * nodes. `flags` is 0 or JSON_STRNUM_FLAG, which keeps numbers as the
    * text they were written as (type JSON_STRNUM, in `strnum`) instead of
    * converting them to JSON_INT / JSON_FLOAT. */
pub Value *parse(u8 *raw, i64 len=-1, i64 flags=0) {
        if (!raw) { return null; }
        if (len == -1) { len = str.len(raw); }
        return jsonParseWithLenAndFlags(raw,len,flags);
}

/* The offending character for a message: 'x', '\0' at the end of the
    * input, '\xNN' for other control characters */
static u8 *jsonErrorChar(u8 *buf, u8 ch) {
        if (ch == '\0') {
                str.copy(buf, "'\\0'");
        } else if (ch < 0x20 || ch == 0x7F) {
                str.formatTo(buf, "'\\x%02X'", ch);
        } else {
                str.formatTo(buf, "'%c'", ch);
        }
        return buf;
}

static u8 *jsonGetStrerrorInternal(i32 error, u8 ch, i64 line, i64 col) {
        u8 c[16], *what;
        jsonErrorChar(c, ch);
        switch (error) {
                case OK:
                        return str.formatTo(null, "Success");
                case INVALID_UTF16:
                        what = "Invalid UTF-16 code in a \\u escape";
                case INVALID_UTF16_SURROGATE:
                        what = "Invalid UTF-16 surrogate pair in a \\u escape";
                case INVALID_HEX:
                        what = "Expected 4 hex digits after \\u";
                case INVALID_STRING_NOT_TERMINATED:
                        what = "Unterminated string";
                case INVALID_NUMBER:
                case INVALID_DECIMAL:
                case INVALID_SIGN:
                        return str.formatTo(null, "Invalid number at %s at line %d, column %d",
                                                                                        c, line, col);
                case INVALID_KEY_TERMINATOR_CHARACTER:
                        return str.formatTo(null, "Expected '\"' to start an object key, got %s at "
                                                                                        "line %d, column %d", c, line, col);
                case INVALID_KEY_VALUE_SEPARATOR:
                        return str.formatTo(null, "Expected ':' after an object key, got %s at line "
                                                                                        "%d, column %d", c, line, col);
                case INVALID_ARRAY_CHARACTER:
                        return str.formatTo(null, "Expected ',' or ']' in an array, got %s at line "
                                                                                        "%d, column %d", c, line, col);
                case INVALID_ESCAPE_CHARACTER:
                        return str.formatTo(null, "Invalid escape character %s after '\\' at line "
                                                                                        "%d, column %d", c, line, col);
                case INVALID_CONTROL_CHARACTER:
                        return str.formatTo(null, "Unescaped control character %s in a string (write "
                                                                                        "it as \\u%04x) at line %d, column %d", c, ch, line, col);
                case NESTING_TOO_DEEP:
                        return str.formatTo(null, "Arrays and objects nested more than %d deep at "
                                                                                        "line %d, column %d", MAX_DEPTH, line, col);
                case INVALID_BOOL:
                case INVALID_LITERAL:
                        what = "Invalid literal, expected true, false or null";
                case INVALID_JSON_TYPE_CHAR:
                case INVALID_TYPE:
                        if (ch == '\0') {
                                what = "Unexpected end of the JSON text"; break;
                        }
                        return str.formatTo(null, "Unexpected character %s where a value or ',' "
                                                                                        "was expected at line %d, column %d", c, line, col);
                case CANNOT_START_PARSE:
                        what = "Expected a JSON value (object, array, string, number, true, "
                                                    "false or null)";
                case CANNOT_ADVANCE:
                case EOF:
                        what = "Unexpected end of the JSON text";
                default:
                        return null;
        }
        return str.formatTo(null, "%s at line %d, column %d", what, line, col);
}

/* If the json has state this will return the error as a human readable string
    * must be freed by the caller (allows it to be thread safe). It would be
    * advisable to use this for debugging purposes only as it calls malloc */
pub u8 *errorStr(Value *json) {
        if (!json || !json.state) { /* null or not a root: nothing to report */
                return null;
        }
        if (json.state.error != OK) {
                u8 *str = jsonGetStrerrorInternal(json.state.error,json.state.ch,
                                json.state.line,json.state.col);
                return str;
        }
        return null;
}

/* Print the error to stdout ("No errors" if there was none); nothing for
    * null or a node that isn't a root */
pub void printError(Value *json) {
        if (!json || !json.state) { /* null or not a root: prints nothing */
                return;
        }
        u8 *strError = errorStr(json);
        if (strError) {
                printf("%s\n", strError);
                mem.free(strError);
        } else {
                printf("No errors\n");
        }
}

pub bool ok(Value *j) {
        if (!j) { return false; }
        return j.state && j.state.error == OK;
}

pub bool isObject(Value *j) {
        if (!j) { return false; }
        return j.type == OBJECT;
}

pub bool isArray(Value *j) {
        if (!j) { return false; }
        return j.type == ARRAY;
}

pub bool isNull(Value *j) {
        if (!j) { return false; }
        return j.type == NULL;
}

pub bool isBool(Value *j) {
        if (!j) { return false; }
        return j.type == BOOL;
}

pub bool isString(Value *j) {
        if (!j) { return false; }
        return j.type == STRING;
}

pub bool isInt(Value *j) {
        if (!j) { return false; }
        return j.type == INT;
}

pub bool isFloat(Value *j) {
        if (!j) { return false; }
        return j.type == FLOAT;
}

static bool jsonTypeCheck(Value *j, u8 tk) {
        switch (tk) {
                case 's':
                        if (!isString(j)) {
                                return false;
                        }
                        return true;
                case 'i':
                        if (!isInt(j)) {
                                return false;
                        }
                        return true;
                case 'f':
                        if (!isFloat(j)) {
                                return false;
                        }
                        return true;
                case 'o':
                        if (!isObject(j)) {
                                return false;
                        }
                        return true;
                case 'a':
                        if (!isArray(j)) {
                                return false;
                        }
                        return true;
                case 'b':
                        if (!isBool(j)) {
                                return false;
                        }
                        return true;
                case '!':
                        if (!isNull(j)) {
                                return false;
                        }
                        return true;
                default:
                        return false;
        }
}

/**
    * Get item from an array of json or return null
    */
Value *jsonArrayAt(Value *j, i64 idx) {
        if (!isArray(j) || idx < 0) {
                return null;
        }

        auto node = j.array;
        auto i = 0;

        while (node && i < idx) {
                node = node.next;
                ++i;
        }

        if (!node) { return null; }
        /* The idx was passed the number of elements in the list */
        if (i != idx) { return null; }

        return node;
}

static Value *jsonObjectAt(Value *j, u8 *name) {
        if (!isObject(j)) {
                return null;
        }
        if (name == null) {
                return null;
        }

        auto el = j.object;
        while ((el != null) && (el.key != null) && str.cmp(name,el.key) != 0) {
                el = el.next;
        }

        if ((el == null) || (el.key == null)) {
                return null;
        }

        return el;
}

pub Value *objectGet(Value *obj, u8 *key)
{// The value stored under exactly `key` (the first one if it is repeated),
    // or null if obj isn't an object or has no such key. Any key works, also
    // ones JsonSelect can't spell ('.', '[', ':' ...). A view, don't release.
        return jsonObjectAt(obj, key);
}

pub Value *arrayGet(Value *arr, i64 i)
{// Item i (from 0) of an array, or null if arr isn't an array or i is
    // negative or past the end. A view, don't release.
        return jsonArrayAt(arr, i);
}

pub i64 len(Value *node)
{// The number of items of an array or object; 0 for anything else and null
        Value *it;
        i64 n = 0;
        if (isArray(node)) {
                it = node.array;
        } else if (isObject(node)) {
                it = node.object;
        } else {
                return 0;
        }
        while (it) {
                n++;
                it = it.next;
        }
        return n;
}

/**
    * Allows selections like:
    *
    * Json *J = JsonParse(text);
    * Json *width = JsonSelect(J,".foo.bar[*].baz",4);      // [*] takes an i64
    * Json *height = JsonSelect(J,".tv.type[4].*","name");  // .* takes a u8 *
    * Json *price = JsonSelect(J,".clothes.shirt[4].price_*","eur");
    *
    * You can also include a :<type> specifier, typically at the end, to verify the
    * type of the final JSON object selected. If the type doesn't match, the
    * function will return null. For instance, the specifier .foo.bar:s will return
    * null unless the root object has a 'foo' field, which is an object with a
    * 'bar' field that contains a string. Here is a comprehensive list of
    * selectors:
    *
    *    ".field" selects the "field" of the current object (the first one if
    *             the key repeats).
    *    "[1234]" selects the specified index of the current array.
    *    ":<type>" checks if the currently selected type is of the specified type,
    *              where the type can be a single letter representing:
    *      "s" -> string
    *      "f" -> float
    *      "i" -> int
    *      "a" -> array
    *      "o" -> object
    *      "b" -> boolean
    *      "!" -> null
    */
pub Value *select(Value *j, u8 *fmt, ...args) {
        /**
            * Heavily inspired by:
            * https://github.com/antirez/stonky/blob/main/stonky.c
            *
            * There was an issue in CJSON that rejected this contribution.
            * https://github.com/DaveGamble/cJSON/issues/553
            *
            * The fmt string is parsed, the JSON path is built and the
            * appropriate action is performed depending on the type of the selector
            * (Array, Object or Type Check). If a wildcard "*" is found, the next
            * argument from the variable arguments list is used.
            *
            * If any error occurs during the process (for example, the path length
            * exceeds the maximum buffer length, or an invalid type check character is
            * encountered), the function will stop processing and return null. If
            * everything goes smoothly, the function will return the selected JSON
            * object.
            */
        i64 kind, arg = 0, pathLen, len, idx;
        u8 path[JSON_SEL_MAX_BUF + 1], buf[32], *s, *ptr = fmt;

        if (!j || !fmt || (*ptr != '.' && *ptr != '[')) {
                return null;
        }

        while (*ptr) {
                switch (*ptr) {
                        case '.': kind = JSON_SEL_OBJ;
                        case '[': kind = JSON_SEL_ARRAY;
                        case ':': kind = JSON_SEL_TYPECHECK;
                        default:  return null;
                }
                ptr++;

                /* The component runs to the next '.', '[', ']', ':' or the end; a
                    * '*' takes the next argument: an i64 index in [..], a string after
                    * '.'. */
                pathLen = 0;
                while (*ptr && *ptr != '.' && *ptr != '[' && *ptr != ']' && *ptr != ':') {
                        if (*ptr == '*') {
                                if (kind == JSON_SEL_ARRAY) {
                                        len = libc.snprintf(buf, sizeof(buf), "%ld", (i64)args[arg++]);
                                        s = buf;
                                } else if (kind == JSON_SEL_OBJ) {
                                        s = (u8 *)args[arg++];
                                        if (!s) { return null; }
                                        len = str.len(s);
                                } else {
                                        return null;
                                }
                        } else {
                                s = ptr;
                                len = 1;
                        }
                        if (pathLen + len > JSON_SEL_MAX_BUF) {
                                return null;
                        }
                        mem.copy(path + pathLen, s, len);
                        pathLen += len;
                        ptr++;
                }
                path[pathLen] = '\0';
                if (pathLen == 0) {
                        return null; /* ".", "[", "[]", "..x", ":" */
                }

                switch (kind) {
                        case JSON_SEL_ARRAY:
                                if (*ptr != ']') { return null; }
                                ptr++;
                                /* only digits: no sign, spaces or trailing junk */
                                idx = 0;
                                for (len = 0; len < pathLen; ++len) {
                                        if (path[len] < '0' || path[len] > '9') { return null; }
                                        idx = idx * 10 + path[len] - '0';
                                        if (idx < 0) { return null; } /* overflow */
                                }
                                j = jsonArrayAt(j, idx);
                        case JSON_SEL_OBJ:
                                j = jsonObjectAt(j, path);
                        case JSON_SEL_TYPECHECK:
                                if (pathLen != 1 || !jsonTypeCheck(j, path[0])) { return null; }
                }
                if (!j) {
                        return null;
                }
        }

        return j;
}
