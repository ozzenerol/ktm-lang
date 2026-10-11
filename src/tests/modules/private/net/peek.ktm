module net;

/* another file of net uses the private fields too */
pub u8 *peek(Conn *c) { return c.buf; }

pub i64 sum(Bytes *b) {
    i64 t = 0;
    for (auto x : b) { t += x; }
    return t + twice(0);
}

pub i64 tlsUsed(Tls *t) { return t.used + t.level; }
