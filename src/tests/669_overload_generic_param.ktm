import cstr, fs, libc, proc, test, vec;

/* Overloads whose parameters are generic instances (vec.Vec<i64> *,
 * vec.Vec<String> *, one with a module type inside: vec.Vec<u8[]> *)
 * next to plain ones: each has its own symbol (they once had raw <...>
 * and the assembler failed), and a call that fits none lists them as
 * written. */

#define PROG "__669_prog.ktm"
#define OUT  "__669_prog.out"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 total(vec.Vec<i64> *v) {
    i64 t = 0;
    for (i64 i = 0; i < v.len; i++) { t += v.get(i); }
    return t;
}

i64 total(vec.Vec<u8[]> *v) {
    i64 t = 0;
    for (i64 i = 0; i < v.len; i++) { t += v.get(i).len; }
    return t;
}

i64 total(vec.Vec<String> *v) { return -v.len; }

i64 total(i64 n) { return n * 100; }

i32 main()
{
    printf("Test - overloads with generic parameters: ");
    ad vec.Vec<i64> a = vec.new<i64>();
    a.push(2);
    a.push(3);
    ad vec.Vec<u8[]> b = vec.new<u8[]>();
    b.push("abc");
    b.push("de");
    ad vec.Vec<String> c = vec.new<String>();
    check(total(&a) == 5, "Vec<i64> *");
    check(total(&b) == 5 && total(&c) == 0, "Vec<u8[]> * and Vec<String> *");
    check(total(7) == 700, "the plain one");
    fn(vec.Vec<i64> *) i64 f = &total;
    check(f(&a) == 5, "taken as a function pointer");

    u8 *src = "import vec;\n"
              "i64 g(vec.Vec<i64> *v) { return 1; }\n"
              "i64 g(i64 n) { return 2; }\n"
              "void main() { g(\"x\"); }\n";
    fs.writeAll(PROG, cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    i64 rc = proc.shellf("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} %s > %s 2>&1", PROG, OUT);
    String out = fs.readAll(OUT);
    bool listed = rc != 0 && out.ptr != null && libc.strstr(out.ptr, "`g(vec.Vec<i64> *)`") != null;
    if (!listed) { printf("\n  got [%.*s]", out.len, out.ptr); }
    check(listed, "the candidates are shown as written");
    out.free();
    fs.remove(PROG);
    fs.remove(OUT);
    fs.remove("a.out");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
