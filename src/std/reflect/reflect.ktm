module reflect;

/* What the compiler tells a program about its types (v0.4.0).
 *
 *   reflect.Type *t = reflect.typeOf(User);   // a type, or an expression's
 *   reflect.Value v = reflect.of(user);       // a value's address and type
 *
 * typeOf and of are built into the compiler: the description of a type is
 * made at compile time, only for the types a program asks about (and the
 * types they lead to), as read-only data. Compare two Types with
 * reflect.same, not ==: a library and a program each have their own. */

/* What kind of type a Type describes */
pub enum Kind {
    none,           /* void */
    int,
    float,
    boolean,
    enumType,
    pointer,
    array,
    slice,
    string,         /* String */
    structType,
    unionType,
    fnType
};

pub struct Type;
pub struct Field;
pub struct EnumMember;

/* A type's description, from typeOf(T) or typeOf(expr) */
pub struct Type {
    Kind kind;
    u8[] name;          /* as typeof prints it */
    i64 size;
    bool isSigned;      /* an int, or an enum's integer */
    Type *elem;         /* a pointer's, array's or slice's element */
    i64 len;            /* an array's length */
    Field[] fields;     /* a struct's or union's, in declaration order */
    EnumMember[] members;
};

/* A field of a struct or union */
pub struct Field {
    u8[] name;
    Type *type;
    i64 offset;
    bool isPrivate;     /* `private` in its module: json skips it */
};

/* A member of an enum: its name and value */
pub struct EnumMember {
    u8[] name;
    i64 value;
};

/* A value: where it is and what it is */
pub struct Value {
    void *ptr;
    Type *type;
};

/* Built into the compiler (these declarations are for the names only):
 * typeOf(T) or typeOf(expr) gives the description of a type, of(x) the
 * address and type of a variable, field or element */
pub Type *typeOf(...args);
pub Value of(...args);
/* name(e) of an enum value: its member's name, a view of read-only data
 * ("" for a value that isn't one); reflect.nameOf(reflect.typeOf(e), e) */
pub u8[] name(...args);

/* reflect.of(x) is a call to this, made by the compiler */
pub Value __value(void *ptr, Type *type)
{
    Value v;
    v.ptr = ptr;
    v.type = type;
    return v;
}

/* The name of the member of enum type t whose value is `value`; "" when
 * there is none (or t isn't an enum). The first one when two share it. */
pub u8[] nameOf(Type *t, i64 value)
{
    if (t && t.kind == Kind.enumType) {
        for (auto m : t.members) {
            if (m.value == value) { return m.name; }
        }
    }
    return "";
}

/* The value of enum type t's member `name` into *value: false (and *value
 * left alone) when t has no such member */
pub bool enumValue(Type *t, u8[] name, i64 *value)
{
    if (t && t.kind == Kind.enumType) {
        for (auto m : t.members) {
            if (m.name.equals(name)) {
                if (value) { *value = m.value; }
                return true;
            }
        }
    }
    return false;
}

/* The same type: kind, name and size, and the same fields or element */
pub bool same(Type *a, Type *b)
{
    if (a == b) { return true; }
    if (!a || !b || a.kind != b.kind || a.size != b.size || a.len != b.len ||
            !a.name.equals(b.name) || a.fields.len != b.fields.len) {
        return false;
    }
    for (i64 i = 0; i < a.fields.len; i++) {
        if (!a.fields[i].name.equals(b.fields[i].name) ||
                a.fields[i].offset != b.fields[i].offset) {
            return false;
        }
    }
    return true;
}

/* The field `name` of a struct (or union) value, as a value of its own;
 * one with a null type when there is no such field */
pub Value field(Value v, u8[] name)
{
    Value f;
    f.ptr = null;
    f.type = null;
    if (!v.type || !v.ptr ||
            (v.type.kind != Kind.structType && v.type.kind != Kind.unionType)) {
        return f;
    }
    for (i64 i = 0; i < v.type.fields.len; i++) {
        if (v.type.fields[i].name.equals(name)) {
            f.ptr = (u8 *)v.ptr + v.type.fields[i].offset;
            f.type = v.type.fields[i].type;
            return f;
        }
    }
    return f;
}

/* The integer in v (an int, a bool or an enum, any size), sign-extended
 * as its type says; 0 for anything else */
pub i64 getInt(Value v)
{
    if (!v.type || !v.ptr) { return 0; }
    Kind k = v.type.kind;
    if (k != Kind.int && k != Kind.boolean && k != Kind.enumType) { return 0; }
    switch (v.type.size) {
        case 1: return v.type.isSigned ? (i64)*(i8 *)v.ptr  : (i64)*(u8 *)v.ptr;
        case 2: return v.type.isSigned ? (i64)*(i16 *)v.ptr : (i64)*(u16 *)v.ptr;
        case 4: return v.type.isSigned ? (i64)*(i32 *)v.ptr : (i64)*(u32 *)v.ptr;
        case 8: return *(i64 *)v.ptr;
    }
    return 0;
}

/* Store n in v (an int, a bool or an enum), truncated to its size as C
 * does (a bool gets 0 or 1); false if v is something else */
pub bool setInt(Value v, i64 n)
{
    if (!v.type || !v.ptr) { return false; }
    Kind k = v.type.kind;
    if (k == Kind.boolean) {
        *(u8 *)v.ptr = n != 0;
        return true;
    }
    if (k != Kind.int && k != Kind.enumType) { return false; }
    switch (v.type.size) {
        case 1: *(u8 *)v.ptr = (u8)n;   return true;
        case 2: *(u16 *)v.ptr = (u16)n; return true;
        case 4: *(u32 *)v.ptr = (u32)n; return true;
        case 8: *(i64 *)v.ptr = n;      return true;
    }
    return false;
}

/* The float in v (f64 or f32); 0 for anything else */
pub f64 getFloat(Value v)
{
    if (!v.type || !v.ptr || v.type.kind != Kind.float) { return 0; }
    if (v.type.size == 4) { return (f64)*(f32 *)v.ptr; }
    return *(f64 *)v.ptr;
}

/* Store x in v (f64 or f32); false if v is something else */
pub bool setFloat(Value v, f64 x)
{
    if (!v.type || !v.ptr || v.type.kind != Kind.float) { return false; }
    if (v.type.size == 4) {
        *(f32 *)v.ptr = (f32)x;
    } else {
        *(f64 *)v.ptr = x;
    }
    return true;
}
