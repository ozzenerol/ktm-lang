module user;
import box;

/* instances of box's generic made and used in this module: in a field,
 * a local, over this module's own type, nested */
pub struct Term { i64 x; };
struct Holder { box.Box<i64> b; };

pub i64 run() {
    Holder h;
    h.b = box.new<i64>(5);
    box.Box<i64> local = box.new<i64>(6);
    Term t = {9};
    box.Box<Term *> bt = box.new<Term *>(&t);
    box.Box<box.Box<i64>> bb = box.new<box.Box<i64>>(box.new<i64>(4));
    return h.b.get() + local.get() + bt.get().x + bb.get().get();
}
