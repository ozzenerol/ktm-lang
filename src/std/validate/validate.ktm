module validate;
import cstr, json, mem, net.url, reflect, str, vec;

/* Checking a struct's values against rules on its fields (v0.21.0):
 *
 *   @json struct Bookmark {
 *       @required @url String url;
 *       @required @max(200) String title;
 *       @oneOf("read", "unread") u8[] status;
 *       @min(0) @max(5) i64 stars;
 *       @max(10) u8[][] tags;
 *   };
 *   validate.Errors errs;
 *   if (!validate.check(reflect.of(b), &errs)) {
 *       "%s\n", errs.message();   // url: not a URL; title: is required
 *   }
 *   errs.free();
 *
 * The rules: @required (not 0, "", empty or null), @min(n) and @max(n)
 * (a number's value, a text's length in characters, a slice's in items),
 * @email, @url (with a scheme and a host) and @oneOf("a", "b"). A text or
 * slice that is empty and not @required passes its other rules: they
 * check a value when there is one. Nested structs, struct pointers and
 * slices of structs are checked too; each error names its field by its
 * JSON key, the way a client sent it: "address.city", "tags[2].name".
 * http.decodeValid decodes a request's body and checks it in one call. */

/* What check throws for a rule on a field it can't apply to (@email on
 * an i64): a mistake in the program, not in the value */
pub const i64 ERR_VALIDATE = 0x6B746D30;

/* What http.decodeValid throws for a value that breaks its rules; the
 * server answers it with a 422 and the errors as JSON */
pub const i64 ERR_INVALID = 0x6B746D31;

/* One field's problem: field "email", message "not an email address" */
pub struct FieldError {
    String field;
    String message;
};

/* Every problem check found, in field order */
pub struct Errors {
    vec.Vec<FieldError> items;
};

pub void Errors.free() {
    for (i64 i = 0; i < self.items.len; i++) {
        self.items.data[i].field.free();
        self.items.data[i].message.free();
    }
    self.items.free();
}

/* A problem of the program's own (a check rules can't express) */
pub void Errors.add(u8[] field, u8 *fmt, ...args) {
    FieldError e;
    e.field = str.from(field);
    u8 *text = cstr.formatArgs(null, fmt, args);
    e.message = str.from(text[0:cstr.len(text)]);
    mem.free(text);
    self.items.push(e);
}

/* "url: not a URL; title: is required" ("" when there are none) */
pub String Errors.message() {
    String o;
    for (i64 i = 0; i < self.items.len; i++) {
        if (i) { o.append("; "); }
        o.append(self.items.data[i].field[0:]);
        o.append(": ");
        o.append(self.items.data[i].message[0:]);
    }
    return o;
}

/* {"error":"invalid request","fields":[{"field":"url","message":"not a URL"}]} */
pub String Errors.toJson() {
    String o;
    o.append("{\"error\":\"invalid request\",\"fields\":[");
    for (i64 i = 0; i < self.items.len; i++) {
        if (i) { o.appendByte(','); }
        o.append("{\"field\":");
        json.quoteTo(&o, self.items.data[i].field[0:]);
        o.append(",\"message\":");
        json.quoteTo(&o, self.items.data[i].message[0:]);
        o.appendByte('}');
    }
    o.append("]}");
    return o;
}

/* The path of field f under prefix: "address.city" */
static String fieldPath(u8[] prefix, reflect.Field *f) {
    String p = str.from(prefix);
    if (prefix.len) { p.appendByte('.'); }
    p.append(f.key.len ? f.key : f.name);
    return p;
}

static void checkField(void *p, reflect.Field *f, u8[] path, Errors *out);

static bool isText(reflect.Type *t) {
    return t.kind == reflect.Kind.string ||
           (t.kind == reflect.Kind.slice && t.elem && t.elem.kind == reflect.Kind.int && t.elem.size == 1);
}

static u8[] textOf(void *p, reflect.Type *t) {
    if (t.kind == reflect.Kind.string) {
        String *s = p;
        return (*s)[0:];
    }
    return *(u8[] *)p;
}

static f64 numberOf(void *p, reflect.Type *t) {
    if (t.kind == reflect.Kind.float) {
        return t.size == 4 ? (f64)*(f32 *)p : *(f64 *)p;
    }
    if (t.isSigned) {
        if (t.size == 1) { return (f64)*(i8 *)p; }
        if (t.size == 2) { return (f64)*(i16 *)p; }
        if (t.size == 4) { return (f64)*(i32 *)p; }
        return (f64)*(i64 *)p;
    }
    if (t.size == 1) { return (f64)*(u8 *)p; }
    if (t.size == 2) { return (f64)*(u16 *)p; }
    if (t.size == 4) { return (f64)*(u32 *)p; }
    return (f64)*(u64 *)p;
}

static void misuse(reflect.Field *f, u8 *rule, u8 *takes) {
    throw(ERR_VALIDATE, "validate: @%s on %.*s, a %.*s: it takes %s", rule, f.name.len, f.name.ptr,
          f.type.name.len, f.type.name.ptr, takes);
}

/* Throws ERR_VALIDATE for a rule f's type can't have */
static void rulesFit(reflect.Field *f) {
    reflect.Type *t = f.type;
    bool text = isText(t);
    bool number = t.kind == reflect.Kind.int || t.kind == reflect.Kind.float;
    if ((f.email || f.url || f.oneOf.len) && !text) {
        misuse(f, f.email ? "email" : f.url ? "url" : "oneOf", "a String or a u8[]");
    }
    if ((f.hasMin || f.hasMax) && !text && !number && t.kind != reflect.Kind.slice) {
        misuse(f, f.hasMin ? "min" : "max", "a number, a String or a slice");
    }
}

