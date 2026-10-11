module shop;

pub const i64 SLOTS = 3;
pub enum Kind { small = 10, big = 20 };

pub struct Box {
    i64 weight;
    Order *back;       // and back to the earlier file's type
};

pub struct Item {
    i64 price;
    Price unit;        // a type of the next file, by value
};

pub i64 Box.heavier(i64 by) { return self.weight + by; }
