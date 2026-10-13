module json;
import mem, reflect, str;

/* JSON into a value of any type, by its reflect description (v0.4.0):
 *
 *   User u;
 *   mem.set(&u, 0, sizeof(User));
 *   json.decode(text, reflect.of(u));
 *   defer json.free(reflect.of(u));
 *
 * Fields are matched by their names, private ones are skipped. A wrong
 * type throws ERR_DECODE with where it is ("items[2].price: expected a
 * number, got a string"). Keys the value has no field for are ignored and
 * fields the JSON leaves out keep what they had, unless flags has STRICT. */

/* json.decode's error code (Error.code) */
pub const i64 ERR_DECODE = 0x6B746D11;

/* json.decode flag: a key with no field, or a field with no key, is an error */
pub const i64 STRICT = 0x2;

static u8 *decodeGot(Value *n)
{
    switch (n.type) {
        case STRING: return "a string";
        case INT:
        case FLOAT:
        case STRNUM: return "a number";
        case BOOL:   return "a bool";
        case NULL:   return "null";
        case ARRAY:  return "an array";
        case OBJECT: return "an object";
    }
    return "something else";
}

static void decodeFail(String *path, u8 *want, Value *n)
{
    if (path.len) {
        throw(ERR_DECODE, "%s: expected %s, got %s", *path, want, decodeGot(n));
    }
    throw(ERR_DECODE, "expected %s, got %s", want, decodeGot(n));
}

static void decodeError(String *path, u8 *what)
{
    if (path.len) {
        throw(ERR_DECODE, "%s: %s", *path, what);
    }
    throw(ERR_DECODE, "%s", what);
}

/* A copy of t's bytes in a new block with a NUL after them */
static u8 *decodeBytes(u8[] t)
{
    u8 *p = mem.alloc(t.len + 1);
    if (t.len) { mem.copy(p, t.ptr, t.len); }
    p[t.len] = 0;
    return p;
}

static bool decodeIsByte(reflect.Type *t)
{
    return t && t.kind == reflect.Kind.int && t.size == 1;
}

static void decodeInto(Value *n, reflect.Value v, String *path, i64 flags);

/* Is t a vec.Vec<T> (any instance of the vec module's generic struct)?
 * By its type, not by its fields: a struct of one's own with data, len
 * and cap fields stays an object. */
static bool decodeIsVec(reflect.Type *t)
{
    return t && t.kind == reflect.Kind.structType && str.startsWith(t.name, "vec.Vec<") &&
           t.name.len > 9 && t.name[t.name.len - 1] == '>' && t.fields.len == 3 &&
           t.fields[0].type.kind == reflect.Kind.pointer;
}

/* Item i of array n into elem at p */
static void decodeItems(Value *n, u8 *p, reflect.Type *elem, String *path, i64 flags)
{
    i64 mark = path.len, i = 0;
    for (Value *c = n.array; c; c = c.next) {
        path.appendFormat("[%d]", i);
        decodeInto(c, reflect.__value(p + i * elem.size, elem), path, flags);
        path.remove(mark, path.len);
        i++;
    }
}

static void decodeStruct(Value *n, reflect.Value v, String *path, i64 flags)
{
    if (n.type != OBJECT) { decodeFail(path, "an object", n); }
    i64 mark = path.len;
    for (auto f : v.type.fields) {
        if (f.isPrivate) { continue; }
        Value *child = objectGet(n, f.name);
        if (mark) { path.append("."); }
        path.append(f.name);
        if (child) {
            decodeInto(child, reflect.__value((u8 *)v.ptr + f.offset, f.type), path, flags);
        } else if (flags & STRICT) {
            decodeError(path, "missing");
        }
        path.remove(mark, path.len);
    }
    if (!(flags & STRICT)) { return; }
    for (Value *c = n.object; c; c = c.next) {
        bool known = false;
        for (auto f : v.type.fields) {
            if (!f.isPrivate && f.name.equals(c.key)) { known = true; }
        }
        if (!known) {
            if (mark) { path.append("."); }
            path.append(c.key);
            String what = str.format("no such field in %s", v.type.name);
            defer what.free();
            decodeError(path, what.cstr());
        }
    }
}

