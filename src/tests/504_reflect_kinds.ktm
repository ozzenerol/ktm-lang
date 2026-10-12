import reflect, test;

/* v0.4.0 R1: reflect.typeOf gives the compiler's description of a type
 * (a type, or an expression's): its kind, name (as typeof prints it),
 * size, signedness, element and length. Every kind. */

enum Color { red, green, blue };
struct P { i64 x; };
union U { i64 n; f64 f; };

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool is(reflect.Type *t, reflect.Kind k, u8[] name, i64 size) {
    bool ok = t && t.kind == k && t.name.equals(name) && t.size == size;
    if (!ok && t) { printf("\n  got %s kind %d size %d", t.name, t.kind, t.size); }
    return ok;
}

i64 twice(i64 n) { return n * 2; }

i32 main() {
    printf("Test - reflect.typeOf kinds: ");
    check(is(reflect.typeOf(i64), reflect.Kind.int, "i64", 8) && reflect.typeOf(i64).isSigned, "i64");
    check(is(reflect.typeOf(u8), reflect.Kind.int, "u8", 1) && !reflect.typeOf(u8).isSigned, "u8");
    check(is(reflect.typeOf(i32), reflect.Kind.int, "i32", 4), "i32");
    check(is(reflect.typeOf(f64), reflect.Kind.float, "f64", 8), "f64");
    check(is(reflect.typeOf(f32), reflect.Kind.float, "f32", 4), "f32");
    check(is(reflect.typeOf(bool), reflect.Kind.boolean, "bool", 1), "bool");
    check(is(reflect.typeOf(Color), reflect.Kind.enumType, "Color", reflect.typeOf(Color).size), "enum");
    check(is(reflect.typeOf(String), reflect.Kind.string, "String", 24), "String");
    check(is(reflect.typeOf(P), reflect.Kind.structType, "P", 8), "struct");
    check(is(reflect.typeOf(U), reflect.Kind.unionType, "U", 8), "union");

    reflect.Type *p = reflect.typeOf(P *);
    check(is(p, reflect.Kind.pointer, "P *", 8) && p.elem == reflect.typeOf(P), "pointer");
    reflect.Type *a = reflect.typeOf(i32[4]);
    check(is(a, reflect.Kind.array, "i32[4]", 16) && a.len == 4 && a.elem.size == 4, "array");
    reflect.Type *s = reflect.typeOf(u8[]);
    check(is(s, reflect.Kind.slice, "u8[]", 16) && s.elem == reflect.typeOf(u8), "slice");
    check(reflect.typeOf(fn(i64) i64).kind == reflect.Kind.fnType, "fn type");
    check(reflect.typeOf(void *).elem.kind == reflect.Kind.none, "void");

    /* an expression's type, and the same description each time */
    i64 n = 3;
    P pt;
    check(reflect.typeOf(n) == reflect.typeOf(i64) && reflect.typeOf(n + 1).kind == reflect.Kind.int,
          "typeOf an expression");
    check(reflect.typeOf(pt) == reflect.typeOf(P) && reflect.typeOf(&pt) == p, "a variable, its address");
    check(reflect.typeOf("abc").kind == reflect.Kind.array, "a literal is an array");
    check(reflect.typeOf(&twice).kind == reflect.Kind.pointer ||
          reflect.typeOf(&twice).kind == reflect.Kind.fnType, "a function's address");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
