module web;
import mem;

pub struct Conn {
    i32 fd;
    private u8 *buf;
    private i64 used;
};

/* inherits Conn's private fields: still private to web */
pub struct Tls : Conn {
    i64 level;
};

/* a range-for container whose fields are private */
pub struct Bytes {
    private i64 size;
    private u8 *entries;
};

pub Conn *dial(i32 fd) {
    Conn *c = mem.alloc(sizeof(Conn));
    c.fd = fd;
    c.buf = "hello";
    c.used = 5;
    return c;
}

pub i64 Conn.count() { return self.used; }

private i64 twice(i64 n) { return n * 2; }

pub Conn literal() { Conn c = {9, "lit", 3}; return c; }
