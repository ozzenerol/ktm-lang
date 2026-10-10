/* Source code Heavily inspired from: 
 * https://github.com/Jamesbarford/easy-json - A json parser I wrote a while ago 
 */
#define JSON_NO_FLAGS     (0x0)

/* Do not parse numbers, treat them as strings */
#define JSON_STRNUM_FLAG (0x1)

#define JSON_STRING 0
#define JSON_FLOAT  1
#define JSON_INT    2
#define JSON_STRNUM 3
#define JSON_ARRAY  4
#define JSON_OBJECT 5
#define JSON_BOOL   6
#define JSON_NULL   7

#define JSON_OK 0
#define JSON_INVALID_UTF16 1
#define JSON_INVALID_UTF16_SURROGATE 2
#define JSON_INVALID_HEX 3
#define JSON_INVALID_STRING_NOT_TERMINATED 4
#define JSON_INVALID_NUMBER 5
#define JSON_INVALID_DECIMAL 6
#define JSON_INVALID_SIGN 7
#define JSON_INVALID_JSON_TYPE_CHAR 8
#define JSON_INVALID_BOOL 9
#define JSON_INVALID_TYPE 10
#define JSON_CANNOT_ADVANCE 11
#define JSON_CANNOT_START_PARSE 12
#define JSON_INVALID_KEY_TERMINATOR_CHARACTER 13
#define JSON_INVALID_KEY_VALUE_SEPARATOR 14
#define JSON_INVALID_ARRAY_CHARACTER 15
#define JSON_INVALID_ESCAPE_CHARACTER 16
#define JSON_EOF 17
#define JSON_INVALID_LITERAL 18 /* not true, false or null */
#define JSON_INVALID_CONTROL_CHARACTER 19 /* a raw byte < 0x20 in a string */
#define JSON_NESTING_TOO_DEEP 20 /* arrays/objects nested over JSON_MAX_DEPTH */

/* Arrays and objects nest at most this deep: the parser, JsonToString and
 * JsonReleaseList recurse once per level, and 80000 levels overflowed the
 * stack */
#define JSON_MAX_DEPTH 512

#define JSON_PARSER_STRING  0
#define JSON_PARSER_NUMERIC 1
#define JSON_PARSER_ARRAY   2
#define JSON_PARSER_OBJECT  3
#define JSON_PARSER_BOOL    4
#define JSON_PARSER_NULL    5

#define JSON_SEL_INVALD    (0)
#define JSON_SEL_OBJ       (1)
#define JSON_SEL_ARRAY     (2)
#define JSON_SEL_TYPECHECK (3)
#define JSON_SEL_MAX_BUF   (256)

class JsonState
{
  U8 ch;
  I64 offset;
  I64 error;
  I64 line;   /* 1-based line and byte column of `offset` */
  I64 col;
};

class Json;
class Json
{
  JsonState *state;
  Json *next;
  I64 type;
  U8 *key;
  union
  {
    Json *array;
    Json *object;
    U8 *str;
    U8 *strnum;
    F64 f64;
    I64 i64;
    Bool boolean;
  };
  /* Bytes in `str` (a JSON_STRING) and in `key`, before their '\0': a
   * string can hold \u0000, which the '\0' termination would cut off.
   * Set them when you change `str` or `key` (0 means "up to the '\0'").
   * JsonSelect matches keys up to their first '\0'. */
  I64 str_len;
  I64 key_len;
};

class JsonParser 
{
  I32 type;
  U8 *buffer;
  I64 offset;
  I64 buflen;
  I32 error;
  Json *J;
  Json *ptr;
  I32 flags;
  JsonState *state;
  I64 depth; /* arrays/objects open around the current value */
};

class JsonStringBuilder
{
  U8 *buf;
  I64 len;
};

public U8 *JsonToString(Json *j);
Bool JsonParseValue(JsonParser *p);

static Json *JsonNew()
{
  Json *J = MAlloc(sizeof(Json));
  J->type = JSON_NULL;
  J->key = NULL;
  J->next = NULL;
  J->state = NULL;
  J->str_len = 0;
  J->key_len = 0;
  return J;
}

static JsonState *JsonStateNew()
{
  JsonState *json_state = MAlloc(sizeof(JsonState));
  json_state->error = JSON_OK;
  json_state->ch = '\0';
  json_state->offset = 0;
  return json_state;
}

static U0 JsonUnsafeAdvanceBy(JsonParser *p, I64 jmp)
{
  p->offset += jmp;
}

static Bool JsonCanAdvanceBy(JsonParser *p, I64 jmp)
{
  return (p->offset + jmp) < p->buflen;
}

/* Moving past the last character is fine (a top-level value can end the
 * input), moving past the end is not */
static U0 JsonAdvance(JsonParser *p)
{
  if (p->offset < p->buflen) {
    ++p->offset;
    return;
  }
  p->error = JSON_EOF;
}

/* Are there `n` more characters, starting at the current one? */
static Bool JsonHasChars(JsonParser *p, I64 n)
{
  return p->offset + n <= p->buflen;
}

static Bool JsonAdvanceToError(JsonParser *p, I64 jmp, I32 error_code)
{
  p->error = error_code;
  JsonUnsafeAdvanceBy(p, jmp);
  return 0;
}

