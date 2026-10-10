/* Test 439's fixture: a tree of modules built into one library */
module geo;

pub struct Point { i64 x; i64 y; };
pub const i64 SIZE = 4;
pub i64 area(i64 side) { return side * side; }
