module config;
import cstr, fs, libc, mem, proc, reflect, str;

/* A program's settings from environment variables and a .env file
 * (v0.20.0), into a struct:
 *
 *   struct Settings {
 *       i64 port;                       // APP_PORT
 *       @required String databaseUrl;   // APP_DATABASE_URL, must be set
 *       @env("LOG_LEVEL") u8[] logLevel; // LOG_LEVEL, as written
 *       bool debug;                     // APP_DEBUG: true/false, 1/0, yes/no, on/off
 *   };
 *   Settings s;
 *   s.port = 8080;                      // a default: kept when APP_PORT isn't set
 *   config.loadDotenv(".env");          // KEY=value lines, never over the real environment
 *   config.fromEnv(reflect.of(s), "APP_");
 *   ...
 *   config.free(reflect.of(s));         // the String fields
 *
 * A field's variable is the prefix and its name in SNAKE_CASE, or
 * @env("NAME")'s exactly. A nested struct's fields add its name:
 * APP_DB_HOST for s.db.host. A variable set to "" counts as not set. */

/* What fromEnv and loadDotenv throw: the message names every variable
 * that is missing or wrong */
pub const i64 ERR_CONFIG = 0x6B746D23;

/* databaseUrl -> DATABASE_URL, httpURL -> HTTP_URL, URLPath -> URL_PATH */
pub String snakeUpper(u8[] name)
{
    String o;
    for (i64 i = 0; i < name.len; i++) {
        u8 c = name[i];
        bool upper = c >= 'A' && c <= 'Z';
        if (upper && i > 0 && name[i - 1] != '_') {
            u8 p = name[i - 1];
            bool pLower = (p >= 'a' && p <= 'z') || (p >= '0' && p <= '9');
            bool pUpper = p >= 'A' && p <= 'Z';
            bool nLower = i + 1 < name.len && name[i + 1] >= 'a' && name[i + 1] <= 'z';
            if (pLower || (pUpper && nLower)) { o.appendByte('_'); }
        }
        o.appendByte(str.toUpper(c));
    }
    return o;
}

static bool sameFold(u8[] a, u8[] b)
{
    if (a.len != b.len) { return false; }
    for (i64 i = 0; i < a.len; i++) {
        if (str.toLower(a[i]) != str.toLower(b[i])) { return false; }
    }
    return true;
}

/* The variable's value, a view of the environment's; false when unset or "" */
static bool lookup(u8[] name, u8[] *out)
{
    String n = str.from(name);
    defer n.free();
    u8 *p = libc.getenv(n.cstr());
    if (!p || !*p) { return false; }
    *out = p[0:libc.strlen(p)];
    return true;
}

static void problem(String *errs, u8 *fmt, ...args)
{
    if (errs.len) { errs.append("; "); } else { errs.append("config: "); }
    u8 *text = cstr.formatArgs(null, fmt, args);
    errs.append(text[0:cstr.len(text)]);
    mem.free(text);
}

