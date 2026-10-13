import cstr, libc, str, test, vec;

/* A String (or slice) field read straight off a call's result,
 * `mk().name` or `v.get(0).name`, panicked the compiler ("Slice
 * AST_CLASS_REF assign: unsupported cls kind AST_FUNCALL"). Copying the
 * field needs its address; the call now writes its result into a buffer
 * whose address is handed on, not the call's own result value (tagged as
 * a by-value struct, which made memcpy get the struct, not a pointer).
 * Passing such a field on by value, `slen((a = b).name)`, crashed too:
 * the argument tagged `a`'s address, which the copy also used. */

struct It { String name; i64 k; };
struct Late { i64 a; i64 b; String name; };
struct Small { u8[] b; };
struct Pair { i64 x; i64 y; };
struct Box { Pair p; };

It mk(u8 *s) { It i; i.name = str.from(cstr.view(s)); i.k = 3; return i; }
Late late() { Late l; l.a = 1; l.b = 2; l.name = str.from(cstr.view("late")); return l; }
Small small() { Small s; s.b = cstr.view("small"); return s; }
Box box() { Box b; b.p.x = 7; b.p.y = 9; return b; }
i64 slen(String s) { return s.len; }
i64 blen(u8[] b) { return b.len; }
i64 py(Pair p) { return p.y; }

bool same(String s, u8 *want) {
    return s.len == libc.strlen(want) && !libc.memcmp(s.ptr, want, s.len);
}

i32 main() {
    printf("Test - call result string field: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (same(mk("a").name, "a")) { correct++; }
    tests++;
    String s = mk("bb").name;
    if (same(s, "bb") && s.len == 2) { correct++; }
    tests++;
    if (same(late().name, "late") && late().name.len == 4) { correct++; }
    tests++;
    if (small().b.len == 5 && !libc.memcmp(small().b.ptr, "small", 5)) { correct++; }
    tests++;
    if (slen(mk("cccc").name) == 4) { correct++; }
    /* returned in registers, the field passed on by value */
    tests++;
    if (blen(small().b) == 5 && py(box().p) == 9) { correct++; }

    vec.Vec<It> v = vec.new<It>();
    v.push(mk("ddd"));
    tests++;
    if (same(v.get(0).name, "ddd") && v.get(0).k == 3) { correct++; }

    It a;
    It b = mk("eeeee");
    tests++;
    if (same((a = b).name, "eeeee") && same(a.name, "eeeee")) { correct++; }

    i64 n = 0;
    for (i64 i = 0; i < 3; i++) {
        String t = mk("loop").name;
        if (same(t, "loop")) { n++; }
    }
    tests++;
    if (n == 3) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
