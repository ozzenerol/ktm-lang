import cstr, mem, str, test, yaml;

/* yaml (v0.14.0): yaml.Writer writes block-style YAML and yaml.Reader
 * reads the practical subset (block and flow mappings and sequences,
 * plain, quoted and block scalars, comments, ---) with json.Reader's
 * calls. Anchors, aliases, tags, << merge keys and several documents are
 * "not supported" errors with line:col. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool is(u8[] got, u8 *want) {
    return str.equals(got, cstr.view(want));
}

void checkStr(u8[] got, u8 *want, u8 *what) {
    bool ok = is(got, want);
    if (!ok) { printf("\n  got [%.*s]\n  want [%s]", got.len, got.ptr, want); }
    check(ok, what);
}

struct Line {
    String sku;
    i64 qty;
    f64 price;
};

struct Order {
    i64 id;
    String customer;
    String note;
    String poem;
    String folded;
    String quoted;
    String single;
    Line lines[4];
    i64 nlines;
    String tags[4];
    i64 ntags;
    i64 flags;
    bool paid;
    bool hasDiscount;
    f64 rate;
    f64 big;
    i64 hex;
    String version;
    String empty;
};

void readLine(yaml.Reader *r, Line *l) {
    r.beginObject();
    while (r.nextKey()) {
        u8[] k = r.key();
        if (is(k, "sku"))        { r.readString(&l.sku); }
        else if (is(k, "qty"))   { l.qty = r.readInt(); }
        else if (is(k, "price")) { l.price = r.readFloat(); }
        else { r.skipValue(); }
    }
}

void readOrder(yaml.Reader *r, Order *o) {
    r.beginObject();
    while (r.nextKey()) {
        u8[] k = r.key();
        if (is(k, "id"))               { o.id = r.readInt(); }
        else if (is(k, "customer"))    { r.readString(&o.customer); }
        else if (is(k, "note"))        { r.readString(&o.note); }
        else if (is(k, "poem"))        { r.readString(&o.poem); }
        else if (is(k, "folded"))      { r.readString(&o.folded); }
        else if (is(k, "quoted"))      { r.readString(&o.quoted); }
        else if (is(k, "single"))      { r.readString(&o.single); }
        else if (is(k, "paid"))        { o.paid = r.readBool(); }
        else if (is(k, "discount"))    { o.hasDiscount = !r.isNull(); if (o.hasDiscount) { r.skipValue(); } }
        else if (is(k, "rate"))        { o.rate = r.readFloat(); }
        else if (is(k, "big"))         { o.big = r.readFloat(); }
        else if (is(k, "hex"))         { o.hex = r.readInt(); }
        else if (is(k, "version"))     { r.readString(&o.version); }
        else if (is(k, "empty"))       { r.readString(&o.empty); }
        else if (is(k, "lines")) {
            r.beginArray();
            while (r.nextItem()) { readLine(r, &o.lines[o.nlines++]); }
        } else if (is(k, "tags")) {
            r.beginArray();
            while (r.nextItem()) { r.readString(&o.tags[o.ntags++]); }
        } else if (is(k, "flags")) {
            r.beginObject();
            while (r.nextKey()) { if (r.readBool()) { o.flags++; } }
        } else { r.skipValue(); }
    }
    r.finish();
}

/* Reads `text` as an Order, the error message ("" if none) */
String decodeError(u8 *text) {
    String msg;
    Order o;
    mem.set(&o, 0, sizeof(Order));
    yaml.Reader r = yaml.readerNew(cstr.view(text));
    try {
        readOrder(&r, &o);
    } catch (Error e) {
        if (e.code == yaml.ERR_DECODE) { msg.append(cstr.view(e.message)); }
        else { msg.appendFormat("code %x: %s", e.code, e.message); }
    }
    r.free();
    return msg;
}

