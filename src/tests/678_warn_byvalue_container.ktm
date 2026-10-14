import cstr, fs, libc, proc, test;

/* A warning when a function changes a Vec, List, Map, Tree, Set or String
 * it got by value (v0.18.0): `void f(vec.Vec<String> v) { v.removeAt(0); }`
 * changes a copy of the caller's header, so the caller keeps the old
 * length and frees what was removed twice. Not for a pointer parameter, a
 * local, or a method that changes items in place (set). */

#define SRC_FILE "__678_src.ktm"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what, u8 *out) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s: [%s]\n", what, out); }
}

u8 *compile(u8 *src) {
    fs.writeAll(SRC_FILE, cstr.view(src));
    return proc.shellOutput(KTM "-S -o /dev/null " SRC_FILE " 2>&1").take();
}

i32 main() {
    printf("Test - warn on changing a by-value container: ");

    u8 *out = compile("import str, vec;\n"
                      "void drop(vec.Vec<String> args) {\n"
                      "    args.removeAt(0);\n"
                      "}\n"
                      "void main() {}\n");
    check(out && libc.strstr(out, "warning: `args` is a copy of the caller's vec.Vec: `removeAt` changes only the copy") &&
          libc.strstr(out, "take `vec.Vec<String> *args` and pass `&args`") && libc.strstr(out, ":3:"),
          "removeAt on a by-value Vec", out);

    out = compile("import str;\nvoid add(String s) {\n    s.append(\"x\");\n}\nvoid main() {}\n");
    check(out && libc.strstr(out, "`s` is a copy of the caller's String: `append`"), "append on a by-value String", out);

    out = compile("import map;\nvoid put(map.Map<i64, i64> m) {\n    m.set(1, 2);\n}\nvoid main() {}\n");
    check(out && libc.strstr(out, "`m` is a copy of the caller's map.Map: `set`"), "set on a by-value Map", out);

    out = compile("import vec;\n"
                  "void ok(vec.Vec<i64> *p, vec.Vec<i64> v) {\n"
                  "    p.push(1);\n"
                  "    v.set(0, 2);\n"
                  "    i64 n = v.len;\n"
                  "    vec.Vec<i64> local = vec.new<i64>();\n"
                  "    local.push(n);\n"
                  "    local.free();\n"
                  "}\n"
                  "void main() {}\n");
    check(out && !libc.strstr(out, "warning"), "a pointer, set(), a local: no warning", out);
    fs.remove(SRC_FILE);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
