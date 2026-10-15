import cstr, fs, libc, mem, proc, test;

/* v0.23.2: a method defined through a type alias of a struct or union,
 * `type P = Point; i64 P.sum() {...}`, is a method of Point (an alias
 * is the type itself). It said "`P` is not a struct". An alias of a
 * type that is no struct is still an error. */

#define MA_SRC "./ktm-890-alias.ktm"
#define MA_OUT "./ktm-890-alias.out"

struct Point { i64 x; i64 y; };
union Num { i64 i; f64 f; };
type P = Point;
type N = Num;

i64 P.sum() { return self.x + self.y; }
i64 P.plus<T>(T t) { return self.x + t; }
i64 N.twice() { return self.i * 2; }

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

/* `source` compiled fails with `want` */
bool fails(u8 *source, u8 *want) {
    fs.writeAll(MA_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-S " MA_SRC " -o /dev/null > " MA_OUT " 2>&1");
    u8 *out = fs.readAll(MA_OUT).take();
    bool ok = out && libc.strstr(out, "error:") && libc.strstr(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", source, out); }
    mem.free(out);
    fs.remove(MA_SRC);
    fs.remove(MA_OUT);
    return ok;
}

i32 main() {
    printf("Test - method on a type alias: ");
    P p = {3, 4};
    Point q = {1, 2};
    check(p.sum() == 7, "through the alias");
    check(q.sum() == 3, "through the struct's own name");
    check(q.plus<i64>(9) == 10 && p.plus(1) == 4, "a method with type parameters");
    Num n;
    n.i = 21;
    check(n.twice() == 42, "a union's");
    check(fails("type I = i64;\nvoid I.f() {}\nvoid main() {}\n",
                "`I` is not a struct: only a struct or union can have methods"),
          "an alias of i64");
    check(fails("struct S { i64 x; };\ntype SP = S *;\nvoid SP.f() {}\nvoid main() {}\n",
                "`SP` is not a struct"), "an alias of a pointer");
    check(fails("import vec;\ntype BV = vec.BitVec;\nvoid BV.count() {}\nvoid main() {}\n",
                "methods of `vec.BitVec` can only be defined in the module that defines it"),
          "an alias of a standard library struct");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
