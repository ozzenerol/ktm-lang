import cstr, fs, libc, mem, proc, test;

/* Methods with type parameters of their own (v0.21.0): `V S.f<V>(...)`
 * on a plain struct and `U Pair<T>.fold<U>(...)` on a generic one. The
 * own parameters are inferred from the arguments (self takes no part,
 * the receiver gives the struct's) or written, `s.f<i64>(x)`; one only
 * in the return type must be written. Called on a value, a pointer, a
 * call's result (chained) and a derived struct; from a generic function;
 * with a @caller parameter; from another module (the fixture
 * src/tests/modules/genmeth: pub and private, calling a static helper),
 * also built with -lib, AOT and JIT. And the errors. */

#define G_OUT "./ktm-823-gm.out"
#define G_BIN "./ktm-823-gm.bin"
#define G_DIR "./g823"
#define G_FILE "./__823_gm.ktm"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s", what); }
}

struct Box { i64 n; };
i64 Box.add<V>(V x) { return self.n + (i64)x; }
V Box.pick<V>(V a, V b) { return self.n > 0 ? a : b; }
V Box.zero<V>() { V z; return z; }
i64 Box.both<A, B>(A a, B b) { return (i64)a * 10 + (i64)b; }
i64 gLine = 0;
i64 Box.at<V>(@caller("line") i64 line, V x) { gLine = line; return (i64)x; }

struct Base { i64 k; };
i64 Base.mul<V>(V x) { return self.k * (i64)x; }
struct Derived : Base { i64 extra; };

struct Pair<T> { T a; T b; };
U Pair<T>.fold<U>(U init, fn(U, T) U f) { return f(f(init, self.a), self.b); }
Pair<U> Pair<T>.map<U>(fn(T) U f) { Pair<U> p = {f(self.a), f(self.b)}; return p; }

f64 addHalf(f64 acc, i64 x) { return acc + (f64)x * 0.5; }
f64 toF(i64 x) { return (f64)x + 0.25; }
i64 toI(f64 x) { return (i64)(x * 4.0); }

T viaGeneric<T>(Box *b, T x) { return b.pick<T>(x, x); }

bool same(u8 *a, u8 *b) { return !libc.strcmp(a, b); }

