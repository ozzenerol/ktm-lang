module geo;

/* Overloads over two files of one module: area, describe and the
 * method Rect.grow; scale(f64) is private while scale(i64) is pub */

pub struct Rect { i64 w; i64 h; };
pub struct Circle { i64 r; };

pub i64 area(Rect *r) { return r.w * r.h; }
pub i64 area(Circle *c) { return 3 * c.r * c.r; }

pub void Rect.grow(i64 by) { self.w += by; self.h += by; }
pub void Rect.grow(Rect *other) { self.w += other.w; self.h += other.h; }

pub i64 scale(i64 x) { return x * scaleFactor(); }
i64 scale(f64 x) { return (i64)(x * 10.0); }

/* the module's own calls, to overloads in the other file too */
pub i64 total(Rect *r, Circle *c) { return area(r) + area(c) + scaleFactor(); }