static U8 JsonPeek(JsonParser *p)
{
  if (p->offset >= p->buflen) return '\0';
  return p->buffer[p->offset];
}

static U8 JsonUnsafePeekAt(JsonParser *p, I64 idx)
{
  return p->buffer[idx];
}

/* JSON's whitespace is exactly space, tab, LF and CR (not \v or \f) */
static U0 JsonAdvanceWhitespace(JsonParser *p)
{
  while (p->offset < p->buflen) {
    U8 ch = p->buffer[p->offset];
    if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') break;
    p->offset++;
  }
}

/* Length of the number at the current offset, RFC 8259's grammar
 * -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?, ending at the input's
 * end, whitespace, ',', ']' or '}'. Anything else (`01`, `+1`, `.5`,
 * `1.`, `0x1F`, `1e`, `1a`, `--1`) sets JSON_INVALID_NUMBER and returns
 * 0. *_is_int: no fraction and no exponent. */
static I64 JsonNumberLen(JsonParser *p, Bool *_is_int)
{
  I64 i = p->offset, n = p->buflen;
  U8 *b = p->buffer;
  *_is_int = TRUE;
  if (i < n && b[i] == '-') i++;
  if (i >= n || !('0' <= b[i] <= '9')) goto bad;
  if (b[i] == '0') {
    i++;
  } else {
    while (i < n && '0' <= b[i] <= '9') i++;
  }
  if (i < n && b[i] == '.') {
    *_is_int = FALSE;
    i++;
    if (i >= n || !('0' <= b[i] <= '9')) goto bad;
    while (i < n && '0' <= b[i] <= '9') i++;
  }
  if (i < n && (b[i] == 'e' || b[i] == 'E')) {
    *_is_int = FALSE;
    i++;
    if (i < n && (b[i] == '+' || b[i] == '-')) i++;
    if (i >= n || !('0' <= b[i] <= '9')) goto bad;
    while (i < n && '0' <= b[i] <= '9') i++;
  }
  if (i < n && b[i] != ' ' && b[i] != '\t' && b[i] != '\n' &&
      b[i] != '\r' && b[i] != ',' && b[i] != ']' && b[i] != '}') {
    goto bad;
  }
  return i - p->offset;

bad:
  /* Point the error at the offending character */
  if (i > n) i = n;
  p->offset = i;
  p->error = JSON_INVALID_NUMBER;
  return 0;
}

/* Does the integer `text` (an optional '-' then digits, no leading
 * zeros) fit in an I64? */
static Bool JsonIntFits(U8 *text, I64 len)
{
  U8 *max = "9223372036854775807";
  if (*text == '-') {
    max = "9223372036854775808";
    text++;
    len--;
  }
  if (len != 19) return len < 19;
  return StrNCmp(text, max, 19) <= 0;
}

static U0 JsonParseNumber(JsonParser *p)
{
  auto current = p->ptr;
  Bool is_int;
  I64 num_len = JsonNumberLen(p, &is_int);
  if (!num_len) return;

  /* strtoll / strtod need the text on its own: `len` may end the input
   * mid-buffer, with more digits after it */
  U8 small[64], *text = small;
  if (num_len >= 64) text = MAlloc(num_len + 1);
  MemCpy(text, p->buffer + p->offset, num_len);
  text[num_len] = '\0';

  /* An integer outside I64 is read as an F64 (JSON_FLOAT), as JavaScript
   * does; strtoll would saturate it silently. 1e400 is inf, which
   * JsonToString writes as null. */
  if (is_int && JsonIntFits(text, num_len)) {
    current->i64 = strtoll(text, NULL, 10);
    current->type = JSON_INT;
  } else {
    current->f64 = strtod(text, NULL);
    current->type = JSON_FLOAT;
  }
  if (text != small) Free(text);
  JsonUnsafeAdvanceBy(p, num_len);
}

/* With JSON_STRNUM_FLAG: keep a number as the text it was written as
 * (`J->strnum`), so nothing is lost to I64 / F64 conversion. It is
 * checked like any other number. */
static U0 JsonParseStrNum(JsonParser *p)
{
  auto current = p->ptr;
  Bool is_int;
  I64 num_len = JsonNumberLen(p, &is_int);
  if (!num_len) return;

  current->strnum = MAlloc(num_len + 1);
  MemCpy(current->strnum, p->buffer + p->offset, num_len);
  current->strnum[num_len] = '\0';
  current->type = JSON_STRNUM;
  JsonUnsafeAdvanceBy(p, num_len);
}

/**
 * Parse json boolean
 * 1: true
 * 0: false
 * -1: error
 */
static I64 JsonParseBool(JsonParser *p)
{
  U8 peek = JsonPeek(p);
  I64 retval = -1;

  /* Check if the next character in the buffer is 't' for true */
  if (peek == 't') {
    if (JsonHasChars(p, 4) && !StrNCmp(p->buffer+p->offset,"true",4)) {
      retval = TRUE;
      JsonUnsafeAdvanceBy(p, 4);
    }
  }
  /* Check if the next character in the buffer is 'f' for false */
  else if (peek == 'f') {
    if (JsonHasChars(p, 5) && !StrNCmp(p->buffer+p->offset,"false",5)) {
      retval = FALSE;
      JsonUnsafeAdvanceBy(p, 5);
    }
  }

  /* Failed to parse boolean: report it where the literal starts */
  if (retval == -1) {
    p->error = JSON_INVALID_LITERAL;
  }
  return retval;
}

