module box;
pub struct Box<T> { T v; };
pub Box<T> new<T>(T v) { Box<T> b; b.v = v; return b; }
pub T Box<T>.get() { return self.v; }
