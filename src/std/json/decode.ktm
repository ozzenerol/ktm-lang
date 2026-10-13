module json;
import mem, reflect, str;

/* JSON into a value of any type, by its reflect description (v0.4.0):
 *
 *   User u;
 *   mem.set(&u, 0, sizeof(User));
 *   json.decode(text, reflect.of(u));
 *   defer json.free(reflect.of(u));
 *
 * Fields are matched by their keys (@key, else their names); private and
 * @skip ones are skipped. A wrong type throws ERR_DECODE with where it is
 * ("1:40: items[2].price: expected a number, got a string"). Keys the value has no field for are ignored and
 * fields the JSON leaves out keep what they had, unless flags has STRICT. */

/* json.decode's error code (Error.code) */
pub const i64 ERR_DECODE = 0x6B746D11;

/* json.decode flag: a key with no field, or a field with no key, is an error */
pub const i64 STRICT = 0x2;

/* What r's next value is, for a message */
static u8 *decodeGot(Reader *r)
{
    switch (r.peek()) {
        case STRING: return "a string";
        case INT:
        case FLOAT:  return "a number";
        case BOOL:   return "a bool";
        case NULL:   return "null";
        case ARRAY:  return "an array";
        case OBJECT: return "an object";
    }
    return "something else";
}

