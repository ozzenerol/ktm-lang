import reflect, str, test;

/* @key and @skip on a struct without @json (v0.21.0): reflect sees them,
 * so ktm-pg's columns and other reflect users can rename and skip
 * fields of plain structs. @omitEmpty still needs @json or @yaml. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

struct Row {
    i64 id;
    @key("password_hash") String passwordHash;
    @skip i64 cache;
};

i32 main() {
    printf("Test - @key on any struct: ");
    reflect.Type *t = reflect.typeOf(Row);
    check(str.equals(t.fields[1].key, "password_hash") && str.equals(t.fields[0].key, "id"), "@key");
    check(t.fields[2].skip && !t.fields[1].skip, "@skip");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