bool output(u8 *cmd, u8 *want) {
    proc.shell("rm -f " G_OUT " " G_BIN);
    proc.shell(cstr.view(cmd));
    u8 *out = fs.readAll(G_OUT).take();
    bool ok = out && !cstr.cmp(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    proc.shell("rm -f " G_OUT " " G_BIN);
    return ok;
}

bool fails(u8 *file, u8 *source, u8 *want) {
    fs.writeAll(cstr.view(file), cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    u8 *cmd = cstr.format(KTM "-S %s -o /dev/null > " G_OUT " 2>&1", file);
    proc.shell(cstr.view(cmd));
    mem.free(cmd);
    u8 *out = fs.readAll(G_OUT).take();
    bool ok = out && libc.strstr(out, "error:") && libc.strstr(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", source, out); }
    mem.free(out);
    fs.remove(cstr.view(file));
    fs.remove(G_OUT);
    return ok;
}

u8 *gWant = "0.5 1.5 small big 103\n3.5 10\n";

i32 main()
{
    printf("Test - generic methods: ");
    Box b = {10};
    check(b.add(5) == 15, "inferred from an argument");
    check(b.add<u8>(259) == 13, "written: the argument converted to u8");
    check(same(b.pick("yes", "no"), "yes") && b.pick(1.5, 2.5) == 1.5, "two instances");
    check(b.zero<i64>() == 0, "only in the return type: written");
    check(b.both(3, 4) == 34 && b.both<u8, i64>(1, 2) == 12, "two own parameters");
    Box *p = &b;
    check(p.add(1) == 11, "on a pointer");
    check(b.at(4) == 4 && gLine == 88, "a @caller parameter");
    Derived d;
    d.k = 6;
    check(d.mul(7) == 42, "a base struct's");
    check(viaGeneric(&b, 9) == 9, "from a generic function, written with its T");
    Pair<i64> q = {3, 4};
    check(q.fold(1.0, &addHalf) == 4.5, "a generic struct's: U from the arguments, T from the receiver");
    check(q.map(&toF).map(&toI).b == 17, "chained on a call's result");

    check(output(KTM "${KTM_TEST_SRC:-.}/modules/genmeth/main.ktm -o " G_BIN " > " G_OUT " 2>&1 && "
                 G_BIN " >> " G_OUT " 2>&1", gWant), "a module's, AOT");
    check(output(KTM "-jit ${KTM_TEST_SRC:-.}/modules/genmeth/main.ktm > " G_OUT " 2>&1", gWant),
          "a module's, JIT");
    proc.shell("rm -rf " G_DIR " && mkdir -p " G_DIR " && cp -r ${KTM_TEST_SRC:-.}/modules/genmeth/. " G_DIR "/");
    check(output(KTM "-lib bag " G_DIR "/bag > " G_OUT " 2>&1; " KTM G_DIR "/main.ktm -o " G_BIN
                 " > " G_OUT " 2>&1 && " G_BIN " >> " G_OUT " 2>&1", gWant), "a -lib module's, AOT");
    check(output(KTM "-jit " G_DIR "/main.ktm > " G_OUT " 2>&1", gWant), "a -lib module's, JIT");
    check(fails(G_DIR "/p.ktm", "import bag;\ni32 main() { bag.Bag b; b.secret(1); return 0; }\n",
                "the method `secret` of `bag.Bag` is private to module bag"), "private");
    proc.shell("rm -rf " G_DIR);

    check(fails(G_FILE, "struct S { i64 n; };\nV S.z<V>() { V v; return v; }\nvoid main() { S s; s.z(); }\n",
                "can't work out the type arguments of `S.z`: nothing says what `V` is"), "can't infer");
    check(fails(G_FILE, "struct S { i64 n; };\nvoid S.f<V>(V x) { }\nvoid main() { S s; s.f<i64, u8>(1); }\n",
                "`S.f` takes 1 type argument, got 2"), "type argument count");
    check(fails(G_FILE, "struct S { i64 n; };\nvoid S.f<V>(V x) { }\nvoid S.f(i64 x) { }\nvoid main() { }\n",
                "`S.f` is already a method of S"), "a plain method of the name after");
    check(fails(G_FILE, "struct S { i64 n; };\nvoid S.f(i64 x) { }\nvoid S.f<V>(V x) { }\nvoid main() { }\n",
                "`S.f` is already a method of S"), "a plain method of the name before");
    check(fails(G_FILE, "struct S { i64 n; };\nvoid S.f<V>(V x) { }\nvoid S.f<V>(V x, V y) { }\nvoid main() { }\n",
                "`S.f` is already a method of S"), "two generic methods of the name");
    check(fails(G_FILE, "struct S { i64 f; };\nvoid S.f<V>(V x) { }\nvoid main() { }\n",
                "`S` has a field named `f`"), "a field of the name");
    check(fails(G_FILE, "struct S { i64 n; };\nvoid S.f<V>(V x) { }\nvoid main() { S s; auto p = &s.f; }\n",
                "`S.f` is a generic method: call it with `(...)`; it has no address"), "no address");
    check(fails(G_FILE, "struct P<T> { T a; };\nvoid P<T>.f<T>(T x) { }\nvoid main() { }\n",
                "`T` is already a type parameter of `P`"), "a parameter name of the struct's");
    check(fails(G_FILE, "struct P<T> { T a; };\nvoid P.f<V>(V x) { }\nvoid main() { }\n",
                "`P` is generic: write its parameters"), "a generic struct without its parameters");
    check(fails(G_FILE, "void Q.f<V>(V x) { }\nvoid main() { }\n",
                "`Q` is not a struct: only a struct can have methods"), "not a struct");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