/* Sets the field at p, of type t, from text; false when it doesn't fit */
static bool setField(void *p, reflect.Type *t, u8[] text, u8[] name, String *errs)
{
    if (t.kind == reflect.Kind.int) {
        i64 v;
        bool ok;
        if (t.isSigned) {
            ok = str.toInt(text, &v);
            if (ok && t.size < 8) {
                i64 lim = (i64)1 << (t.size * 8 - 1);
                ok = v >= -lim && v < lim;
            }
        } else {
            u64 u;
            ok = str.toUint(text, &u);
            if (ok && t.size < 8) { ok = u < ((u64)1 << (t.size * 8)); }
            v = (i64)u;
        }
        if (!ok) {
            problem(errs, "%.*s: `%.*s` isn't a whole number that fits %.*s", name.len, name.ptr,
                    text.len, text.ptr, t.name.len, t.name.ptr);
            return false;
        }
        if (t.size == 1) { *(u8 *)p = (u8)v; }
        else if (t.size == 2) { *(u16 *)p = (u16)v; }
        else if (t.size == 4) { *(u32 *)p = (u32)v; }
        else { *(i64 *)p = v; }
        return true;
    }
    if (t.kind == reflect.Kind.float) {
        f64 v;
        if (!str.toFloat(text, &v)) {
            problem(errs, "%.*s: `%.*s` isn't a number", name.len, name.ptr, text.len, text.ptr);
            return false;
        }
        if (t.size == 4) { *(f32 *)p = (f32)v; } else { *(f64 *)p = v; }
        return true;
    }
    if (t.kind == reflect.Kind.boolean) {
        if (sameFold(text, "true") || sameFold(text, "1") || sameFold(text, "yes") || sameFold(text, "on")) {
            *(bool *)p = true;
            return true;
        }
        if (sameFold(text, "false") || sameFold(text, "0") || sameFold(text, "no") || sameFold(text, "off")) {
            *(bool *)p = false;
            return true;
        }
        problem(errs, "%.*s: `%.*s` isn't true or false", name.len, name.ptr, text.len, text.ptr);
        return false;
    }
    if (t.kind == reflect.Kind.enumType) {
        for (auto m : t.members) {
            if (sameFold(m.name, text)) {
                if (t.size == 1) { *(u8 *)p = (u8)m.value; }
                else if (t.size == 2) { *(u16 *)p = (u16)m.value; }
                else if (t.size == 4) { *(u32 *)p = (u32)m.value; }
                else { *(i64 *)p = m.value; }
                return true;
            }
        }
        String names;
        for (i64 i = 0; i < t.members.len; i++) {
            if (i) { names.append(", "); }
            names.append(t.members[i].name);
        }
        problem(errs, "%.*s: `%.*s` isn't one of %.*s", name.len, name.ptr, text.len, text.ptr, names.len, names.ptr);
        names.free();
        return false;
    }
    if (t.kind == reflect.Kind.string) {
        String *s = p;
        s.free();
        *s = str.from(text);
        return true;
    }
    if (t.kind == reflect.Kind.slice && t.elem && t.elem.kind == reflect.Kind.int && t.elem.size == 1) {
        u8[] *s = p;
        *s = text;
        return true;
    }
    problem(errs, "%.*s: a %.*s field can't be read from a variable", name.len, name.ptr, t.name.len, t.name.ptr);
    return false;
}

static void fill(void *base, reflect.Type *t, u8[] prefix, String *errs)
{
    for (auto f : t.fields) {
        if (f.skip || f.isPrivate) { continue; }
        void *p = (u8 *)base + f.offset;
        String name;
        if (f.env.len) {
            name = str.from(f.env);
        } else {
            name = str.from(prefix);
            String part = snakeUpper(f.name);
            name.append(part[0:]);
            part.free();
        }
        if (f.type.kind == reflect.Kind.structType && !f.env.len) {
            name.appendByte('_');
            fill(p, f.type, name[0:], errs);
            name.free();
            continue;
        }
        u8[] text;
        if (lookup(name[0:], &text)) {
            setField(p, f.type, text, name[0:], errs);
        } else if (f.required) {
            problem(errs, "%.*s is required", name.len, name.ptr);
        }
        name.free();
    }
}

/* Fills the struct v (reflect.of(settings)) from the environment: each
 * field whose variable is set; the others keep what they hold (a default).
 * Throws ERR_CONFIG naming every variable that is missing (@required) or
 * can't be read, after setting the rest. u8[] fields are views of the
 * environment's values, String fields copies (config.free frees them). */
pub void fromEnv(reflect.Value v, u8[] prefix = "")
{
    if (!v.type || v.type.kind != reflect.Kind.structType) {
        throw(ERR_CONFIG, "config: fromEnv takes a struct: config.fromEnv(reflect.of(settings))");
    }
    String errs;
    defer errs.free();
    fill(v.ptr, v.type, prefix, &errs);
    if (errs.len) { throw(ERR_CONFIG, "%s", errs.cstr()); }
}