/* Walks any document with peek(), the error message ("" if none) */
String walkError(u8 *text) {
    String msg;
    yaml.Reader r = yaml.readerNew(cstr.view(text));
    try {
        r.skipValue();
        r.finish();
    } catch (Error e) {
        msg.append(cstr.view(e.message));
    }
    r.free();
    return msg;
}

void checkError(u8 *text, u8 *want, u8 *what) {
    String msg = walkError(text);
    checkStr(msg.ptr[0:msg.len], want, what);
    msg.free();
}

i32 main() {
    printf("Test - yaml module: ");

    u8 *doc =
        "# an order\n"
        "---\n"
        "id: 1042\n"
        "customer: Ada Lovelace   # trailing comment\n"
        "note: a plain scalar\n"
        "  that goes on\n"
        "\n"
        "  over lines\n"
        "poem: |\n"
        "  line one\n"
        "    indented\n"
        "  line three\n"
        "folded: >-\n"
        "  folded\n"
        "  text\n"
        "\n"
        "  new paragraph\n"
        "quoted: \"tab\\there \\u00e9 \\\"q\\\"\"\n"
        "single: 'it''s # not a comment'\n"
        "lines:\n"
        "  - sku: A-1\n"
        "    qty: 2\n"
        "    price: 9.5\n"
        "  - {sku: B-2, qty: 1, price: 20}\n"
        "tags: [new, 'gift wrap', \"x\"]\n"
        "flags:\n"
        "  a: true\n"
        "  b: False\n"
        "  c: TRUE\n"
        "paid: true\n"
        "discount: ~\n"
        "rate: .inf\n"
        "big: 1e3\n"
        "hex: 0x1F\n"
        "version: '1.0'\n"
        "empty:\n"
        "unknown:\n"
        "- skipped\n"
        "- [nested, {deep: [1, 2]}]\n"
        "...\n";

    Order o;
    mem.set(&o, 0, sizeof(Order));
    yaml.Reader r = yaml.readerNew(cstr.view(doc));
    String err;
    try { readOrder(&r, &o); }
    catch (Error e) { err.append(cstr.view(e.message)); }
    checkStr(err.ptr[0:err.len], "", "the order reads with no error");
    check(o.id == 1042, "id");
    checkStr(o.customer.ptr[0:o.customer.len], "Ada Lovelace", "a plain scalar ends before a comment");
    checkStr(o.note.ptr[0:o.note.len], "a plain scalar that goes on\nover lines", "a multi-line plain scalar folds");
    checkStr(o.poem.ptr[0:o.poem.len], "line one\n  indented\nline three\n", "| keeps lines and indentation");
    checkStr(o.folded.ptr[0:o.folded.len], "folded text\nnew paragraph", ">- folds and strips");
    checkStr(o.quoted.ptr[0:o.quoted.len], "tab\there \xc3\xa9 \"q\"", "double-quoted escapes");
    checkStr(o.single.ptr[0:o.single.len], "it's # not a comment", "single-quoted '' and #");
    check(o.nlines == 2, "two lines");
    checkStr(o.lines[0].sku.ptr[0:o.lines[0].sku.len], "A-1", "block mapping in a sequence");
    check(o.lines[0].qty == 2 && o.lines[0].price == 9.5, "qty and price");
    checkStr(o.lines[1].sku.ptr[0:o.lines[1].sku.len], "B-2", "flow mapping in a sequence");
    check(o.lines[1].qty == 1 && o.lines[1].price == 20.0, "flow qty, int read as float");
    check(o.ntags == 3, "three tags");
    checkStr(o.tags[1].ptr[0:o.tags[1].len], "gift wrap", "flow sequence, quoted item");
    check(o.flags == 2, "true, False and TRUE");
    check(o.paid && !o.hasDiscount, "bool and ~");
    check(o.rate > 1e308, ".inf");
    check(o.big == 1000.0, "1e3");
    check(o.hex == 31, "0x1F");
    checkStr(o.version.ptr[0:o.version.len], "1.0", "a quoted number is a string");
    checkStr(o.empty.ptr[0:o.empty.len], "", "no value reads as an empty string");
    r.free();

    /* peek kinds, the same numbers as json's */
    yaml.Reader p = yaml.readerNew("[a, 1, 1.5, true, null, ~, [], {}, '2', 99999999999999999999]");
    p.beginArray();
    i64 want[10] = {yaml.STRING, yaml.INT, yaml.FLOAT, yaml.BOOL, yaml.NULL, yaml.NULL,
                    yaml.ARRAY, yaml.OBJECT, yaml.STRING, yaml.FLOAT};
    bool kinds = true;
    for (i64 i = 0; p.nextItem(); i++) {
        if (p.peek() != want[i]) { kinds = false; printf("\n  item %d: kind %d\n", i, p.peek()); }
        p.skipValue();
    }
    p.finish();
    check(kinds, "peek() kinds");
    check(yaml.STRING == 0 && yaml.FLOAT == 1 && yaml.INT == 2 && yaml.ARRAY == 4 &&
          yaml.OBJECT == 5 && yaml.BOOL == 6 && yaml.NULL == 7, "kinds match json's");
    p.free();

    /* readString takes any scalar; readInt doesn't take a string */
    yaml.Reader q = yaml.readerNew("a: 12\nb: true\nc: '7'\n");
    q.beginObject();
    q.nextKey();
    checkStr(q.readStr(), "12", "readStr on a plain int");
    q.nextKey();
    checkStr(q.readStr(), "true", "readStr on a plain bool");
    q.nextKey();
    String m;
    try { q.readInt(); } catch (Error e) { m.append(cstr.view(e.message)); }
    checkStr(m.ptr[0:m.len], "3:4: c: expected an integer, got a string", "a quoted number isn't an int");
    m.free();
    q.free();

    /* decode errors: line:col and the path */
    String e1 = decodeError("id: 1\nlines:\n  - sku: A\n    qty: many\n");
    checkStr(e1.ptr[0:e1.len], "4:10: lines[0].qty: expected an integer, got a string", "error path into a sequence");
    e1.free();
    String e2 = decodeError("id: 99999999999999999999\n");
    checkStr(e2.ptr[0:e2.len], "1:5: id: expected an integer that fits an i64, got a number", "int overflow");
    e2.free();
    String e3 = decodeError("- 1\n");
    checkStr(e3.ptr[0:e3.len], "1:1: expected a mapping, got a sequence", "wrong top-level kind");
    e3.free();

    /* not supported, and not YAML */
    checkError("a: &x 1\nb: 2\n", "1:4: a: anchors (&) are not supported", "anchor");
    checkError("a: 1\nb: *x\n", "2:4: b: aliases (*) are not supported", "alias");
    checkError("a: !!str 1\n", "1:4: a: tags (!) are not supported", "tag");
    checkError("base: {a: 1}\nmore:\n  <<: {b: 2}\n", "3:3: more: merge keys (<<) are not supported", "merge key");
    checkError("a: 1\n---\nb: 2\n", "2:1: several documents (a second ---) are not supported", "two documents");
    checkError("? a\n: b\n", "1:1: complex keys (?) are not supported", "complex key");
    checkError("a:\n\t- 1\n", "2:1: a: a tab in the indentation (YAML indents with spaces)", "tab indentation");
    checkError("a: 1\n  b: 2\n", "2:3: a: a key inside a plain scalar that goes on over several lines (quote the value or indent the key less)", "a key under a scalar");
    checkError("a: b: c\n", "1:4: a: a mapping can't start on the same line as its key", "a: b: c");
    checkError("a: [1, 2\n", "1:4: a[1]: a [ with no closing ]", "unclosed [");
    checkError("a: \"x\\q\"\n", "1:6: a: an unknown or malformed escape in a string", "bad escape");
    checkError("a:\n  b: 1\n c: 2\n", "3:2: a: bad indentation: expected a key in line with the ones above", "bad indentation");
    yaml.Reader f = yaml.readerNew("- a\n  - b\n");
    f.beginArray();
    f.nextItem();
    checkStr(f.readStr(), "a - b", "an indented '- ' line goes on a plain scalar");
    f.free();
    checkError("", "", "an empty document is null");

    /* Writer: block style, quoting only where needed */
    yaml.Writer w = yaml.writerNew();
    w.beginObject();
    w.key("name"); w.str("Ada");
    w.key("id"); w.int(-7);
    w.key("big"); w.uint(18446744073709551615);
    w.key("rate"); w.float(2.0);
    w.key("inf"); w.float(1e308 * 10);
    w.key("ok"); w.bool(true);
    w.key("none"); w.nil();
    w.key("needs quotes"); w.str("true");
    w.key("colon"); w.str("a: b");
    w.key("multi"); w.str("x\ny");
    w.key("empty"); w.str("");
    w.rawKey("tags");
    w.beginArray(); w.str("a"); w.str("- b"); w.end();
    w.key("lines");
    w.beginArray();
    w.beginObject(); w.key("sku"); w.str("A-1"); w.key("qty"); w.int(2); w.end();
    w.beginArray(); w.int(1); w.int(2); w.end();
    w.beginObject(); w.end();
    w.end();
    w.key("nested");
    w.beginObject(); w.key("deep"); w.beginObject(); w.key("x"); w.int(1); w.end(); w.end();
    w.key("none2"); w.beginArray(); w.end();
    w.end();
    String out = w.take();
    u8 *wantOut =
        "name: Ada\n"
        "id: -7\n"
        "big: 18446744073709551615\n"
        "rate: 2.0\n"
        "inf: .inf\n"
        "ok: true\n"
        "none: null\n"
        "needs quotes: \"true\"\n"
        "colon: \"a: b\"\n"
        "multi: \"x\\ny\"\n"
        "empty: \"\"\n"
        "tags:\n"
        "  - a\n"
        "  - \"- b\"\n"
        "lines:\n"
        "  - sku: A-1\n"
        "    qty: 2\n"
        "  - - 1\n"
        "    - 2\n"
        "  - {}\n"
        "nested:\n"
        "  deep:\n"
        "    x: 1\n"
        "none2: []\n";
    checkStr(out.ptr[0:out.len], wantOut, "Writer output");

    /* and it reads back */
    yaml.Reader back = yaml.readerNew(out.ptr[0:out.len]);
    back.beginObject();
    bool same = true;
    while (back.nextKey()) {
        u8[] k = back.key();
        if (is(k, "big"))               { same = same && back.readUint() == 18446744073709551615; }
        else if (is(k, "rate"))         { same = same && back.readFloat() == 2.0 && true; }
        else if (is(k, "needs quotes")) { same = same && is(back.readStr(), "true"); }
        else if (is(k, "colon"))        { same = same && is(back.readStr(), "a: b"); }
        else if (is(k, "multi"))        { same = same && is(back.readStr(), "x\ny"); }
        else if (is(k, "none"))         { same = same && back.isNull(); }
        else if (is(k, "empty"))        { same = same && back.peek() == yaml.STRING && is(back.readStr(), ""); }
        else { back.skipValue(); }
    }
    back.finish();
    check(same, "the Writer's output reads back");
    back.free();
    out.free();

    bool threw = false;
    w.int(1);
    try { w.str("x"); } catch (Error e) { threw = e.code == yaml.ERR_WRITER; }
    check(threw, "a second top-level value throws");
    w.free();
    threw = false;
    w.beginObject();
    try { w.int(1); } catch (Error e) { threw = e.code == yaml.ERR_WRITER; }
    check(threw, "a value with no key throws");
    w.free();

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
