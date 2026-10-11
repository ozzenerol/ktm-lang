module json;

/* JsonParse flags: none, or keep numbers as their text (JSON_STRNUM) */
const i64 JSON_NO_FLAGS = (0x0);

const i64 JSON_STRNUM_FLAG = (0x1);

/* Json node types: string (str, str_len), float (f64), integer (i64),
 * number kept as text (strnum, with JSON_STRNUM_FLAG), array (array: the
 * first item, null when empty), object (object: the first member, null
 * when empty), boolean (boolean), null. */
const i64 JSON_STRING = 0;

const i64 JSON_FLOAT = 1;

const i64 JSON_INT = 2;

const i64 JSON_STRNUM = 3;

const i64 JSON_ARRAY = 4;

const i64 JSON_OBJECT = 5;

const i64 JSON_BOOL = 6;

const i64 JSON_NULL = 7;

/* JsonParse error codes (state->error). Not produced any more, kept for
 * old code: 1, 6, 7, 9, 10, 11 and 17 (the end of the input is reported
 * as 8, or as the error of what was being read). */
const i64 JSON_OK = 0;

/* Not produced */
const i64 JSON_INVALID_UTF16 = 1;

/* A \u escape with an unpaired or invalid surrogate */
const i64 JSON_INVALID_UTF16_SURROGATE = 2;

/* \u not followed by 4 hex digits */
const i64 JSON_INVALID_HEX = 3;

/* A string with no closing quote */
const i64 JSON_INVALID_STRING_NOT_TERMINATED = 4;

/* A malformed number (01, +1, .5, 1., 1e, -) */
const i64 JSON_INVALID_NUMBER = 5;

/* Not produced */
const i64 JSON_INVALID_DECIMAL = 6;

/* Not produced */
const i64 JSON_INVALID_SIGN = 7;

/* No value where one was expected, an unexpected character after a
 * value (also after the top-level one), or the input ended early */
const i64 JSON_INVALID_JSON_TYPE_CHAR = 8;

/* Not produced (see JSON_INVALID_LITERAL) */
const i64 JSON_INVALID_BOOL = 9;

/* Not produced */
const i64 JSON_INVALID_TYPE = 10;

/* Not produced */
const i64 JSON_CANNOT_ADVANCE = 11;

/* Empty input, or it doesn't start with a JSON value */
const i64 JSON_CANNOT_START_PARSE = 12;

/* An object member that doesn't start with a '"' key */
const i64 JSON_INVALID_KEY_TERMINATOR_CHARACTER = 13;

/* Something other than whitespace between a key and ':' */
const i64 JSON_INVALID_KEY_VALUE_SEPARATOR = 14;

/* Something other than ',' or ']' after an array item */
const i64 JSON_INVALID_ARRAY_CHARACTER = 15;

/* An unknown escape such as \q in a string */
const i64 JSON_INVALID_ESCAPE_CHARACTER = 16;

/* Not produced */
const i64 JSON_EOF = 17;

/* Not true, false or null where a literal starts */
const i64 JSON_INVALID_LITERAL = 18;

/* A raw byte below 0x20 inside a string (write \n, \t, \u0001 ...) */
const i64 JSON_INVALID_CONTROL_CHARACTER = 19;

/* Arrays/objects nested deeper than JSON_MAX_DEPTH */
const i64 JSON_NESTING_TOO_DEEP = 20;

/* JsonParse rejects arrays and objects nested deeper than this */
const i64 JSON_MAX_DEPTH = 512;