static void freeFields(void *base, reflect.Type *t)
{
    for (auto f : t.fields) {
        void *p = (u8 *)base + f.offset;
        if (f.type.kind == reflect.Kind.string) {
            String *s = p;
            s.free();
        } else if (f.type.kind == reflect.Kind.structType) {
            freeFields(p, f.type);
        }
    }
}

/* Frees the String fields of the struct v (nested ones too) */
pub void free(reflect.Value v)
{
    if (v.type && v.type.kind == reflect.Kind.structType) { freeFields(v.ptr, v.type); }
}

static bool nameOk(u8[] k)
{
    if (!k.len || (k[0] >= '0' && k[0] <= '9')) { return false; }
    for (auto c : k) {
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.')) {
            return false;
        }
    }
    return true;
}

/* Sets the variables in the .env file at path: `KEY=value` lines,
 * `export KEY=value`, # comments, "double quotes" (with \n, \t, \", \\
 * and ${OTHER} expanded) and 'single quotes' (as written); an unquoted
 * value ends at ` #`. A variable that is already set keeps its value
 * unless override. Answers how many it set, -1 when there is no such
 * file; a line it can't read throws ERR_CONFIG with its number. */
pub i64 loadDotenv(u8[] path = ".env", bool override = false)
{
    String text = fs.readAll(path);
    if (!text.ptr) { return -1; }
    defer text.free();
    i64 set = 0, lineNo = 0;
    u8[] rest = text[0:];
    while (rest.len) {
        lineNo++;
        i64 nl = str.find(rest, "\n");
        u8[] line = nl < 0 ? rest : rest[0:nl];
        rest = nl < 0 ? rest[rest.len:] : rest[nl + 1:];
        line = str.trim(line);
        if (!line.len || line[0] == '#') { continue; }
        if (str.startsWith(line, "export ")) { line = str.trim(line[7:]); }
        i64 eq = str.find(line, "=");
        u8[] key = eq < 0 ? line : str.trim(line[0:eq]);
        if (eq < 0 || !nameOk(key)) {
            throw(ERR_CONFIG, "config: %.*s:%d: expected KEY=value", path.len, path.ptr, lineNo);
        }
        u8[] raw = str.trimStart(line[eq + 1:]);
        String value;
        if (raw.len && (raw[0] == '"' || raw[0] == '\'')) {
            u8 q = raw[0];
            /* a quoted value may run over lines */
            u8[] all = text[(raw.ptr - text.ptr) + 1:];
            i64 i = 0;
            bool closed = false;
            while (i < all.len) {
                u8 c = all[i];
                if (c == q) { closed = true; break; }
                if (c == '\n') { lineNo++; }
                if (q == '"' && c == '\\' && i + 1 < all.len) {
                    u8 d = all[i + 1];
                    if (d == 'n') { value.appendByte('\n'); }
                    else if (d == 't') { value.appendByte('\t'); }
                    else if (d == 'r') { value.appendByte('\r'); }
                    else { value.appendByte(d); }
                    i += 2;
                    continue;
                }
                value.appendByte(c);
                i++;
            }
            if (!closed) {
                value.free();
                throw(ERR_CONFIG, "config: %.*s:%d: a quote that isn't closed", path.len, path.ptr, lineNo);
            }
            u8[] after = all[i + 1:];
            i64 anl = str.find(after, "\n");
            u8[] tail = str.trim(anl < 0 ? after : after[0:anl]);
            if (tail.len && tail[0] != '#') {
                value.free();
                throw(ERR_CONFIG, "config: %.*s:%d: text after the closing quote", path.len, path.ptr, lineNo);
            }
            rest = anl < 0 ? after[after.len:] : after[anl + 1:];
            if (q == '"') {
                String x = proc.expandEnv(value[0:]);
                value.free();
                value = x;
            }
        } else {
            i64 hash = str.find(raw, " #");
            value = str.from(str.trimEnd(hash < 0 ? raw : raw[0:hash]));
        }
        String k = str.from(key);
        if (override || !libc.getenv(k.cstr())) {
            libc.setenv(k.cstr(), value.cstr(), 1);
            set++;
        }
        k.free();
        value.free();
    }
    return set;
}