/**
 * Parse json null
 * 1: success
 * -1: error
 */
static I32 JsonParseNull(JsonParser *p)
{
  if (!JsonHasChars(p, 4) || StrNCmp(p->buffer + p->offset,"null",4)) {
    p->error = JSON_INVALID_LITERAL; /* where the literal starts */
    return -1;
  }
  JsonUnsafeAdvanceBy(p, 4);
  return 1;
}

static Bool JsonSetExpectedType(JsonParser *p)
{
  auto peek = JsonPeek(p);
  switch (peek) {
    case '{': p->type = JSON_PARSER_OBJECT; break;
    case '[': p->type = JSON_PARSER_ARRAY; break;
    case 'n': p->type = JSON_PARSER_NULL; break;
    case 't':
    case 'f': p->type = JSON_PARSER_BOOL; break;
    case '"': p->type = JSON_PARSER_STRING; break;
    default: {
      if (('0'<=peek<='9') || peek == '-' || peek == '+' || peek == '.') {
        p->type = JSON_PARSER_NUMERIC;
        break;
      } else {
        p->error = JSON_INVALID_JSON_TYPE_CHAR;
        return FALSE;
      }
    }
  }
  return TRUE;
}

/* Reads the 4 hex digits after the 'u' of a \u escape, leaving the parser on
 * the last one. Returns the code unit, or -1 if they aren't 4 hex digits. */
static I64 JsonParseHex4(JsonParser *p)
{
  I64 i, val = 0;
  U8 ch;
  if (!JsonCanAdvanceBy(p, 4)) {
    return -1;
  }
  for (i = 1; i <= 4; i++) {
    ch = JsonUnsafePeekAt(p, p->offset + i);
    val <<= 4;
    if ('0' <= ch <= '9')      val |= ch - '0';
    else if ('a' <= ch <= 'f') val |= ch - 'a' + 10;
    else if ('A' <= ch <= 'F') val |= ch - 'A' + 10;
    else return -1;
  }
  JsonUnsafeAdvanceBy(p, 4);
  return val;
}

