import cstr, fs, libc, mem, proc, reflect, test;

/* v0.4.0 R1: reflect.of(x) is x's address and type; reflect.field finds
 * a field of a struct value by name; getInt/setInt and getFloat/setFloat
 * read and write the scalars, any size, sign-extended as the type says.
 * reflect.of of something without an address, and reflect without
 * `import reflect`, are errors. */

#define SRC_FILE "__506_src.ktm"

enum Level { low, high = 9 };
struct Inner { i8 small; u16 mid; f32 ratio; };
struct Outer { i64 id; Inner in; bool on; Level lv; f64 w; };

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

u8 *ktmOut(u8 *src) {
    fs.writeAll(SRC_FILE, cstr.view(src));
    u8 *out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "-S -o /dev/null " SRC_FILE " 2>&1").take();
    fs.remove(SRC_FILE);
    return out;
}

i32 main() {
    printf("Test - reflect values: ");
    Outer o;
    mem.set(&o, 0, sizeof(Outer));
    o.id = 7;
    o.in.small = -3;
    o.in.mid = 65000;
    o.in.ratio = 1.5;
    o.lv = Level.high;
    reflect.Value v = reflect.of(o);
    check(v.ptr == &o && v.type == reflect.typeOf(Outer), "of: address and type");
    check(reflect.getInt(reflect.field(v, "id")) == 7, "an i64 field");
    reflect.Value in = reflect.field(v, "in");
    check(in.ptr == &o.in && reflect.getInt(reflect.field(in, "small")) == -3, "nested, sign-extended");
    check(reflect.getInt(reflect.field(in, "mid")) == 65000, "u16 not sign-extended");
    check(reflect.getFloat(reflect.field(in, "ratio")) == 1.5, "an f32 read as f64");
    check(reflect.getInt(reflect.field(v, "lv")) == 9, "an enum");
    check(!reflect.field(v, "missing").type && !reflect.field(reflect.field(v, "id"), "x").type,
          "no such field");

    check(reflect.setInt(reflect.field(v, "id"), 42) && o.id == 42, "setInt");
    check(reflect.setInt(reflect.field(in, "small"), 300) && o.in.small == 44, "setInt truncates");
    check(reflect.setInt(reflect.field(v, "on"), 5) && o.on == true, "a bool gets 1");
    check(reflect.setFloat(reflect.field(v, "w"), 2.25) && o.w == 2.25, "setFloat");
    check(!reflect.setFloat(reflect.field(v, "id"), 1.0) && !reflect.setInt(reflect.field(v, "w"), 1),
          "the wrong kind");
    i64 arr[3] = {1, 2, 3};
    check(reflect.getInt(reflect.of(arr[2])) == 3, "an element");

    u8 *out = ktmOut("import reflect;\nvoid main() { reflect.Value v = reflect.of(5); }\n");
    check(out && libc.strstr(out, "error: reflect.of takes a variable"), "of a value");
    mem.free(out);
    out = ktmOut("void main() { i64 n = 0; void *t = reflect.typeOf(n); }\n");
    check(out && libc.strstr(out, "error:") != null, "without import reflect");
    mem.free(out);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
