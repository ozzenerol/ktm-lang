import cstr, fs, libc, map, mem, proc, test, vec;

/* A type or name from a module the file doesn't import (`ad vec.Vec<i64>
 * v = ...;` or `vec.Vec<i64> v;` without `import vec`) says which import
 * is missing, and `ad` with an unknown type names that type, instead of
 * "Type `ad` has not been declared" (v0.15.1). */

#define IMPORT_TEST_FILE "__664_import_fail.ktm"
#define IMPORT_TEST_OUT  "__664_import_fail.out"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool compileFailsWith(u8 *source, u8 *expected) {
    fs.remove(IMPORT_TEST_FILE);
    fs.remove(IMPORT_TEST_OUT);
    if (!fs.writeAll(IMPORT_TEST_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    i64 res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -o __664_import_fail " IMPORT_TEST_FILE " > " IMPORT_TEST_OUT " 2>&1");
    u8 *out = fs.readAll(IMPORT_TEST_OUT).take();
    bool ok = res == 1 && out && libc.strstr(out, expected);
    if (!ok) { printf("\n  exit %d: %s", res, out ? out : ""); }
    mem.free(out);
    fs.remove(IMPORT_TEST_FILE);
    fs.remove(IMPORT_TEST_OUT);
    fs.remove("__664_import_fail");
    return ok;
}

i32 main()
{
    printf("Test - missing import errors: ");

    check(compileFailsWith("import map;\nvoid main() {\n  map.Map<i64, i64> m = map.new<i64, i64>();\n"
                           "  ad vec.Vec<i64> v = m.values();\n}\n",
                           "module `vec` is not imported: add `import vec;`"),
          "ad with a generic type of a module not imported");
    check(compileFailsWith("void main() { vec.Vec<i64> v; }\n",
                           "module `vec` is not imported: add `import vec;`"),
          "a declaration with it");
    check(compileFailsWith("void main() { i64 n = vec.len; }\n",
                           "module `vec` is not imported: add `import vec;`"),
          "an expression with it");
    check(compileFailsWith("i32 main() { return rt.started(); }\n",
                           "Variable or function `rt` has not been defined"),
          "rt, which can't be imported, gets no import hint");
    check(compileFailsWith("void main() { ad Nope x; }\n",
                           "Type `Nope` has not been declared"),
          "ad with an unknown type names the type");
    check(compileFailsWith("void main() { ad foo.Bar x; }\n",
                           "Type `foo.Bar` has not been declared"),
          "ad with a name that is no module");
    check(compileFailsWith("import map;\nvoid main() { ad map.Nope x; }\n",
                           "Type `map.Nope` has not been declared"),
          "ad with a type its module doesn't have");

    /* with the import, ad works, and ad stays usable as a name */
    i64 got = 0;
    {
        map.Map<i64, i64> m = map.new<i64, i64>();
        m.set(1, 2);
        ad vec.Vec<i64> v = m.values();
        i64 ad = v.len;
        got = ad * 10 + v.data[0];
        m.free();
    }
    check(got == 12, "ad with the import, and a local named ad");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
