import cstr, fs, json, libc, mem, proc, str, test, vec, yaml;

/* @yaml (v0.14.0): S.toYaml(yaml.Writer *) and S.fromYaml(yaml.Reader *)
 * made by the compiler, as @json makes toJson/fromJson; a struct with both
 * gets both and one freeDecoded. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool same(u8[] a, u8 *b) { return a.len == libc.strlen(b) && libc.memcmp(a.ptr, b, a.len) == 0; }

enum Kind { tool, part };

@json @yaml
struct Line {
    String sku;
    @omitEmpty i64 qty;
    Kind kind;
};

@yaml
struct Order {
    i64 id;
    @key("line-items") Line[] lines;
    vec.Vec<String> tags;
    f64 total;
    bool paid;
    Line *first;
    @skip i64 hits;
};

#define Y_FILE "__645_yaml_generate.ktm"
#define Y_OUT  "__645_yaml_generate.out"

bool compileFails(u8 *source, u8 *msg) {
    fs.remove(Y_FILE);
    if (!fs.writeAll(Y_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    i64 res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " Y_FILE " > " Y_OUT " 2>&1");
    u8 *out = fs.readAll(Y_OUT).take();
    bool ok = out && res == 1 && libc.strstr(out, msg) != null;
    if (!ok) { printf("\n    status %d\n%s\n", res, out); }
    mem.free(out);
    fs.remove(Y_FILE);
    fs.remove(Y_OUT);
    fs.remove("./a.out");
    return ok;
}

i32 main() {
    printf("Test - @yaml generated methods: ");

    Line l;
    mem.set(&l, 0, sizeof(Line));
    l.sku = str.from("A1");
    l.qty = 3;
    l.kind = Kind.part;
    Order o;
    mem.set(&o, 0, sizeof(Order));
    o.id = 7;
    o.lines = (&l)[0:1];
    o.tags.push(str.from("a b"));
    o.tags.push(str.from("true"));
    o.total = 2.5;
    o.paid = true;
    o.first = &l;
    o.hits = 9;

    yaml.Writer w = yaml.writerNew();
    o.toYaml(&w);
    String text = w.take();
    u8 *want = "id: 7\nline-items:\n  - sku: A1\n    qty: 3\n    kind: part\ntags:\n  - a b\n  - \"true\"\n"
               "total: 2.5\npaid: true\nfirst:\n  sku: A1\n  qty: 3\n  kind: part\n";
    bool written = same(text, want);
    check(written, "toYaml writes block style, quoting what would read back as something else");
    if (!written) { printf("    got:\n%s\n    want:\n%s\n", text, want); }

    Order b;
    mem.set(&b, 0, sizeof(Order));
    yaml.Reader r = yaml.readerNew(text);
    b.fromYaml(&r);
    r.finish();
    check(b.id == 7 && b.total == 2.5 && b.paid && b.hits == 0, "scalars back, @skip not read");
    check(b.lines.len == 1 && same(b.lines[0].sku, "A1") && b.lines[0].qty == 3 && b.lines[0].kind == Kind.part,
          "a slice of structs with an enum by name");
    check(b.tags.len == 2 && same(b.tags.data[1], "true"), "a quoted \"true\" stays a string");
    check(b.first && same(b.first.sku, "A1"), "a struct pointer");
    b.freeDecoded();
    check(b.lines.len == 0 && b.first == null && b.tags.len == 0, "freeDecoded frees and clears");

    Order c;
    mem.set(&c, 0, sizeof(Order));
    yaml.Reader r2 = yaml.readerNew("# an order\nzzz: [1, {a: b}]\nid: 3\nline-items: [{sku: q, kind: 0}]\n");
    c.fromYaml(&r2);
    check(c.id == 3 && c.lines.len == 1 && same(c.lines[0].sku, "q") && c.lines[0].kind == Kind.tool,
          "flow style, comments, unknown keys, an enum by number");
    c.freeDecoded();

    bool threw = false;
    Order d;
    mem.set(&d, 0, sizeof(Order));
    try {
        yaml.Reader r3 = yaml.readerNew("id: 1\nline-items:\n  - kind: nope\n");
        d.fromYaml(&r3);
    } catch (Error e) {
        threw = e.code == yaml.ERR_DECODE && libc.strstr(e.message, "not a member of Kind") != null;
        if (!threw) { printf("\n    error: %s\n", e.message); }
    }
    d.freeDecoded();
    check(threw, "a decode error says what is wrong");

    json.Writer jw = json.writerNew();
    l.toJson(&jw);
    String j = jw.take();
    check(same(j, "{\"sku\":\"A1\",\"qty\":3,\"kind\":\"part\"}"), "@json @yaml: both");
    j.free();

    check(compileFails("struct L { i64 a; };\n@yaml struct U { i64 a; };\ni32 main() { return 0; }\n",
                       "`@yaml` on U needs `import yaml;` in its file"), "@yaml without import yaml");
    check(compileFails("import yaml;\nstruct L { i64 a; };\n@yaml struct U { L l; };\ni32 main() { return 0; }\n",
                       "it is a L, which has no @yaml"), "a struct field without @yaml");

    text.free();
    l.sku.free();
    o.tags.data[0].free();
    o.tags.data[1].free();
    o.tags.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
