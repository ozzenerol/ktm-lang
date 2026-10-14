module bag;
import vec;

/* generic methods of a module's structs (test 823): a plain struct's
 * and a generic one's, pub and private, one calling another and a
 * static helper (which a -lib build must export for the instances) */

pub struct Bag { vec.Vec<i64> items; };
pub void Bag.add(i64 x) { self.items.push(x); }
pub vec.Vec<U> Bag.map<U>(fn(i64) U f) {
    vec.Vec<U> out;
    for (auto x : self.items) { out.push(f(x)); }
    return out;
}
pub i64 Bag.countPlus<V>(V x) { return self.size<V>(x) + helper(); }
i64 Bag.size<V>(V x) { return self.items.len; }
void Bag.secret<V>(V x) { }
static i64 helper() { return 100; }

pub struct Box<T> { T v; };
pub Box<U> Box<T>.then<U>(fn(T) U f) { Box<U> b = {f(self.v)}; return b; }