/* Writes code point `cp` as UTF-8 to `dst`, returns the number of bytes */
static I64 JsonUtf8Encode(U8 *dst, I64 cp)
{
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
static U8 *JsonParseString(JsonParser *p, I64 *_len)
{
  Bool run = TRUE;
  I64 start = p->offset;
  I64 end = p->offset;

  if (JsonPeek(p) == '"') {
    JsonAdvance(p);
    end++;
  }

  /* approximate string length: to the closing quote, stepping over each
   * escape as a pair (testing the byte before a quote for a '\\' took
   * the end of "C:\\" for an escaped quote). Never past `buflen`: a
   * string the input cuts off is unterminated */
  while (end < p->buflen && JsonUnsafePeekAt(p, end) != '\0') {
    U8 cur = JsonUnsafePeekAt(p, end);
    if (cur == '"') {
      break;
    } else if (cur == '\\') {
      // escape sequence so add another byte
      if (end + 1 >= p->buflen || JsonUnsafePeekAt(p, end + 1) == '\0') {
        end = p->buflen; /* cut off inside an escape: unterminated */
        break;
      }
      end++;
    }
    end++;
  }

  if (end >= p->buflen || JsonUnsafePeekAt(p, end) == '\0') {
    p->offset = end;
    if (p->offset > p->buflen) p->offset = p->buflen;
    p->error = JSON_INVALID_STRING_NOT_TERMINATED;
    return NULL;
  }

  I64 len = 0;
  U8 *str = MAlloc(sizeof(U8) * end - start);

  while (run && JsonPeek(p) != '\0') {
    switch (JsonPeek(p)) {
      case '\\':
        JsonAdvance(p);
        switch (JsonPeek(p)) {
          case '\\':
          case '"':
          case '/': str[len++] = JsonPeek(p); break;
          case 'b': str[len++] = '\b'; break;
          case 'f': str[len++] = '\f'; break;
          case 'n': str[len++] = '\n'; break;
          case 'r': str[len++] = '\r'; break;
          case 't': str[len++] = '\t'; break;
          case 'u': {
            I64 cp = JsonParseHex4(p);
            if (cp < 0) {
              p->error = JSON_INVALID_HEX;
              goto err;
            }
            if (0xDC00 <= cp <= 0xDFFF) { /* lone low surrogate */
              p->error = JSON_INVALID_UTF16_SURROGATE;
              goto err;
            }
            if (0xD800 <= cp <= 0xDBFF) { /* needs a \uDC00-\uDFFF after it */
              if (!JsonCanAdvanceBy(p, 2) ||
                  JsonUnsafePeekAt(p, p->offset + 1) != '\\' ||
                  JsonUnsafePeekAt(p, p->offset + 2) != 'u') {
                p->error = JSON_INVALID_UTF16_SURROGATE;
                goto err;
              }
              JsonUnsafeAdvanceBy(p, 2);
              I64 lo = JsonParseHex4(p);
              if (lo < 0) {
                p->error = JSON_INVALID_HEX;
                goto err;
              }
              if (!(0xDC00 <= lo <= 0xDFFF)) {
                p->error = JSON_INVALID_UTF16_SURROGATE;
                goto err;
              }
              cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
            }
            len += JsonUtf8Encode(str + len, cp);
            break;
          }
          default:
            p->error = JSON_INVALID_ESCAPE_CHARACTER;
            goto err;
        }
        break;
      case '"':
        run = 0;
        break;
      default:
        if (JsonPeek(p) < 0x20) { /* RFC 8259: control characters escaped */
          p->error = JSON_INVALID_CONTROL_CHARACTER;
          goto err;
        }
        str[len++] = JsonPeek(p);
        break;
    }
  
    JsonAdvance(p);
  }
  if (JsonPeek(p) == '"') {
    p->error = JSON_INVALID_STRING_NOT_TERMINATED;
    goto err;
  }

  str[len] = '\0';
  *_len = len;
  return str;

err:
  if (str) {
    Free(str);
  }
  return NULL;
}

static U8 *JsonFloatToStr(U8 *buf, F64 f)
{ // %.15g, %.16g or %.17g, the first that reads back as `f`, with a '.'
  // so it parses as a float. 17 digits always do; starting at 15 (not 1)
  // costs one or two tries instead of up to 17, and %g still drops
  // trailing zeros (0.1 is "0.1"). C's snprintf: StrPrint's exact %g is
  // the same text but several times slower. `buf` holds 64 bytes.
  I64 prec = 15, len;
  U8 *e;
  U64 bits;
  MemCpy(&bits, &f, sizeof(F64));
  if (((bits >> 52) & 0x7FF) == 0x7FF) { /* inf/nan: JSON has none, null as JavaScript */
    StrCpy(buf, "null");
    return buf;
  }
  while (TRUE) {
    snprintf(buf,64,"%.*g",prec(I32),f);
    if (prec >= 17 || strtod(buf,NULL) == f) break;
    prec++;
  }
  if (!StrOcc(buf,'.') && !StrOcc(buf,'n')) { // not inf/nan
    len = StrLen(buf);
    e = StrFirstOcc(buf,"e");
    if (!e) e = buf + len;
    memmove(e+2,e,len - (e-buf) + 1);
    e[0] = '.';
    e[1] = '0';
  }
  return buf;
}

/* Appends the `len` bytes of `str` as a JSON string literal, quotes
 * included; a '\0' among them is written \u0000 */
static U0 JsonCatQuoted(JsonStringBuilder *js, U8 *str, I64 len)
{
  if (!str) {
    str = "";
    len = 0;
  } else if (!len) { /* a node made or changed by hand */
    len = StrLen(str);
  }
  U8 *buf = MAlloc(len * 6 + 3), *dst = buf, *hex = "0123456789abcdef";
  U8 *end = str + len;
  U8 ch;
  *dst++ = '"';
  while (str < end) {
    ch = *str++;
    switch (ch) {
      case '"':  *dst++ = '\\'; *dst++ = '"'; break;
      case '\\': *dst++ = '\\'; *dst++ = '\\'; break;
      case '\b': *dst++ = '\\'; *dst++ = 'b'; break;
      case '\f': *dst++ = '\\'; *dst++ = 'f'; break;
      case '\n': *dst++ = '\\'; *dst++ = 'n'; break;
      case '\r': *dst++ = '\\'; *dst++ = 'r'; break;
      case '\t': *dst++ = '\\'; *dst++ = 't'; break;
      default:
        if (ch < 0x20) { /* other control characters */
          *dst++ = '\\'; *dst++ = 'u'; *dst++ = '0'; *dst++ = '0';
          *dst++ = hex[ch >> 4];
          *dst++ = hex[ch & 0xF];
        } else {
          *dst++ = ch;
        }
        break;
    }
  }
  *dst++ = '"';
  *dst = '\0';
  js->buf = CatLenPrint(js->buf,&js->len,"%s",buf);
  Free(buf);
}

/* `J` and, with `siblings`, the nodes after it (an array's or object's
 * items, keys included); without it just J's value. */
static U0 JsonToStringInternal(Json *J, JsonStringBuilder *js,
                               Bool siblings=TRUE)
{
  U8 fbuf[64];
  if (J == NULL) {
    js->buf = CatLenPrint(js->buf,&js->len,"(nil)");
    return;
  }

  while (J) {
    if (J->key && siblings) {
      JsonCatQuoted(js,J->key,J->key_len);
      js->buf = CatLenPrint(js->buf,&js->len,": ");
    }
    switch (J->type) {
      case JSON_INT:
        js->buf = CatLenPrint(js->buf,&js->len,"%d",J->i64);
        break;
      case JSON_FLOAT:
        js->buf = CatLenPrint(js->buf,&js->len,"%s",JsonFloatToStr(fbuf,J->f64));
        break;
      case JSON_STRNUM:
        js->buf = CatLenPrint(js->buf,&js->len,"%s",J->strnum);
        break;
      case JSON_STRING:
        JsonCatQuoted(js,J->str,J->str_len);
        break;
      case JSON_ARRAY:
        js->buf = CatLenPrint(js->buf,&js->len,"[");
        if (J->array) JsonToStringInternal(J->array,js); /* NULL: empty */
        js->buf = CatLenPrint(js->buf,&js->len,"]");
        break;
      case JSON_OBJECT:
        js->buf = CatLenPrint(js->buf,&js->len,"{");
        if (J->object) JsonToStringInternal(J->object,js); /* NULL: empty */
        js->buf = CatLenPrint(js->buf,&js->len,"}");
        break;
      case JSON_BOOL:
        if (J->boolean == TRUE) {
          js->buf = CatLenPrint(js->buf,&js->len,"true");
        } else {
          js->buf = CatLenPrint(js->buf,&js->len,"false");
        }
        break;
      case JSON_NULL:
        js->buf = CatLenPrint(js->buf,&js->len,"null");
        break;
      default:
        break;
    }
    if (!siblings) {
      break;
    }
    if (J->next) {
      js->buf = CatLenPrint(js->buf,&js->len,",");
    }
    J = J->next;
  }
}

public U8 *JsonToString(Json *json)
{// The JSON text of json's value - only that node, not the nodes after
 // it or its key, so a node from JsonSelect gives just its value. A new
 // string to Free.
  U8 *buf = MAlloc(sizeof(U8)*1<<10);
  I64 len = 0;
  JsonStringBuilder sb;
  sb.buf = buf;
  sb.len = len;
  JsonToStringInternal(json,&sb,FALSE);
  sb.buf[sb.len] = '\0';
  return sb.buf;
}

/* Free J, the nodes after it and everything below them */
static U0 JsonReleaseList(Json *J)
{
  Json *ptr = J;
  Json *next = NULL;

  while (ptr) {
    next = ptr->next;
    if (ptr->key) {
      Free(ptr->key);
    }

    switch (ptr->type) {
      case JSON_STRNUM:
        if (ptr->strnum) {
          Free(ptr->strnum);
        }
        break;

      case JSON_STRING:
        if (ptr->str) {
          Free(ptr->str);
        }
        break;

      case JSON_ARRAY:
        JsonReleaseList(ptr->array);
        break;

      case JSON_OBJECT:
        JsonReleaseList(ptr->object);
        break;

      case JSON_FLOAT:
      case JSON_INT:
      case JSON_BOOL:
      case JSON_NULL:
        break;
    }

    if (ptr->state) { /* only on the node JsonParse returns */
      Free(ptr->state);
    }
    Free(ptr);
    ptr = next;
  }
}

public U0 JsonRelease(Json *J)
{// Free a whole document: J must be what JsonParse returned (the root).
 // NULL, or a node inside a document (from JsonSelect, or an item of an
 // array or object), is ignored: those are views into the document.
  if (J == NULL || J->state == NULL) {
    return;
  }
  JsonReleaseList(J);
}

/**
 * Very similar to parsing an object where starting char is '[',
 * if array is empty return NULL
 */
static Json *JsonParseArray(JsonParser *p)
{
  /* move past '[' */
  JsonAdvance(p);
  JsonAdvanceWhitespace(p);

  /* array empty we can skip */
  if (JsonPeek(p) == ']') {
    JsonAdvance(p);
    return NULL;
  }

  U8 ch = '\0';
  Json *J;
  Json *val = JsonNew();
  p->ptr = val;

  while (TRUE) {
    J = p->ptr;
    JsonAdvanceWhitespace(p);

    if (!JsonSetExpectedType(p) || !JsonParseValue(p)) {
      break;
    }


    JsonAdvanceWhitespace(p);
    ch = JsonPeek(p);
    if (ch != ',') {
      /* Step past the ']' even when it ends the input, so the caller
       * sees what follows it */
      if (ch == ']') {
        JsonAdvance(p);
        break;
      } else {
        JsonReleaseList(val); /* the whole list parsed so far, keys and values */
        p->error = JSON_INVALID_ARRAY_CHARACTER;
        return NULL;
      }
    }
    JsonAdvance(p);
    J->next = JsonNew();
    p->ptr = J->next;
  }

  p->ptr = val;
  return val;
}

Json *JsonParseObject(JsonParser *p)
{
  JsonAdvance(p);
  JsonAdvanceWhitespace(p);
  if (JsonPeek(p) == '}') {
    JsonAdvance(p);
    return NULL;
  }
  U8 ch = '\0';
  Json *J,*val;
  val = JsonNew;
  p->ptr = val;

  while (TRUE) {
    J = p->ptr;
    JsonAdvanceWhitespace(p);

    if (JsonPeek(p) != '"') {
      JsonReleaseList(val); /* the whole list parsed so far, keys and values */
      p->error = JSON_INVALID_KEY_TERMINATOR_CHARACTER;
      return NULL;
    }

    J->key = JsonParseString(p, &J->key_len);
    if (p->error != JSON_OK) {
      JsonReleaseList(val); /* the whole list parsed so far, keys and values */
      return NULL;
    }
    /* only whitespace between the key and ':', the error is at what isn't */
    JsonAdvanceWhitespace(p);
    if (JsonPeek(p) != ':') {
      JsonReleaseList(val); /* the whole list parsed so far, keys and values */
      p->error = JSON_INVALID_KEY_VALUE_SEPARATOR;
      return NULL;
    }

    JsonAdvance(p);
    JsonAdvanceWhitespace(p);

    if (!JsonSetExpectedType(p) || !JsonParseValue(p)) {
      break;
    }

    JsonAdvanceWhitespace(p);
    ch = JsonPeek(p);
    if (ch != ',') {
      if (ch == '}') { /* as for ']' in an array */
        JsonAdvance(p);
        break;
      } else {
        JsonReleaseList(val); /* the whole list parsed so far, keys and values */
        p->error = JSON_INVALID_JSON_TYPE_CHAR;
        return NULL;
      }
    }

    JsonAdvance(p);
    J->next = JsonNew();
    p->ptr = J->next;
  }

  return val;
}

/**
 * Selects which parser to use depending on the type which has been set on the
 * previous parse
 */
static Bool JsonParseValue(JsonParser *p)
{
  auto J = p->ptr;
  switch (p->type) {
    case JSON_PARSER_NUMERIC: {
      if (p->flags & JSON_STRNUM_FLAG) {
          JsonParseStrNum(p);
      } else {
          JsonParseNumber(p);
      }
      break;
    }

    case JSON_PARSER_STRING:
      J->type = JSON_STRING;
      J->str = JsonParseString(p, &J->str_len);
      break;

    case JSON_PARSER_NULL:
      if (JsonParseNull(p) == 1) {
          J->type = JSON_NULL;
      } else if (p->error == JSON_OK) {
          p->error = JSON_INVALID_TYPE;
      }
      break;

    case JSON_PARSER_OBJECT:
      J->type = JSON_OBJECT;
      if (++p->depth > JSON_MAX_DEPTH) {
        p->error = JSON_NESTING_TOO_DEEP;
        J->object = NULL;
      } else {
        J->object = JsonParseObject(p);
      }
      p->depth--;
      break;

    case JSON_PARSER_BOOL:
      J->type = JSON_BOOL;
      J->boolean = JsonParseBool(p);
      break;

    case JSON_PARSER_ARRAY:
      J->type = JSON_ARRAY;
      if (++p->depth > JSON_MAX_DEPTH) {
        p->error = JSON_NESTING_TOO_DEEP;
        J->array = NULL;
      } else {
        J->array = JsonParseArray(p);
      }
      p->depth--;
      break;
  }

  return p->error == JSON_OK;
}


Json *JsonParseWithLenAndFlags(U8 *raw, I64 len, I32 flags)
{
  auto J = JsonNew();
  JsonParser p;
  p.flags = flags;
  p.offset = 0;
  p.buffer = raw;
  p.buflen = len;
  p.type = -1;
  p.J = NULL;
  p.ptr = NULL;
  p.error = JSON_OK; 
  p.J = J;
  p.depth = 0;

  JsonAdvanceWhitespace(&p);

  /* Any JSON value can be the top-level one (RFC 8259) */
  p.ptr = J;
  if (JsonPeek(&p) == '\0' || !JsonSetExpectedType(&p)) {
    p.error = JSON_CANNOT_START_PARSE;
  } else if (JsonParseValue(&p)) {
    /* Only whitespace may follow the value (`[1]x`, `1 2`, `truex` and
     * `{"a":1}}` were accepted) */
    JsonAdvanceWhitespace(&p);
    if (p.offset < p.buflen) {
      p.error = JSON_INVALID_JSON_TYPE_CHAR;
    }
  }

  J->state = JsonStateNew();
  J->state->error = p.error;
  J->state->ch = JsonPeek(&p);
  J->state->offset = p.offset;
  J->state->line = 1;
  J->state->col = 1;
  for (I64 i = 0; i < p.offset && i < len && raw[i]; ++i) {
    if (raw[i] == '\n') {
      J->state->line++;
      J->state->col = 1;
    } else {
      J->state->col++;
    }
  }
  return J;
}

/* Parse `len` bytes of `raw` (-1: up to the NUL) into a tree of Json
 * nodes. `flags` is 0 or JSON_STRNUM_FLAG, which keeps numbers as the
 * text they were written as (type JSON_STRNUM, in `strnum`) instead of
 * converting them to JSON_INT / JSON_FLOAT. */
public Json *JsonParse(U8 *raw, I64 len=-1, I64 flags=0)
{
  if (!raw) return NULL;
  if (len == -1) len = StrLen(raw);
  return JsonParseWithLenAndFlags(raw,len,flags);
}

/* The offending character for a message: 'x', '\0' at the end of the
 * input, '\xNN' for other control characters */
static U8 *JsonErrorChar(U8 *buf, U8 ch)
{
  if (ch == '\0') {
    StrCpy(buf, "'\\0'");
  } else if (ch < 0x20 || ch == 0x7F) {
    StrPrint(buf, "'\\x%02X'", ch);
  } else {
    StrPrint(buf, "'%c'", ch);
  }
  return buf;
}

static U8 *JsonGetStrerrorInternal(I32 error, U8 ch, I64 line, I64 col)
{
  U8 c[16], *what;
  JsonErrorChar(c, ch);
  switch (error) {
    case JSON_OK:
      return StrPrint(NULL, "Success");
    case JSON_INVALID_UTF16:
      what = "Invalid UTF-16 code in a \\u escape"; break;
    case JSON_INVALID_UTF16_SURROGATE:
      what = "Invalid UTF-16 surrogate pair in a \\u escape"; break;
    case JSON_INVALID_HEX:
      what = "Expected 4 hex digits after \\u"; break;
    case JSON_INVALID_STRING_NOT_TERMINATED:
      what = "Unterminated string"; break;
    case JSON_INVALID_NUMBER:
    case JSON_INVALID_DECIMAL:
    case JSON_INVALID_SIGN:
      return StrPrint(NULL, "Invalid number at %s at line %d, column %d",
                      c, line, col);
    case JSON_INVALID_KEY_TERMINATOR_CHARACTER:
      return StrPrint(NULL, "Expected '\"' to start an object key, got %s at "
                      "line %d, column %d", c, line, col);
    case JSON_INVALID_KEY_VALUE_SEPARATOR:
      return StrPrint(NULL, "Expected ':' after an object key, got %s at line "
                      "%d, column %d", c, line, col);
    case JSON_INVALID_ARRAY_CHARACTER:
      return StrPrint(NULL, "Expected ',' or ']' in an array, got %s at line "
                      "%d, column %d", c, line, col);
    case JSON_INVALID_ESCAPE_CHARACTER:
      return StrPrint(NULL, "Invalid escape character %s after '\\' at line "
                      "%d, column %d", c, line, col);
    case JSON_INVALID_CONTROL_CHARACTER:
      return StrPrint(NULL, "Unescaped control character %s in a string (write "
                      "it as \\u%04x) at line %d, column %d", c, ch, line, col);
    case JSON_NESTING_TOO_DEEP:
      return StrPrint(NULL, "Arrays and objects nested more than %d deep at "
                      "line %d, column %d", JSON_MAX_DEPTH, line, col);
    case JSON_INVALID_BOOL:
    case JSON_INVALID_LITERAL:
      what = "Invalid literal, expected true, false or null"; break;
    case JSON_INVALID_JSON_TYPE_CHAR:
    case JSON_INVALID_TYPE:
      if (ch == '\0') {
        what = "Unexpected end of the JSON text"; break;
      }
      return StrPrint(NULL, "Unexpected character %s where a value or ',' "
                      "was expected at line %d, column %d", c, line, col);
    case JSON_CANNOT_START_PARSE:
      what = "Expected a JSON value (object, array, string, number, true, "
             "false or null)"; break;
    case JSON_CANNOT_ADVANCE:
    case JSON_EOF:
      what = "Unexpected end of the JSON text"; break;
    default:
      return NULL;
  }
  return StrPrint(NULL, "%s at line %d, column %d", what, line, col);
}

/* If the json has state this will return the error as a human readable string
 * must be freed by the caller (allows it to be thread safe). It would be
 * advisable to use this for debugging purposes only as it calls malloc */
U8 *JsonGetStrerror(Json *json)
{
  if (!json || !json->state) { /* NULL or not a root: nothing to report */
    return NULL;
  }
  if (json->state->error != JSON_OK) {
    U8 *str = JsonGetStrerrorInternal(json->state->error,json->state->ch,
        json->state->line,json->state->col);
    return str;
  }
  return NULL;
}

/* Print the error to stdout ("No errors" if there was none); nothing for
 * NULL or a node that isn't a root */
U0 JsonPrintError(Json *json)
{
  if (!json || !json->state) { /* NULL or not a root: prints nothing */
    return;
  }
  U8 *str_error = JsonGetStrerror(json);
  if (str_error) {
    "%s\n", str_error;
    Free(str_error);
  } else {
    "No errors\n";
  }
}

public Bool JsonOk(Json *j)
{
  if (!j) return FALSE;
  return j->state && j->state->error == JSON_OK;
}

Bool JsonIsObject(Json *j)
{
  if (!j) return FALSE;
  return j->type == JSON_OBJECT;
}

Bool JsonIsArray(Json *j)
{
  if (!j) return FALSE;
  return j->type == JSON_ARRAY;
}

Bool JsonIsNull(Json *j)
{
  if (!j) return FALSE;
  return j->type == JSON_NULL;
}

Bool JsonIsBool(Json *j)
{
  if (!j) return FALSE;
  return j->type == JSON_BOOL;
}

Bool JsonIsString(Json *j)
{
  if (!j) return FALSE;
  return j->type == JSON_STRING;
}

Bool JsonIsInt(Json *j)
{
  if (!j) return FALSE;
  return j->type == JSON_INT;
}

Bool JsonIsFloat(Json *j)
{
  if (!j) return FALSE;
  return j->type == JSON_FLOAT;
}

static Bool JsonTypeCheck(Json *j, U8 tk)
{
  switch (tk) {
    case 's':
      if (!JsonIsString(j)) {
        return FALSE;
      }
      return TRUE;
    case 'i':
      if (!JsonIsInt(j)) {
        return FALSE;
      }
      return TRUE;
    case 'f':
      if (!JsonIsFloat(j)) {
        return FALSE;
      }
      return TRUE;
    case 'o':
      if (!JsonIsObject(j)) {
        return FALSE;
      }
      return TRUE;
    case 'a':
      if (!JsonIsArray(j)) {
        return FALSE;
      }
      return TRUE;
    case 'b':
      if (!JsonIsBool(j)) {
        return FALSE;
      }
      return TRUE;
    case '!':
      if (!JsonIsNull(j)) {
        return FALSE;
      }
      return TRUE;
    default:
      return FALSE;
  }
}

/**
 * Get item from an array of json or return null
 */
Json *JsonArrayAt(Json *j, I64 idx) {
  if (!JsonIsArray(j) || idx < 0) {
    return NULL;
  }

  auto node = j->array;
  auto i = 0;

  while (node && i < idx) {
    node = node->next;
    ++i;
  }

  if (!node) return NULL;
  /* The idx was passed the number of elements in the list */
  if (i != idx) return NULL;

  return node;
}

static Json *JsonObjectAt(Json *j, U8 *name)
{
  if (!JsonIsObject(j)) {
    return NULL;
  }
  if (name == NULL) {
    return NULL;
  }

  auto el = j->object;
  while ((el != NULL) && (el->key != NULL) && StrCmp(name,el->key) != 0) {
    el = el->next;
  }

  if ((el == NULL) || (el->key == NULL)) {
    return NULL;
  }

  return el;
}

public Json *JsonObjectGet(Json *obj, U8 *key)
{// The value stored under exactly `key` (the first one if it is repeated),
 // or NULL if obj isn't an object or has no such key. Any key works, also
 // ones JsonSelect can't spell ('.', '[', ':' ...). A view, don't release.
  return JsonObjectAt(obj, key);
}

public Json *JsonArrayGet(Json *arr, I64 i)
{// Item i (from 0) of an array, or NULL if arr isn't an array or i is
 // negative or past the end. A view, don't release.
  return JsonArrayAt(arr, i);
}

public I64 JsonLen(Json *node)
{// The number of items of an array or object; 0 for anything else and NULL
  Json *it;
  I64 n = 0;
  if (JsonIsArray(node)) {
    it = node->array;
  } else if (JsonIsObject(node)) {
    it = node->object;
  } else {
    return 0;
  }
  while (it) {
    n++;
    it = it->next;
  }
  return n;
}

/**
 * Allows selections like:
 *
 * Json *J = JsonParse(text);
 * Json *width = JsonSelect(J,".foo.bar[*].baz",4);      // [*] takes an I64
 * Json *height = JsonSelect(J,".tv.type[4].*","name");  // .* takes a U8 *
 * Json *price = JsonSelect(J,".clothes.shirt[4].price_*","eur");
 *
 * You can also include a :<type> specifier, typically at the end, to verify the
 * type of the final JSON object selected. If the type doesn't match, the
 * function will return NULL. For instance, the specifier .foo.bar:s will return
 * NULL unless the root object has a 'foo' field, which is an object with a
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
public Json *JsonSelect(Json *j, U8 *fmt, ...)
{
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
   * encountered), the function will stop processing and return NULL. If
   * everything goes smoothly, the function will return the selected JSON
   * object.
   */
  I64 kind, arg = 0, path_len, len, idx;
  U8 path[JSON_SEL_MAX_BUF + 1], buf[32], *s, *ptr = fmt;

  if (!j || !fmt || (*ptr != '.' && *ptr != '[')) {
    return NULL;
  }

  while (*ptr) {
    switch (*ptr) {
      case '.': kind = JSON_SEL_OBJ; break;
      case '[': kind = JSON_SEL_ARRAY; break;
      case ':': kind = JSON_SEL_TYPECHECK; break;
      default:  return NULL;
    }
    ptr++;

    /* The component runs to the next '.', '[', ']', ':' or the end; a
     * '*' takes the next argument: an I64 index in [..], a string after
     * '.'. */
    path_len = 0;
    while (*ptr && *ptr != '.' && *ptr != '[' && *ptr != ']' && *ptr != ':') {
      if (*ptr == '*') {
        if (kind == JSON_SEL_ARRAY) {
          len = snprintf(buf, sizeof(buf), "%ld", argv[arg++](I64));
          s = buf;
        } else if (kind == JSON_SEL_OBJ) {
          s = argv[arg++](U8 *);
          if (!s) return NULL;
          len = StrLen(s);
        } else {
          return NULL;
        }
      } else {
        s = ptr;
        len = 1;
      }
      if (path_len + len > JSON_SEL_MAX_BUF) {
        return NULL;
      }
      MemCpy(path + path_len, s, len);
      path_len += len;
      ptr++;
    }
    path[path_len] = '\0';
    if (path_len == 0) {
      return NULL; /* ".", "[", "[]", "..x", ":" */
    }

    switch (kind) {
      case JSON_SEL_ARRAY:
        if (*ptr != ']') return NULL;
        ptr++;
        /* only digits: no sign, spaces or trailing junk */
        idx = 0;
        for (len = 0; len < path_len; ++len) {
          if (path[len] < '0' || path[len] > '9') return NULL;
          idx = idx * 10 + path[len] - '0';
          if (idx < 0) return NULL; /* overflow */
        }
        j = JsonArrayAt(j, idx);
        break;
      case JSON_SEL_OBJ:
        j = JsonObjectAt(j, path);
        break;
      case JSON_SEL_TYPECHECK:
        if (path_len != 1 || !JsonTypeCheck(j, path[0])) return NULL;
        break;
    }
    if (!j) {
      return NULL;
    }
  }

  return j;
}
