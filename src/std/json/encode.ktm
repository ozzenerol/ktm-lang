module json;
import cstr, reflect, str;

/* A value of any type as JSON, by its reflect description (v0.4.0):
 *
 *   String text = json.encode(reflect.of(user));   // or (..., 2) indented
 *   defer text.free();
 *
 * ints, floats and bools as numbers and true/false; an enum by its
 * member's name (its number when it has none); String, u8[] and u8 * as
 * strings (a null pointer as null); a u8 array as the C string in it;
 * other arrays and slices as arrays, and a vec.Vec<T> as one too, of its
 * items (like a T[]: a Vec<u8> is a string); a struct as an object of its fields in
 * order (private ones skipped); a pointer as what it points to. A union, a
 * function or a void * can't be JSON: that throws ERR_ENCODE with where it
 * is ("user.avatar: a fn(i64) i64 can't be JSON"). */

/* json.encode's error code (Error.code) */
pub const i64 ERR_ENCODE = 0x6B746D12;

static void encodeError(String *path, reflect.Type *t)
{
    if (path.len) {
        throw(ERR_ENCODE, "%s: a %s can't be JSON", *path, t.name);
    }
    throw(ERR_ENCODE, "a %s can't be JSON", t.name);
}

static bool encodeIsByte(reflect.Type *t)
{
    return t && t.kind == reflect.Kind.int && t.size == 1;
}

static void encodeInto(Writer *w, reflect.Value v, String *path);

/* Is t an instance of the vec module's generic struct Vec? By the type,
 * not by its fields: a struct of your own with data/len/cap stays an
 * object */
static bool encodeIsVec(reflect.Type *t)
{
    return t.kind == reflect.Kind.structType && str.startsWith(t.name, "vec.Vec<");
}

/* The field of t named `name`, null if it has none */
static reflect.Field *encodeField(reflect.Type *t, u8[] name)
{
    for (i64 i = 0; i < t.fields.len; i++) {
        if (str.equals(t.fields[i].name, name)) { return &t.fields[i]; }
    }
    return null;
}

static void encodeItems(Writer *w, u8 *p, i64 count, reflect.Type *elem, String *path)
{
    i64 mark = path.len;
    w.beginArray();
    for (i64 i = 0; i < count; i++) {
        path.appendFormat("[%d]", i);
        encodeInto(w, reflect.__value(p + i * elem.size, elem), path);
        path.remove(mark, path.len);
    }
    w.end();
}

static void encodeInto(Writer *w, reflect.Value v, String *path)
{
    reflect.Type *t = v.type;
    switch (t.kind) {
        case reflect.Kind.boolean:
            w.bool(reflect.getInt(v) != 0);
        case reflect.Kind.int:
            w.int(reflect.getInt(v));
        case reflect.Kind.float:
            w.float(reflect.getFloat(v));
        case reflect.Kind.enumType:
            i64 n = reflect.getInt(v);
            u8[] name = reflect.nameOf(t, n);
            if (name.len) {
                w.str(name);
            } else {
                w.int(n);
            }
        case reflect.Kind.string:
            String *s = v.ptr;
            w.str(*s);
        case reflect.Kind.slice:
            u8[] *view = v.ptr;
            if (encodeIsByte(t.elem)) {
                w.str(*view);
            } else {
                encodeItems(w, view.ptr, view.len, t.elem, path);
            }
        case reflect.Kind.array:
            if (encodeIsByte(t.elem)) {
                u8 *buf = v.ptr;
                i64 n = 0;
                while (n < t.len && buf[n]) { n++; }
                w.str(buf[0:n]);
            } else {
                encodeItems(w, v.ptr, t.len, t.elem, path);
            }
        case reflect.Kind.pointer:
            void *p = *(void **)v.ptr;
            if (t.elem.kind == reflect.Kind.none || t.elem.kind == reflect.Kind.fnType) {
                encodeError(path, t);
            } else if (!p) {
                w.nil();
            } else if (encodeIsByte(t.elem)) {
                w.str(cstr.view(p));
            } else {
                encodeInto(w, reflect.__value(p, t.elem), path);
            }
        case reflect.Kind.structType:
            if (t.toJson) {
                /* @json: its generated method */
                fn(void *, Writer *) void toJson = t.toJson;
                toJson(v.ptr, w);
                break;
            }
            if (encodeIsVec(t)) {
                /* its items, as a T[] slice of them would be */
                reflect.Field *data = encodeField(t, "data");
                reflect.Field *len = encodeField(t, "len");
                u8 *items = *(u8 **)((u8 *)v.ptr + data.offset);
                i64 count = *(i64 *)((u8 *)v.ptr + len.offset);
                if (encodeIsByte(data.type.elem)) {
                    w.str(items ? items[0:count] : "");
                } else {
                    encodeItems(w, items, count, data.type.elem, path);
                }
                break;
            }
            i64 mark = path.len;
            w.beginObject();
            for (auto f : t.fields) {
                if (f.isPrivate || f.skip) { continue; }
                if (mark) { path.append("."); }
                path.append(f.key);
                w.key(f.key);
                encodeInto(w, reflect.__value((u8 *)v.ptr + f.offset, f.type), path);
                path.remove(mark, path.len);
            }
            w.end();
        default:
            encodeError(path, t);
    }
}

/* v (reflect.of(x)) as JSON, a new String (yours); compact, or with
 * `indent` spaces per level. Throws ERR_ENCODE for what JSON can't hold
 * and ERR_WRITER for text that isn't UTF-8 or a NaN/infinite float. */
pub String encode(reflect.Value v, i64 indent = 0)
{
    if (!v.type || !v.ptr) {
        throw(ERR_ENCODE, "encode: the value has no type (use reflect.of(x))");
    }
    Writer w = writerNew(indent);
    defer w.free();
    String path = str.new();
    defer path.free();
    encodeInto(&w, v, &path);
    return w.take();
}
