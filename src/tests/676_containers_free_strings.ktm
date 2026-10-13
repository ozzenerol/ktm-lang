import cstr, fs, libc, proc, test;

/* Containers own their Strings (v0.18.0): Vec, List, Map and Tree free
 * the Strings in them with free() and clear(); Map and Tree also on
 * delete and when set() replaces one (not when the same String is set
 * again after growing); pop/removeAt/shift hand one back; keys() and
 * values() give their own copies. Run under -Memsafe: nothing leaks and
 * nothing is freed twice. */

#define SRC_FILE "__676_src.ktm"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what, u8 *out) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s: [%s]\n", what, out); }
}

u8 *memsafe(u8 *src) {
    fs.writeAll(SRC_FILE, cstr.view(src));
    return proc.shellOutput(KTM "-Memsafe -jit " SRC_FILE " 2>&1").take();
}

bool clean(u8 *out, u8 *want) {
    return out && !libc.strstr(out, "never freed") && !libc.strstr(out, "double free") &&
           libc.strstr(out, want);
}

i32 main() {
    printf("Test - containers free their Strings: ");

    u8 *out = memsafe(
        "import str, vec;\n"
        "void main() {\n"
        "    vec.Vec<String> v = vec.new<String>();\n"
        "    v.push(str.from(\"a\")); v.push(str.from(\"b\")); v.push(str.from(\"c\"));\n"
        "    v.set(0, str.from(\"A\"));\n"
        "    String b = v.get(1);\n"
        "    b.append(\"\");\n"
        "    v.set(1, b);\n"
        "    String c = v.pop();\n"
        "    c.free();\n"
        "    v.clear();\n"
        "    v.push(str.from(\"d\"));\n"
        "    printf(\"len %d\", v.len);\n"
        "    v.free();\n"
        "}\n");
    check(clean(out, "len 1"), "Vec: set, pop, clear, free", out);

    out = memsafe(
        "import list, str;\n"
        "void main() {\n"
        "    list.List<String> l = list.new<String>();\n"
        "    l.append(str.from(\"a\")); l.append(str.from(\"b\"));\n"
        "    String a = l.shift();\n"
        "    a.free();\n"
        "    l.append(str.from(\"c\"));\n"
        "    printf(\"len %d\", l.len);\n"
        "    l.free();\n"
        "}\n");
    check(clean(out, "len 2"), "List: shift, free", out);

    out = memsafe(
        "import map, str, vec;\n"
        "void main() {\n"
        "    map.Map<u8[], String> m = map.new<u8[], String>();\n"
        "    m.set(\"x\", str.from(\"1\"));\n"
        "    m.set(\"y\", str.from(\"2\"));\n"
        "    m.set(\"z\", str.from(\"3\"));\n"
        "    m.set(\"x\", str.from(\"one\"));\n"
        "    m.delete(\"y\");\n"
        "    vec.Vec<String> vs = m.values();\n"
        "    vec.Vec<u8[]> ks = m.keys();\n"
        "    printf(\"%s %d\", vs.get(0), ks.len);\n"
        "    vs.free();\n"
        "    ks.free();\n"
        "    m.clear();\n"
        "    m.set(\"w\", str.from(\"4\"));\n"
        "    m.free();\n"
        "    map.Map<String, String> m2 = map.new<String, String>();\n"
        "    String k = str.from(\"key\");\n"
        "    m2.set(k, str.from(\"v\"));\n"
        "    k.free();\n"
        "    vec.Vec<String> k2 = m2.keys();\n"
        "    k2.free();\n"
        "    m2.free();\n"
        "}\n");
    check(clean(out, "one 2"), "Map: replace, delete, values, keys, clear, free", out);

    out = memsafe(
        "import map, str;\n"
        "void main() {\n"
        "    map.Tree<i64, String> t = map.treeNew<i64, String>();\n"
        "    t.set(1, str.from(\"a\"));\n"
        "    t.set(2, str.from(\"b\"));\n"
        "    t.set(1, str.from(\"A\"));\n"
        "    t.delete(2);\n"
        "    printf(\"len %d\", t.len);\n"
        "    t.free();\n"
        "}\n");
    check(clean(out, "len 1"), "Tree: replace, delete, free", out);
    fs.remove(SRC_FILE);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