static void decodeInto(Value *n, reflect.Value v, String *path, i64 flags)
{
    reflect.Type *t = v.type;
    switch (t.kind) {
        case reflect.Kind.boolean:
            if (n.type != BOOL) { decodeFail(path, "a bool", n); }
            reflect.setInt(v, n.boolean);
        case reflect.Kind.int:
            if (n.type != INT) { decodeFail(path, "an integer", n); }
            reflect.setInt(v, n.intVal);
        case reflect.Kind.float:
            if (n.type == INT) {
                reflect.setFloat(v, (f64)n.intVal);
            } else if (n.type == FLOAT) {
                reflect.setFloat(v, n.floatVal);
            } else {
                decodeFail(path, "a number", n);
            }
        case reflect.Kind.enumType:
            i64 member = 0;
            if (n.type == INT) {
                member = n.intVal;
            } else if (n.type != STRING) {
                String want = str.format("a name of %s", t.name);
                defer want.free();
                decodeFail(path, want.cstr(), n);
            } else if (!reflect.enumValue(t, n.str, &member)) {
                String what = str.format("%s has no member \"%s\"", t.name, n.str);
                defer what.free();
                decodeError(path, what.cstr());
            }
            reflect.setInt(v, member);
        case reflect.Kind.string:
            String *s = v.ptr;
            if (n.type == NULL) {
                *s = str.new();
            } else if (n.type == STRING) {
                *s = str.from(n.str);
            } else {
                decodeFail(path, "a string", n);
            }
        case reflect.Kind.slice:
            u8[] *view = v.ptr;
            u8[] none;
            if (n.type == NULL) {
                *view = none;
            } else if (decodeIsByte(t.elem) && n.type == STRING) {
                *view = decodeBytes(n.str)[0:n.str.len];
            } else if (n.type == ARRAY) {
                i64 count = len(n);
                u8 *items = mem.allocZero(count * t.elem.size + 1);
                *view = items[0:count];       /* set first: json.free finds it */
                decodeItems(n, items, t.elem, path, flags);
            } else {
                decodeFail(path, decodeIsByte(t.elem) ? "a string" : "an array", n);
            }
        case reflect.Kind.array:
            if (decodeIsByte(t.elem) && n.type == STRING) {
                /* a C string buffer, as encode writes it: the text and a NUL */
                if (n.str.len >= t.len) {
                    String what = str.format("expected at most %d bytes, got %d", t.len - 1, n.str.len);
                    defer what.free();
                    decodeError(path, what.cstr());
                }
                mem.set(v.ptr, 0, t.len);
                if (n.str.len) { mem.copy(v.ptr, n.str.ptr, n.str.len); }
                return;
            }
            if (n.type != ARRAY) { decodeFail(path, decodeIsByte(t.elem) ? "a string" : "an array", n); }
            if (len(n) > t.len) {
                String what = str.format("expected at most %d items, got %d", t.len, len(n));
                defer what.free();
                decodeError(path, what.cstr());
            }
            decodeItems(n, v.ptr, t.elem, path, flags);
        case reflect.Kind.pointer:
            void **slot = v.ptr;
            if (n.type == NULL) {
                *slot = null;
            } else if (decodeIsByte(t.elem)) {
                if (n.type != STRING) { decodeFail(path, "a string", n); }
                *slot = decodeBytes(n.str);
            } else if (t.elem.kind == reflect.Kind.none || t.elem.kind == reflect.Kind.fnType) {
                String what = str.format("a %s can't be JSON", t.name);
                defer what.free();
                decodeError(path, what.cstr());
            } else {
                *slot = mem.allocZero(t.elem.size);
                decodeInto(n, reflect.__value(*slot, t.elem), path, flags);
            }
        case reflect.Kind.structType:
            if (!decodeIsVec(t)) {
                decodeStruct(n, v, path, flags);
                return;
            }
            /* a vec.Vec<T>: an array, as a slice is; its items in a block
             * mem.alloc made, so its own methods can grow and free it */
            reflect.Type *elem = t.fields[0].type.elem;
            void **data = (void **)((u8 *)v.ptr + t.fields[0].offset);
            i64 *vlen = (i64 *)((u8 *)v.ptr + t.fields[1].offset);
            i64 *vcap = (i64 *)((u8 *)v.ptr + t.fields[2].offset);
            if (n.type == NULL) {
                *data = null;
                *vlen = 0;
                *vcap = 0;
            } else if (decodeIsByte(elem) && n.type == STRING) {
                /* a vec.Vec<u8> from a string, as a u8[]: NUL past len */
                *data = decodeBytes(n.str);
                *vlen = n.str.len;
                *vcap = n.str.len + 1;
            } else if (n.type == ARRAY) {
                i64 vcount = len(n);
                i64 room = vcount > 0 ? vcount : 1;
                u8 *block = mem.allocZero(room * elem.size);
                *data = block;              /* set first: json.free finds it */
                *vlen = vcount;
                *vcap = room;
                decodeItems(n, block, elem, path, flags);
            } else {
                decodeFail(path, decodeIsByte(elem) ? "a string" : "an array", n);
            }
        default:
            String what = str.format("a %s can't be JSON", t.name);
            defer what.free();
            decodeError(path, what.cstr());
    }
}

