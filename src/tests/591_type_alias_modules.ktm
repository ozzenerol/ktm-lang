import cstr, fs, libc, proc, str, test;

/* `type Name = T;` in modules and the errors (v0.9.0): a module's aliases
 * resolve in any order, `pub` ones are `mod.Name` outside, the others
 * private; redefinitions, a cycle, a self reference and an alias inside a
 * function are errors with their own messages. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

u8[] DIR = "__591_alias";

void put(u8 *rel, u8 *text) {
    String path = str.format("%s/%s", DIR, rel);
    fs.writeAll(path, cstr.view(text));
    path.free();
}

/* ktm on DIR/main.ktm, run it if it built: 0 and the program's output
 * in *out, or ktm's status and its messages */
i64 build(u8 *main, String *out) {
    put("main.ktm", main);
    i64 res = proc.shell("cd __591_alias && ../../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                         "main.ktm -o main.bin > out.txt 2>&1 && ./main.bin > out.txt 2>&1");
    String path = str.format("%s/out.txt", DIR);
    *out = fs.readAll(path);
    path.free();
    return res;
}

bool fails(u8 *main, u8 *want) {
    String out;
    bool ok = build(main, &out) != 0 && out.ptr && libc.strstr(out.ptr, want) != null;
    if (!ok && out.ptr) { printf("\n%s", out); }
    out.free();
    return ok;
}

i32 main() {
    printf("Test - type aliases in modules, errors: ");

    fs.remove(DIR, fs.REMOVE_RECURSE | fs.REMOVE_FORCE);
    fs.makeDir("__591_alias/geo",, true);
    fs.makeDir("__591_alias/cyc");
    put("geo/geo.ktm",
        "module geo;\n"
        "pub type Coord = Meters;      /* the alias it uses comes below */\n"
        "pub type Meters = f64;\n"
        "type Secret = i64;\n"
        "pub struct Spot { Coord x, y; };\n"
        "pub type Spots = Spot[];\n"
        "pub Coord dist2(Spot *a, Spot *b) {\n"
        "    Secret unused = 0;\n"
        "    Coord dx = a.x - b.x, dy = a.y - b.y;\n"
        "    return dx * dx + dy * dy;\n"
        "}\n");
    put("cyc/cyc.ktm", "module cyc;\npub type A = B;\npub type B = A;\n");

    String out;
    i64 res = build("import geo;\n"
                    "i32 main() {\n"
                    "    geo.Coord c = 1.5;\n"
                    "    geo.Spot s[2] = {{0, 0}, {3, 4}};\n"
                    "    geo.Spots all = s;\n"
                    "    printf(\"%.1f %.1f %d\\n\", c, geo.dist2(&all[0], &all[1]), all.len);\n"
                    "    return 0;\n"
                    "}\n", &out);
    check(res == 0 && out.ptr && str.equals(out, "1.5 25.0 2\n"), "pub aliases from a module, in any order");
    if (res != 0 && out.ptr) { printf("\n%s", out); }
    out.free();

    check(fails("import geo;\ni32 main() { geo.Secret x = 1; return 0; }\n",
                "`Secret` is private to module geo"), "a module's alias without pub is private");
    check(fails("import cyc;\ni32 main() { cyc.A a; return 0; }\n",
                "type aliases `A` and `B` refer to each other (a cycle)"), "a cycle in a module");
    check(fails("type T = T;\ni32 main() { return 0; }\n", "type alias `T` refers to itself"),
          "a self reference");
    check(fails("type A = i64;\ntype A = u8;\ni32 main() { return 0; }\n",
                "type `A` has already been defined"), "an alias defined twice");
    check(fails("struct S { i64 a; };\ntype S = i64;\ni32 main() { return 0; }\n",
                "`S` is already a type"), "an alias named like a struct");
    check(fails("type A = B;\ntype B = i64;\ni32 main() { return 0; }\n",
                "Type `B` has not been declared"), "in a program, an alias names a type declared before it");
    check(fails("i32 main() {\n    type X = i64;\n    return 0;\n}\n",
                "a type alias goes at file scope, not inside a function"), "an alias inside a function");
    check(fails("type T = u8[];\ni32 main() { T t = 5; return 0; }\n", "`u8[] = i64` is invalid"),
          "a type error names the real type");

    fs.remove(DIR, fs.REMOVE_RECURSE);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
