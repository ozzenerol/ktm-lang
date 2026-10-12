import cstr, fs, libc, mem, proc, str, test;

/* `ad` (autodefer, v0.8.0): `ad T name = value;` frees name at the end of
 * its scope, as if `defer name.free();` followed: a String, any slice, a
 * struct with a free() method, or a pointer to one. Every way out of the
 * scope frees it (falling off, return, break, a throw). Returning an `ad`
 * variable, a type with no free(), and a struct without a value are
 * errors. `ad` stays an ordinary name elsewhere. */

#define AD_SRC "./ktm-580-ad.ktm"
#define AD_OUT "./ktm-580-ad.out"

struct Res { i64 id; };
i64 gFreed = 0;
void Res.free() { gFreed += self.id; }

struct Child : Res { i64 extra; };

Res *made(i64 id) {
    Res *r = mem.alloc(sizeof(Res));
    r.id = id;
    return r;
}

struct Owned { i64 id; };
void Owned.free() { gFreed += self.id; mem.free(self); }

i64 early(i64 n) {
    ad Res a = {1};
    if (n > 0) {
        ad Res b = {100};
        return n;
    }
    return 0;
}

void loopBreak() {
    for (i64 i = 0; i < 5; i++) {
        ad Res r = {10};
        if (i == 2) { break; }
    }
}

u8 *firstError(u8 *source) {
    proc.shell("rm -f " AD_SRC " " AD_OUT);
    fs.writeAll(AD_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-S " AD_SRC " -o /dev/null > " AD_OUT " 2>&1");
    u8 *out = fs.readAll(AD_OUT).take();
    proc.shell("rm -f " AD_SRC " " AD_OUT);
    return out;
}

bool reports(u8 *source, u8 *want) {
    u8 *out = firstError(source);
    u8 *first = out ? libc.strstr(out, "error:") : null;
    bool ok = first && libc.strstr(first, want) == first + 7;
    if (!ok) { printf("\n%s\n----\n%s\n", source, out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - autodefer: ");
    i64 correct = 0, tests = 0;

    gFreed = 0;
    tests++; if (early(1) == 1 && gFreed == 101) { correct++; }
    gFreed = 0;
    tests++; if (early(0) == 0 && gFreed == 1) { correct++; }
    gFreed = 0;
    loopBreak();
    tests++; if (gFreed == 30) { correct++; }

    gFreed = 0;
    try {
        ad Res t = {7};
        throw(1, "out");
    } catch (Error e) { }
    tests++; if (gFreed == 7) { correct++; }

    gFreed = 0;
    Res *raw = made(20);
    {
        ad Child c = {5, 0};          /* Res.free through a derived type */
        ad Res *p = raw;              /* a pointer: p.free() */
    }
    mem.free(raw);
    tests++; if (gFreed == 25) { correct++; }

    gFreed = 0;
    {
        Owned *o = mem.alloc(sizeof(Owned));
        o.id = 3;
        ad Owned *own = o;
    }
    tests++; if (gFreed == 3) { correct++; }

    {
        ad String name = str.from("Lorenzo");
        ad String empty;
        ad u8[][] parts = str.split("a,b,c", ",");
        ad auto copy = name.clone();
        tests++; if (parts.len == 3 && empty.len == 0 && copy.len == 7) { correct++; }
    }

    i64 ad = 4;
    ad += 1;
    tests++; if (ad == 5) { correct++; }

    tests++;
    if (reports("import str;\nString f() { ad String s = str.from(\"x\"); return s; }\n"
                "i32 main() { return 0; }\n",
                "`s` is declared with `ad`, so it is freed when this function returns")) { correct++; }
    tests++;
    if (reports("i32 main() { ad i64 n = 5; return 0; }\n",
                "`ad` frees its variable with `free()`, and `i64` has no `free()`")) { correct++; }
    tests++;
    if (reports("struct R { i64 a; };\nvoid R.free() { }\ni32 main() { ad R r; return 0; }\n",
                "`ad r` needs a value to free later: write `ad R r = ...;`")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
