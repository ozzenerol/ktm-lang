import cstr, fs, libc, mem, proc, test;

/* Calling a plain field as a method, `s.len()` with `len` a field of a
 * generic set.Set, segfaulted the compiler (exit 139, no message): it
 * lowered the field as a function pointer call. It is now an error that
 * says it is a field. A function pointer field is still called. */

#define M_SRC "./ktm-641-field-call.ktm"
#define M_OUT "./ktm-641-field-call.out"

/* `source` compiled with -S fails with exactly one error, `want` */
bool fails(u8 *source, u8 *want) {
    proc.shell("rm -f " M_SRC " " M_OUT);
    fs.writeAll(M_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-S " M_SRC " -o /dev/null > " M_OUT " 2>&1");
    u8 *out = fs.readAll(M_OUT).take();
    proc.shell("rm -f " M_SRC " " M_OUT);
    i64 errors = 0;
    u8 *p = out;
    while (p && (p = libc.strstr(p, "error:"))) {
        errors++;
        p++;
    }
    bool ok = out && errors == 1 && libc.strstr(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", source, out); }
    mem.free(out);
    return ok;
}

#define P "struct P { i64 len; i64 *ptr; };\n"

struct Cb { i64 n; fn(i64) i64 f; };
i64 twice(i64 x) { return x * 2; }

i32 main() {
    printf("Test - field called as a method: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (fails("import set;\nvoid main() { set.Set<u8[]> s = set.new<u8[]>(8); printf(\"%d\\n\", s.len()); }\n",
              "`len` is a field, not a method: read it without `()`")) { correct++; }
    tests++;
    if (fails(P "void main() { P p; printf(\"%d\\n\", p.len()); }\n",
              "`len` is a field, not a method")) { correct++; }
    tests++;
    if (fails(P "void main() { P p; P *pp = &p; printf(\"%d\\n\", pp->len()); }\n",
              "`len` is a field, not a method")) { correct++; }
    tests++;
    if (fails(P "P mk() { P p; return p; }\nvoid main() { printf(\"%d\\n\", mk().len()); }\n",
              "`len` is a field, not a method")) { correct++; }
    tests++;
    if (fails(P "void main() { P p; p.ptr(2); }\n",
              "`ptr` is a field, not a method")) { correct++; }

    /* a function pointer field is called as before */
    Cb c;
    c.n = 4;
    c.f = &twice;
    tests++;
    if (c.f(c.n) == 8) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