static void checkStruct(void *base, reflect.Type *t, u8[] prefix, Errors *out) {
    for (i64 i = 0; i < t.fields.len; i++) {
        reflect.Field *f = &t.fields[i];
        if (f.skip || f.isPrivate) { continue; }
        rulesFit(f);
        String path = fieldPath(prefix, f);
        checkField((u8 *)base + f.offset, f, path[0:], out);
        path.free();
    }
}

/* "1 character", "3 characters" */
static void bound(Errors *out, u8[] path, bool atLeast, f64 n, u8 *unit) {
    out.add(path, "must %s at %s %g%s%s%s", unit ? "have" : "be", atLeast ? "least" : "most", n,
            unit ? " " : "", unit ? unit : "", unit && n != 1.0 ? "s" : "");
}

/* name@example.com: one '@', something before it, a domain with a dot
 * after it, no spaces or control bytes */
pub bool isEmail(u8[] s) {
    i64 at = -1;
    for (i64 i = 0; i < s.len; i++) {
        u8 c = s[i];
        if (c <= ' ' || c == 127 || c == '<' || c == '>' || c == ',' || c == ';' || c == '"') { return false; }
        if (c == '@') {
            if (at >= 0) { return false; }
            at = i;
        }
    }
    if (at < 1 || at + 1 >= s.len) { return false; }
    u8[] domain = s[at + 1:];
    if (domain[0] == '.' || domain[domain.len - 1] == '.' || !str.contains(domain, ".") ||
            str.contains(domain, "..")) {
        return false;
    }
    return true;
}

/* An absolute URL with a host: https://example.com/a */
pub bool isUrl(u8[] s) {
    url.URL u;
    bool ok = url.parse(s, &u) && u.isAbsolute() && u.host.len > 0;
    u.free();
    return ok;
}

static bool oneOfHas(u8[] list, u8[] s) {
    i64 start = 0;
    for (i64 i = 0; i <= list.len; i++) {
        if (i < list.len && list[i] != '|') { continue; }
        if (str.equals(list[start:i], s)) { return true; }
        start = i + 1;
    }
    return false;
}

static void checkField(void *p, reflect.Field *f, u8[] path, Errors *out) {
    reflect.Type *t = f.type;
    bool text = isText(t);
    bool number = t.kind == reflect.Kind.int || t.kind == reflect.Kind.float;
    bool slice = t.kind == reflect.Kind.slice && !text;

    if (text || slice) {
        u8[] *raw = p;
        i64 len = text ? str.runeCount(textOf(p, t)) : raw.len;
        if (!len) {
            if (f.required) { out.add(path, "is required"); }
            return;
        }
        u8 *unit = text ? "character" : "item";
        if (f.hasMin && (f64)len < f.min) { bound(out, path, true, f.min, unit); }
        if (f.hasMax && (f64)len > f.max) { bound(out, path, false, f.max, unit); }
        if (text) {
            u8[] s = textOf(p, t);
            if (f.email && !isEmail(s)) { out.add(path, "not an email address"); }
            if (f.url && !isUrl(s)) { out.add(path, "not a URL"); }
            if (f.oneOf.len && !oneOfHas(f.oneOf, s)) {
                String list = str.from(f.oneOf);
                list.replace("|", ", ");
                out.add(path, "must be one of %s", list.cstr());
                list.free();
            }
        } else if (t.elem && t.elem.kind == reflect.Kind.structType) {
            for (i64 i = 0; i < raw.len; i++) {
                String at = str.format("%.*s[%d]", path.len, path.ptr, i);
                checkStruct(raw.ptr + i * t.elem.size, t.elem, at[0:], out);
                at.free();
            }
        }
        return;
    }
    if (number) {
        f64 n = numberOf(p, t);
        if (f.required && n == 0.0) {
            out.add(path, "is required");
            return;
        }
        if (f.hasMin && n < f.min) { bound(out, path, true, f.min, null); }
        if (f.hasMax && n > f.max) { bound(out, path, false, f.max, null); }
        return;
    }
    if (t.kind == reflect.Kind.pointer) {
        void *q = *(void **)p;
        if (!q) {
            if (f.required) { out.add(path, "is required"); }
            return;
        }
        if (t.elem && t.elem.kind == reflect.Kind.structType) { checkStruct(q, t.elem, path, out); }
        return;
    }
    if (t.kind == reflect.Kind.structType) {
        checkStruct(p, t, path, out);
        return;
    }
    if (f.required) {
        bool zero = true;
        for (i64 i = 0; i < t.size; i++) {
            if (((u8 *)p)[i]) { zero = false; break; }
        }
        if (zero) { out.add(path, "is required"); }
    }
}

/* Checks the struct v (reflect.of(x)) into *out (replaced): true when
 * every rule holds, else false with one error per broken rule */
pub bool check(reflect.Value v, Errors *out) {
    out.free();
    mem.set(out, 0, sizeof(Errors));
    if (!v.type || v.type.kind != reflect.Kind.structType) {
        throw(ERR_VALIDATE, "validate: check takes a struct: validate.check(reflect.of(x), &errs)");
    }
    checkStruct(v.ptr, v.type, "", out);
    return out.items.len == 0;
}
