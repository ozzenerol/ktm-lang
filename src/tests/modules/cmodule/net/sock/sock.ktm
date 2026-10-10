/* an extern "c" in any module calls the C function by its C name */
module sock;

extern "c" i32 abs(i32 x);
pub i64 dist(i64 a, i64 b) { return abs(a - b); }