static void decodeFail(Reader *r, u8 *want)
{
    String what = str.format("expected %s, got %s", want, decodeGot(r));
    defer what.free();
    r.fail(what.cstr());
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

static void decodeInto(Reader *r, reflect.Value v, i64 flags);

/* Is t a vec.Vec<T> (any instance of the vec module's generic struct)?
 * By its type, not by its fields: a struct of one's own with data, len
 * and cap fields stays an object. */
static bool decodeIsVec(reflect.Type *t)
{
    return t && t.kind == reflect.Kind.structType && str.startsWith(t.name, "vec.Vec<") &&
           t.name.len > 9 && t.name[t.name.len - 1] == '>' && t.fields.len == 3 &&
           t.fields[0].type.kind == reflect.Kind.pointer;
}

/* The items of the array r is at, of elem, into a block that grows as it
 * goes (mem.realloc, the new room zeroed). *block and *count are kept up
 * to date item by item, so json.free finds what was filled in if one
 * throws. */
static void decodeGrow(Reader *r, u8 **block, i64 *count, i64 *room, reflect.Type *elem, i64 flags)
{
    r.beginArray();
    while (r.nextItem()) {
        if (*count == *room) {
            i64 more = *room ? *room * 2 : 4;
            *block = mem.realloc(*block, more * elem.size + 1);
            mem.set(*block + *room * elem.size, 0, (more - *room) * elem.size + 1);
            *room = more;
        }
        u8 *at = *block + *count * elem.size;
        *count = *count + 1;
        decodeInto(r, reflect.__value(at, elem), flags);
    }
}

static void decodeStruct(Reader *r, reflect.Value v, i64 flags)
{
    reflect.Type *t = v.type;
    if (t.fromJson) {
        /* @json: its generated method (STRICT through the Reader) */
        fn(void *, Reader *) void fromJson = t.fromJson;
        fromJson(v.ptr, r);
        return;
    }
    if (r.peek() != OBJECT) { decodeFail(r, "an object"); }
    /* which fields were read, for STRICT */
    bool *seen = (flags & STRICT) ? mem.allocZero(t.fields.len + 1) : null;
    defer mem.free(seen);
    r.beginObject();
    while (r.nextKey()) {
        u8[] k = r.key();
        bool known = false;
        for (i64 i = 0; i < t.fields.len; i++) {
            reflect.Field *f = &t.fields[i];
            if (f.isPrivate || f.skip || !f.key.equals(k)) { continue; }
            if (seen) { seen[i] = true; }
            decodeInto(r, reflect.__value((u8 *)v.ptr + f.offset, f.type), flags);
            known = true;
            break;
        }
        if (known) { continue; }
        if (flags & STRICT) {
            String what = str.format("no such field in %s", t.name);
            defer what.free();
            r.fail(what.cstr());
        }
        r.skipValue();
    }
    if (!(flags & STRICT)) { return; }
    for (i64 i = 0; i < t.fields.len; i++) {
        reflect.Field *f = &t.fields[i];
        if (f.isPrivate || f.skip || seen[i]) { continue; }
        r.fail("missing", f.key.ptr);
    }
}

static void decodeInto(Reader *r, reflect.Value v, i64 flags)
{
    reflect.Type *t = v.type;
    switch (t.kind) {
        case reflect.Kind.boolean:
            if (r.peek() != BOOL) { decodeFail(r, "a bool"); }
            reflect.setInt(v, r.readBool());
        case reflect.Kind.int:
            if (r.peek() != INT) { decodeFail(r, "an integer"); }
            reflect.setInt(v, r.readInt());
        case reflect.Kind.float:
            i64 kind = r.peek();
            if (kind != INT && kind != FLOAT) { decodeFail(r, "a number"); }
            reflect.setFloat(v, r.readFloat());
        case reflect.Kind.enumType:
            i64 member = 0;
            i64 got = r.peek();
            if (got == INT) {
                member = r.readInt();
            } else if (got != STRING) {
                String want = str.format("a name of %s", t.name);
                defer want.free();
                decodeFail(r, want.cstr());
            } else {
                u8[] name = r.readStr();
                if (!reflect.enumValue(t, name, &member)) {
                    String what = str.format("%s has no member \"%s\"", t.name, name);
                    defer what.free();
                    r.fail(what.cstr());
                }
            }
            reflect.setInt(v, member);
        case reflect.Kind.string:
            String *s = v.ptr;
            if (r.isNull()) {
                *s = str.new();
            } else {
                if (r.peek() != STRING) { decodeFail(r, "a string"); }
                *s = str.from(r.readStr());
            }
        case reflect.Kind.slice:
            u8[] *view = v.ptr;
            u8[] none;
            if (r.isNull()) {
                *view = none;
            } else if (decodeIsByte(t.elem) && r.peek() == STRING) {
                u8[] bytes = r.readStr();
                *view = decodeBytes(bytes)[0:bytes.len];
            } else if (r.peek() == ARRAY) {
                u8 *items = null;
                i64 count = 0, room = 0;
                *view = none;
                try {
                    decodeGrow(r, &items, &count, &room, t.elem, flags);
                } catch (Error e) {
                    *view = items ? items[0:count] : none;   /* for json.free */
                    throw(e);
                }
                if (!items) { items = mem.allocZero(1); }
                *view = items[0:count];
            } else {
                decodeFail(r, decodeIsByte(t.elem) ? "a string" : "an array");
            }
        case reflect.Kind.array:
            if (decodeIsByte(t.elem) && r.peek() == STRING) {
                /* a C string buffer, as encode writes it: the text and a NUL */
                u8[] chars = r.readStr();
                if (chars.len >= t.len) {
                    String what = str.format("expected at most %d bytes, got %d", t.len - 1, chars.len);
                    defer what.free();
                    r.fail(what.cstr());
                }
                mem.set(v.ptr, 0, t.len);
                if (chars.len) { mem.copy(v.ptr, chars.ptr, chars.len); }
                return;
            }
            if (r.peek() != ARRAY) { decodeFail(r, decodeIsByte(t.elem) ? "a string" : "an array"); }
            i64 i = 0;
            r.beginArray();
            while (r.nextItem()) {
                if (i == t.len) {
                    /* how many there are, for the message */
                    i64 total = i;
                    do {
                        r.skipValue();
                        total++;
                    } while (r.nextItem());
                    String what = str.format("expected at most %d items, got %d", t.len, total);
                    defer what.free();
                    r.fail(what.cstr());
                }
                decodeInto(r, reflect.__value((u8 *)v.ptr + i * t.elem.size, t.elem), flags);
                i++;
            }
        case reflect.Kind.pointer:
            void **slot = v.ptr;
            if (r.isNull()) {
                *slot = null;
            } else if (decodeIsByte(t.elem)) {
                if (r.peek() != STRING) { decodeFail(r, "a string"); }
                *slot = decodeBytes(r.readStr());
            } else if (t.elem.kind == reflect.Kind.none || t.elem.kind == reflect.Kind.fnType) {
                String what = str.format("a %s can't be JSON", t.name);
                defer what.free();
                r.fail(what.cstr());
            } else {
                *slot = mem.allocZero(t.elem.size);
                decodeInto(r, reflect.__value(*slot, t.elem), flags);
            }
        case reflect.Kind.structType:
            if (!decodeIsVec(t)) {
                decodeStruct(r, v, flags);
                return;
            }
            /* a vec.Vec<T>: an array, as a slice is; its items in a block
             * mem.alloc made, so its own methods can grow and free it */
            reflect.Type *elem = t.fields[0].type.elem;
            u8 **data = (u8 **)((u8 *)v.ptr + t.fields[0].offset);
            i64 *vlen = (i64 *)((u8 *)v.ptr + t.fields[1].offset);
            i64 *vcap = (i64 *)((u8 *)v.ptr + t.fields[2].offset);
            *data = null;
            *vlen = 0;
            *vcap = 0;
            if (r.isNull()) {
                return;
            }
            if (decodeIsByte(elem) && r.peek() == STRING) {
                /* a vec.Vec<u8> from a string, as a u8[]: NUL past len */
                u8[] vbytes = r.readStr();
                *data = decodeBytes(vbytes);
                *vlen = vbytes.len;
                *vcap = vbytes.len + 1;
            } else if (r.peek() == ARRAY) {
                decodeGrow(r, data, vlen, vcap, elem, flags);
                if (!*data) {
                    *data = mem.allocZero(elem.size);
                    *vcap = 1;
                }
            } else {
                decodeFail(r, decodeIsByte(elem) ? "a string" : "an array");
            }
        default:
            String what = str.format("a %s can't be JSON", t.name);
            defer what.free();
            r.fail(what.cstr());
    }
}

/* Fill v (reflect.of(x)) from the JSON in text. Strings, slices and the
 * structs pointers point to are allocated: json.free(v) frees them. Throws
 * ERR_DECODE when text isn't JSON or doesn't fit v's type, with where:
 * "1:34: lines[1].price: expected a number, got a string". The text is
 * read front to back by a json.Reader, no tree is built, and a @json
 * struct (at any depth) is read by its generated fromJson. */
pub void decode(u8[] text, reflect.Value v, i64 flags = 0)
{
    if (!v.type || !v.ptr) {
        throw(ERR_DECODE, "decode: the value has no type (use reflect.of(x))");
    }
    Reader r = readerNew(text, flags);
    defer r.free();
    decodeInto(&r, v, flags);
    r.finish();
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
    if (t.freeDecoded) {
        fn(void *) void freeDecoded = t.freeDecoded;
        freeDecoded(v.ptr);
        return;
    }
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
                if (!f.isPrivate && !f.skip) {
                    free(reflect.__value((u8 *)v.ptr + f.offset, f.type));
                }
            }
    }
}
