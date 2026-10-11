module shop;

/* Uses types, an enum, a const and a method from the later file
 * b_types.ktm: every name of a module is visible in any order. */
pub struct Order {
    Box *box;          // pointer to a later type
    Item first;        // a later type by value
    Kind kind;         // a later enum
    i64 sizes[SLOTS];  // a later const as an array size
};

pub i64 orderTotal(Order *o) {
    return o.box.weight + o.first.price + o.sizes[SLOTS - 1] + o.kind;
}

pub Box *orderBox(Order *o) { return o.box; }

pub i64 boxWeight(Box *b) { return b.heavier(1); }

/* a global array sized by a later const, and a method of a later type */
pub i64 gSlots[SLOTS * 2];
pub i64 Item.total() { return self.price + self.unit.cents; }
pub Kind kindOf(Order *o) { return o.kind; }
