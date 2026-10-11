module json;

/* JsonParse flags: none, or keep numbers as their text (JSON_STRNUM) */
pub const i64 NO_FLAGS = (0x0);

pub const i64 STRNUM_FLAG = (0x1);

/* Json node types: string (str, str_len), float (f64), integer (i64),
 * number kept as text (strnum, with JSON_STRNUM_FLAG), array (array: the
 * first item, null when empty), object (object: the first member, null
 * when empty), boolean (boolean), null. */
pub const i64 STRING = 0;

pub const i64 FLOAT = 1;

pub const i64 INT = 2;

pub const i64 STRNUM = 3;

pub const i64 ARRAY = 4;

pub const i64 OBJECT = 5;

pub const i64 BOOL = 6;

pub const i64 NULL = 7;

/* JsonParse error codes (state->error). Not produced any more, kept for
 * old code: 1, 6, 7, 9, 10, 11 and 17 (the end of the input is reported
 * as 8, or as the error of what was being read). */
pub const i64 OK = 0;

/* Not produced */
pub const i64 INVALID_UTF16 = 1;

/* A \u escape with an unpaired or invalid surrogate */
pub const i64 INVALID_UTF16_SURROGATE = 2;

/* \u not followed by 4 hex digits */
pub const i64 INVALID_HEX = 3;

/* A string with no closing quote */
pub const i64 INVALID_STRING_NOT_TERMINATED = 4;

/* A malformed number (01, +1, .5, 1., 1e, -) */
pub const i64 INVALID_NUMBER = 5;

/* Not produced */
pub const i64 INVALID_DECIMAL = 6;

/* Not produced */
pub const i64 INVALID_SIGN = 7;

/* No value where one was expected, an unexpected character after a
 * value (also after the top-level one), or the input ended early */
pub const i64 INVALID_JSON_TYPE_CHAR = 8;

/* Not produced (see JSON_INVALID_LITERAL) */
pub const i64 INVALID_BOOL = 9;

/* Not produced */
pub const i64 INVALID_TYPE = 10;

/* Not produced */
pub const i64 CANNOT_ADVANCE = 11;

/* Empty input, or it doesn't start with a JSON value */
pub const i64 CANNOT_START_PARSE = 12;

/* An object member that doesn't start with a '"' key */
pub const i64 INVALID_KEY_TERMINATOR_CHARACTER = 13;

/* Something other than whitespace between a key and ':' */
pub const i64 INVALID_KEY_VALUE_SEPARATOR = 14;

/* Something other than ',' or ']' after an array item */
pub const i64 INVALID_ARRAY_CHARACTER = 15;

/* An unknown escape such as \q in a string */
pub const i64 INVALID_ESCAPE_CHARACTER = 16;

/* Not produced */
pub const i64 EOF = 17;

/* Not true, false or null where a literal starts */
pub const i64 INVALID_LITERAL = 18;

/* A raw byte below 0x20 inside a string (write \n, \t, \u0001 ...) */
pub const i64 INVALID_CONTROL_CHARACTER = 19;

/* Arrays/objects nested deeper than JSON_MAX_DEPTH */
pub const i64 NESTING_TOO_DEEP = 20;

/* Bytes in a string that aren't UTF-8: a stray continuation byte, a
 * sequence cut off or too long for its value, a surrogate, or past
 * U+10FFFF (RFC 8259 text is UTF-8) */
pub const i64 INVALID_UTF8 = 21;

/* JsonParse rejects arrays and objects nested deeper than this */
pub const i64 MAX_DEPTH = 512;
