import cstr, fs, libc, mem, proc, test;

/* What typed pointer parameters for void * ones (510) don't allow: only
 * a void * parameter takes a data pointer parameter, so the return type,
 * the parameter count, `...`, and integer, struct, slice and function
 * pointer parameters must still match exactly; and a pointer to a
 * function pointer must match exactly (storing through it could put a
 * function taking a User * where any pointer is passed), with a note
 * saying so. */

#define AT_SRC "./ktm-511-fnparams.ktm"
#define AT_OUT "./ktm-511-fnparams.out"

#define NOTE "only on a function pointer itself, not through a pointer to one"

i64 gCorrect = 0, gTests = 0;

u8 *diagnose(u8 *source) {
    proc.shell("rm -f " AT_SRC " " AT_OUT);
    fs.writeAll(AT_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " AT_SRC " -o /dev/null > " AT_OUT " 2>&1");
    u8 *out = fs.readAll(AT_OUT).take();
    proc.shell("rm -f " AT_SRC " " AT_OUT);
    return out;
}

i64 count(u8 *s, u8 *what) {
    i64 n = 0;
    while (s && (s = libc.strstr(s, what))) {
        n++;
        s++;
    }
    return n;
}

/* body gives exactly one error, containing want (or no error at all
 * when want is null) */
void prints(u8 *body, u8 *want, u8 *what) {
    u8 *src = cstr.format(
"struct User { i64 age; };
i64 one(User *a) { return 0; }
i64 two(User *a, User *b) { return 0; }
User *give(void *a) { return a; }
i64 num(i64 a) { return 0; }
i64 val(User a) { return 0; }
i64 sl(u8[] a) { return 0; }
i64 fp(fn(i64) i64 a) { return 0; }
i64 va(User *a, ...args) { return 0; }
void takePtr((fn(void *) i64) *pp) {}
%s
", body);
    u8 *out = diagnose(src);
    bool ok = out && count(out, "error:") == (want ? 1 : 0) && (!want || libc.strstr(out, want));
    /* the note only where it is the reason */
    ok = ok && (want && libc.strstr(want, NOTE)) == (libc.strstr(out, NOTE) != null);
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n%s\n----\n%s\n", what, body, out); }
    mem.free(out);
    mem.free(src);
}

i32 main() {
    printf("Test - typed pointer parameters: what is still an error: ");
    prints("void main() { fn(void *) i64 f = &one; fn(void *, void *) i64 g = &two; f = g; }",
           "cannot convert fn(void*, void*) i64 to fn(void*) i64", "allowed ones, then a count mismatch");
    prints("void main() { fn(void *) void * f = &give; }",
           "cannot convert fn(void*) User * to fn(void*) void *", "the return type stays exact");
    prints("void main() { fn(void *) i64 f = &two; }",
           "cannot convert fn(User*, User*) i64 to fn(void*) i64", "the parameter count");
    prints("void main() { fn(void *) i64 f = &num; }",
           "cannot convert fn(i64) i64 to fn(void*) i64", "an integer parameter");
    prints("void main() { fn(void *) i64 f = &val; }",
           "cannot convert fn(User) i64 to fn(void*) i64", "a struct parameter");
    prints("void main() { fn(void *) i64 f = &sl; }",
           "cannot convert fn(u8[]) i64 to fn(void*) i64", "a slice parameter");
    prints("void main() { fn(void *) i64 f = &fp; }",
           "cannot convert fn(fn(i64) i64) i64 to fn(void*) i64", "a function pointer parameter");
    prints("void main() { fn(void *) i64 f = &va; }",
           "cannot convert fn(User*, ...args) i64 to fn(void*) i64", "`...`");
    prints("void main() { fn(User *) i64 f = &one; (fn(void *) i64) *pp = &f; }",
           NOTE, "a pointer to a function pointer, assigned");
    prints("void main() { fn(User *) i64 f = &one; takePtr(&f); }",
           NOTE, "a pointer to a function pointer, as an argument");
    prints("(fn(void *) i64) *back((fn(User *) i64) *p) { return p; }\nvoid main() {}",
           NOTE, "a pointer to a function pointer, returned");
    prints("void main() { fn(i64) i64 f = &num; (fn(void *) i64) *pp = &f; }",
           "cannot convert (fn(i64) i64) * to (fn(void*) i64) *", "no note when it wouldn't help");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
