module yaml;

/* What Reader.peek() says the next value is: the same numbers as json's
 * kinds, so code can serve both. STRING is any quoted or block scalar
 * and any plain one that isn't one of the others; INT an integer that
 * fits an i64; FLOAT any other number (also .inf and .nan); BOOL true or
 * false; NULL null, ~ or nothing at all; ARRAY a sequence; OBJECT a
 * mapping. */
pub const i64 STRING = 0;

pub const i64 FLOAT = 1;

pub const i64 INT = 2;

pub const i64 ARRAY = 4;

pub const i64 OBJECT = 5;

pub const i64 BOOL = 6;

pub const i64 NULL = 7;

/* The code a Reader throws for text that isn't YAML (or uses a part of it
 * yaml doesn't support) and for a value of the wrong kind. The message
 * says where: "3:14: items[2].price: expected a number, got a string". */
pub const i64 ERR_DECODE = 0x6B746D14;

/* The code a Writer throws when it is used out of order (a value where a
 * key is expected, a key outside a mapping, end() with nothing open,
 * take() before the document is complete) or given a string that isn't
 * UTF-8. The message says which. */
pub const i64 ERR_WRITER = 0x6B746D15;

/* Mappings and sequences nested deeper than this are an error */
pub const i64 MAX_DEPTH = 512;
