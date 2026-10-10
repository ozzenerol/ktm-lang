module geo;

pub struct Point { i64 x; i64 y; };
pub enum Kind { square, circle };
pub const i64 SIZE = 4;

/* calls square() and uses g_calls, both defined in a later file */
pub i64 area(Kind k, i64 side) {
  if (k == Kind.square) return square(side);
  g_calls++;
  return 3 * side * side;
}
