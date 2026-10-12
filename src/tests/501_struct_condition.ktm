import cstr, fs, libc, mem, proc, test;

/* A struct used as a truth value (`if (err)` with a String err, `while`,
 * `for`, `?:`, `!`, `&&`, `||`) compiled and was tested by its address,
 * so it was always true: `String err = sql.execRaw(...); if (err)` took
 * the error branch every time. It is an error now; for a String or a
 * slice it says to test .len or .ptr. */

#define SRC_FILE "__501_src.ktm"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

u8 *ktmOut(u8 *body) {
    u8 *src = cstr.format("import str;\nstruct P { i64 x; };\nvoid main()\n{\n"
                          "  String s;\n  u8[] v;\n  P p;\n%s\n}\n", body);
    fs.writeAll(SRC_FILE, cstr.view(src));
    mem.free(src);
    u8 *out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "-S -o /dev/null " SRC_FILE " 2>&1; echo \"exit=$?\"").take();
    fs.remove(SRC_FILE);
    return out;
}

void errors(u8 *body, u8 *want, u8 *what) {
    u8 *out = ktmOut(body);
    bool ok = out && libc.strstr(out, want) != null;
    check(ok, what);
    if (!ok) { printf("  got: %s\n", out); }
    mem.free(out);
}

i32 main() {
    printf("Test - a struct as a condition: ");
    u8 *str = "error: a String can't be a condition: test its .len (or .ptr)";
    errors("  if (s) {}", str, "if");
    errors("  while (s) { break; }", str, "while");
    errors("  do { } while (s);", str, "do while");
    errors("  for (; s;) { break; }", str, "for");
    errors("  bool b = !s;", str, "!");
    errors("  bool b = true && s;", str, "&&");
    errors("  bool b = s || false;", str, "||");
    errors("  i64 n = v ? 1 : 2;", "error: a u8[] can't be a condition: test its .len (or .ptr)", "?: of a slice");
    errors("  if (p) {}", "error: a P can't be a condition", "another struct");
    u8 *out = ktmOut("  if (s.len && s.ptr) {}\n  if (!v.len) {}\n  i64 n = p.x ? 1 : 2;");
    check(out && libc.strstr(out, "exit=0") && !libc.strstr(out, "error"), "fields are fine");
    mem.free(out);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