/* Fill v (reflect.of(x)) from the JSON in text. Strings, slices and the
 * structs pointers point to are allocated: json.free(v) frees them. Throws
 * ERR_DECODE when text isn't JSON or doesn't fit v's type. */
pub void decode(u8[] text, reflect.Value v, i64 flags = 0)
{
    if (!v.type || !v.ptr) {
        throw(ERR_DECODE, "decode: the value has no type (use reflect.of(x))");
    }
    Value *doc = parse(text);
    defer release(doc);
    if (!ok(doc)) {
        String why = errorStr(doc);
        defer why.free();
        throw(ERR_DECODE, "not JSON: %s", why);
    }
    String path = str.new();
    defer path.free();
    decodeInto(doc, v, &path, flags);
}

/* The JSON in text as a T: `User u = json.decode<User>(text);`, freed
 * with `json.free(reflect.of(u))`. Fields missing from the text are zero.
 * Throws ERR_DECODE as decode does; what was filled in by then is freed
 * first, so there is nothing to clean up after a throw. */
pub T decode<T>(u8[] text, i64 flags = 0)
{
    T out;
    mem.set(&out, 0, sizeof(T));
    try {
        decode(text, reflect.of(out), flags);
    } catch (Error e) {
        free(reflect.of(out));
        throw(e);
    }
    return out;
}

/* Free what decode allocated in v: its Strings, the items of its slices
 * and vec.Vecs, its u8 * strings and what its other pointers point to,
 * through nested structs and arrays; they are zeroed. Only for values
 * decode filled (or that own those the same way). Private fields are left
 * alone. */
pub void free(reflect.Value v)
{
    reflect.Type *t = v.type;
    if (!t || !v.ptr) { return; }
    switch (t.kind) {
        case reflect.Kind.string:
            String *s = v.ptr;
            s.free();
        case reflect.Kind.slice:
            u8[] *view = v.ptr;
            for (i64 i = 0; i < view.len; i++) {
                free(reflect.__value(view.ptr + i * t.elem.size, t.elem));
            }
            mem.free(view.ptr);
            u8[] none;
            *view = none;
        case reflect.Kind.array:
            for (i64 i = 0; i < t.len; i++) {
                free(reflect.__value((u8 *)v.ptr + i * t.elem.size, t.elem));
            }
        case reflect.Kind.pointer:
            void **slot = v.ptr;
            if (*slot && !decodeIsByte(t.elem)) {
                free(reflect.__value(*slot, t.elem));
            }
            mem.free(*slot);
            *slot = null;
        case reflect.Kind.structType:
            if (decodeIsVec(t)) {
                /* each item's, then the Vec's block; the Vec is zeroed */
                reflect.Type *elem = t.fields[0].type.elem;
                void **data = (void **)((u8 *)v.ptr + t.fields[0].offset);
                i64 *vlen = (i64 *)((u8 *)v.ptr + t.fields[1].offset);
                i64 *vcap = (i64 *)((u8 *)v.ptr + t.fields[2].offset);
                for (i64 i = 0; *data && i < *vlen; i++) {
                    free(reflect.__value((u8 *)*data + i * elem.size, elem));
                }
                mem.free(*data);
                *data = null;
                *vlen = 0;
                *vcap = 0;
                return;
            }
            for (auto f : t.fields) {
                if (!f.isPrivate) {
                    free(reflect.__value((u8 *)v.ptr + f.offset, f.type));
                }
            }
    }
}
