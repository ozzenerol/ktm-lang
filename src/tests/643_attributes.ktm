import cstr, fs, json, libc, mem, proc, reflect, test, yaml;

/* Attributes (v0.14.0): @json and @yaml on a struct, @key("..."), @skip
 * and @omitEmpty on its fields. reflect shows them (Type.json/.yaml,
 * Field.key/.skip/.omitEmpty), and every misplaced or unknown one is a
 * compile error at the attribute. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

@json @yaml
struct User {
    u8[] name;
    @key("e-mail") u8[] email;
    @skip i64 hits;
    @omitEmpty i64 age;
    @skip struct { i64 a; } inner;
};

@json
struct Box<T> {
    T value;
    @key("n") i64 count;
};

struct Plain { i64 x; };

#define AT_FILE "__643_attributes.ktm"
#define AT_OUT  "__643_attributes.out"

void cleanup() {
    fs.remove(AT_FILE);
    fs.remove(AT_OUT);
    fs.remove("./a.out");
}

/* compiling `source` fails (status 1) with `msg` */
bool fails(u8 *source, u8 *msg) {
    cleanup();
    if (!fs.writeAll(AT_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    i64 res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " AT_FILE " > " AT_OUT " 2>&1");
    u8 *out = fs.readAll(AT_OUT).take();
    if (!out) { cleanup(); return false; }
    bool ok = res == 1 && libc.strstr(out, msg) != null;
    if (!ok) { printf("\n    status %d\n%s\n", res, out); }
    mem.free(out);
    cleanup();
    return ok;
}

bool same(u8[] a, u8 *b) { return a.len == libc.strlen(b) && libc.memcmp(a.ptr, b, a.len) == 0; }

i32 main() {
    printf("Test - attributes: ");

    reflect.Type *u = reflect.typeOf(User);
    check(u.json && u.yaml, "@json @yaml on the struct");
    check(same(u.fields[0].key, "name"), "a field's key is its name");
    check(same(u.fields[1].key, "e-mail"), "@key renames it");
    check(u.fields[2].skip && !u.fields[1].skip, "@skip");
    check(u.fields[3].omitEmpty && !u.fields[2].omitEmpty, "@omitEmpty");
    check(!reflect.typeOf(Plain).json && !reflect.typeOf(Plain).yaml, "no attributes");
    reflect.Type *b = reflect.typeOf(Box<i64>);
    check(b.json && !b.yaml && same(b.fields[1].key, "n"), "a generic struct's instance has them");
    User x;
    x.hits = 7;
    check(x.hits == 7, "the struct works as before");

    check(fails("@jsn struct U { i64 a; };\ni32 main() { return 0; }\n",
                "unknown attribute `@jsn`"), "unknown attribute");
    check(fails("@key(\"x\") struct U { i64 a; };\ni32 main() { return 0; }\n",
                "`@key` goes on a field, not a struct"), "a field's attribute on a struct");
    check(fails("@json struct U { @json i64 a; };\ni32 main() { return 0; }\n",
                "`@json` goes on a struct, not a field"), "a struct's attribute on a field");
    check(fails("struct U { @omitEmpty i64 a; };\ni32 main() { return 0; }\n",
                "which has neither @json nor @yaml"), "@omitEmpty needs @json or @yaml");
    check(fails("@json i32 main() { return 0; }\n",
                "`@json` goes on a struct"), "on a function");
    check(fails("@json union V { i64 a; };\ni32 main() { return 0; }\n",
                "`@json` goes on a struct"), "on a union");
    check(fails("@json struct U;\ni32 main() { return 0; }\n",
                "goes on the struct's definition"), "on a forward declaration");
    check(fails("@json struct U { @key i64 a; };\ni32 main() { return 0; }\n",
                "takes the field's key as a string"), "@key without its key");
    check(fails("@json struct U { @skip(\"x\") i64 a; };\ni32 main() { return 0; }\n",
                "`@skip` takes no arguments"), "an argument where none goes");
    check(fails("@json @json struct U { i64 a; };\ni32 main() { return 0; }\n",
                "`@json` is here twice"), "twice");
    check(fails("@json struct U { @skip @key(\"x\") i64 a; };\ni32 main() { return 0; }\n",
                "a `@skip` field is never written or read"), "@skip with another");
    check(fails("@json struct U { @key(\"x\") i64 a, b; };\ni32 main() { return 0; }\n",
                "`@key` names one field"), "@key on two fields");
    check(fails("pub @json struct U { i64 a; };\ni32 main() { return 0; }\n",
                "a struct's attributes come first"), "after pub");
    check(fails("i32 main() { @json i64 x = 1; return 0; }\n",
                "not inside a function"), "inside a function");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
