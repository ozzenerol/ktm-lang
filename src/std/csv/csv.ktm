module csv;

pub struct Parser {
    u8 *csv;
    u64 len,off,rowOff,nrows,nfields;
    i64 state;
};

pub struct Slice {
    u64 idx,off,len;
};

pub void init(Parser *p, u8 *csv, u64 len) {
    p.csv = csv;
    p.len = len;
    p.off = p.rowOff = p.nrows = p.nfields = p.state = 0;
}

pub i64 next(Parser *p, Slice *s) {
    u8 *csv = p.csv;
    switch (p.state) {
        case 0:
            s.idx = p.nfields++;
            s.off = p.off;
            s.len = 0;
            for (i64 state = 1; p.off < p.len;) {
                i64 b = csv[p.off++];
                state ^= b == 0x22;
                switch (-state & b) {
                    case 0x2C: return FIELD;
                    case 0x0D:
                        p.off += p.off < p.len && csv[p.off] == 0x0A;
                        fallthrough;
                    case 0x0A:
                        p.state = 1;
                        return FIELD;
                }
                s.len++;
            }
            p.state = 1;
            return FIELD;

        case 1:
            s.idx = p.nrows++;
            s.off = p.rowOff;
            s.len = p.off - p.rowOff;
            p.nfields = 0;
            p.rowOff = p.off;
            if (p.off < p.len) { p.state = 0; }
            else                 { p.state = 2; }
            return ROW;

        case 2: return EOF;
    }
    return EOF;
}
