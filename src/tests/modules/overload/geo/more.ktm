module geo;
import str;

i64 scaleFactor() { return 2; }

pub String describe(i64 n) { return str.format("n=%d", n); }
pub String describe(u8[] what) { return str.format("what=%.*s", (i32)what.len, what.ptr); }
pub i64 inner() { return scale(1.5); }
