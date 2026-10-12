import reflect, str, test;

/* v0.4.0 R1: a struct's fields from reflect.typeOf: names, types and
 * offsets in declaration order (an inherited struct's first), unions,
 * nested and self-referencing types, and private fields marked (in the
 * module that has them). */

struct Base { i64 a; i32 b; };
struct Derived : Base { u8 c; f64 d; };
struct Node { u8[] label; Node *next; Node *kids[2]; String note; };
struct Holder { union { i64 n; f64 x; }; i64 after; };

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* "name@offset:type ..." of t's fields */
String fieldsOf(reflect.Type *t) {
    String out = str.new();
    for (auto f : t.fields) {
        out.appendFormat("%s%s@%d:%s ", f.isPrivate ? "-" : "", f.name, f.offset, f.type.name);
    }
    return out;
}

bool fieldsAre(reflect.Type *t, u8[] want) {
    String got = fieldsOf(t);
    bool ok = got.equals(want);
    if (!ok) { printf("\n  got %s", got); }
    got.free();
    return ok;
}

i32 main() {
    printf("Test - reflect fields: ");
    check(fieldsAre(reflect.typeOf(Base), "a@0:i64 b@8:i32 "), "a plain struct");
    check(fieldsAre(reflect.typeOf(Derived), "a@0:i64 b@8:i32 c@16:u8 d@24:f64 "),
          "inherited fields come first");
    check(reflect.typeOf(Derived).size == sizeof(Derived), "size");
    check(fieldsAre(reflect.typeOf(Node), "label@0:u8[] next@16:Node * kids@24:Node *[2] note@40:String "),
          "a self-referencing struct");
    reflect.Type *node = reflect.typeOf(Node);
    check(node.fields[1].type.elem == node && node.fields[2].type.elem.elem == node &&
          node.fields[2].type.len == 2, "next and kids lead back to Node");
    check(fieldsAre(reflect.typeOf(Holder), "n@0:i64 x@0:f64 after@8:i64 "),
          "an anonymous union's members, flattened");
    check(reflect.typeOf(i64).fields.len == 0 && reflect.typeOf(i64).members.len == 0,
          "a scalar has no fields");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
