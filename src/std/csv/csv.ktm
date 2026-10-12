module csv;

pub struct Parser {
    u8 *csv;
    u64 len,off,rowOff,nrows,nfields;
    i64 state;
};

/* What next() found: a field (idx is its number in the row, text its
 * bytes, quotes included) or a row (idx is the row's number, text the
 * whole line); text is a view of the parser's input */
pub struct Item {
    u64 idx;
    u8[] text;
};

/* Parse text (a view: it must outlive the parser) */
pub void init(Parser *p, u8[] text) {
    p.csv = text.ptr;
    p.len = text.len;
    p.off = p.rowOff = p.nrows = p.nfields = p.state = 0;
}

/* The next field or row of p into *it: FIELD, ROW, or EOF at the end */
pub i64 next(Parser *p, Item *it) {
    u8 *csv = p.csv;
    switch (p.state) {
        case 0:
            it.idx = p.nfields++;
            u64 start = p.off, n = 0;
            it.text = csv[start:start];
            for (i64 state = 1; p.off < p.len;) {
                i64 b = csv[p.off++];
                state ^= b == 0x22;
                switch (-state & b) {
                    case 0x2C:
                        it.text = csv[start:start + n];
                        return FIELD;
                    case 0x0D:
                        p.off += p.off < p.len && csv[p.off] == 0x0A;
                        fallthrough;
                    case 0x0A:
                        p.state = 1;
                        it.text = csv[start:start + n];
                        return FIELD;
                }
                n++;
            }
            p.state = 1;
            it.text = csv[start:start + n];
            return FIELD;

        case 1:
            it.idx = p.nrows++;
            it.text = csv[p.rowOff:p.off];
            p.nfields = 0;
            p.rowOff = p.off;
            if (p.off < p.len) { p.state = 0; }
            else                 { p.state = 2; }
            return ROW;

        case 2: return EOF;
    }
    return EOF;
}
