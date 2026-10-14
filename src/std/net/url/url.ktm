module url;
import mem, str, vec;

/* URLs (RFC 3986, v0.20.0), as Go's net/url: parse one into its parts,
 * build one back, query strings, and a link resolved against a base.
 *
 *   url.URL u;
 *   if (!url.parse("https://ann:pw@example.com:8443/a/b?x=1&y=two#top", &u)) { ... }
 *   defer u.free();
 *   // u.scheme "https", u.user "ann", u.password "pw", u.host "example.com",
 *   // u.port 8443, u.path "/a/b", u.rawQuery "x=1&y=two", u.fragment "top"
 *
 * path, rawQuery and fragment are kept as written (percent-encoded);
 * user and password decoded. */

pub struct URL {
    String scheme;      /* lower case: "https" */
    String user;
    String password;
    bool hasUser;       /* a user part was there ("user@", maybe empty) */
    bool hasPassword;
    String host;        /* without brackets for IPv6: "::1" */
    bool hasAuthority;  /* "//" was there (a host, maybe empty: file:///x) */
    i32 port;           /* 0: none given */
    String path;        /* as written: "/a%20b/c" */
    String rawQuery;    /* after '?', without it */
    bool hasQuery;      /* a '?' was there, maybe with nothing after it */
    String fragment;    /* after '#', without it */
    bool hasFragment;
};

/* Frees its Strings */
pub void URL.free() {
    self.scheme.free();
    self.user.free();
    self.password.free();
    self.host.free();
    self.path.free();
    self.rawQuery.free();
    self.fragment.free();
}

/* u's Strings freed and every field cleared */
static void reset(URL *u) {
    u.free();
    mem.set(u, 0, sizeof(URL));
}

static bool schemeChar(u8 c, bool first) {
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) { return true; }
    return !first && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.');
}

static i64 indexOf(u8[] s, u8 c) {
    for (i64 i = 0; i < s.len; i++) { if (s[i] == c) { return i; } }
    return -1;
}

static i64 lastIndexOf(u8[] s, u8 c) {
    for (i64 i = s.len - 1; i >= 0; i--) { if (s[i] == c) { return i; } }
    return -1;
}

/* Every '%' followed by two hex digits, and no byte a URL can't hold */
static bool wellFormed(u8[] s) {
    for (i64 i = 0; i < s.len; i++) {
        u8 c = s[i];
        if (c <= ' ' || c == 0x7F) { return false; }
        if (c == '%') {
            if (i + 2 >= s.len) { return false; }
            String d;
            bool ok = str.urlDecode(s[i:i + 3], &d);
            d.free();
            if (!ok) { return false; }
            i += 2;
        }
    }
    return true;
}

/* text into *out (whose Strings are replaced): an absolute URL
 * ("https://host/path?q#f"), or a reference without a scheme ("/path",
 * "a/b?q", "//host/path"). False, with *out cleared, when it isn't one:
 * a bad port, a bad %-escape, a space or control byte, an unclosed
 * IPv6 bracket. */
pub bool parse(u8[] text, URL *out) {
    reset(out);
    u8[] rest = text;
    /* the fragment, then the query */
    i64 h = indexOf(rest, '#');
    if (h >= 0) {
        out.hasFragment = true;
        out.fragment = str.from(rest[h + 1:]);
        rest = rest[0:h];
    }
    i64 q = indexOf(rest, '?');
    if (q >= 0) {
        out.hasQuery = true;
        out.rawQuery = str.from(rest[q + 1:]);
        rest = rest[0:q];
    }
    /* the scheme: letters first, up to ':' before any '/' */
    i64 i = 0;
    while (i < rest.len && schemeChar(rest[i], i == 0)) { i++; }
    if (i > 0 && i < rest.len && rest[i] == ':') {
        out.scheme = str.from(rest[0:i]);
        out.scheme.toLower();
        rest = rest[i + 1:];
    }
    /* the authority: after "//", up to the path */
    if (rest.len >= 2 && rest[0] == '/' && rest[1] == '/') {
        out.hasAuthority = true;
        rest = rest[2:];
        i64 end = indexOf(rest, '/');
        u8[] auth = end >= 0 ? rest[0:end] : rest;
        rest = end >= 0 ? rest[end:] : "";
        i64 at = lastIndexOf(auth, '@');
        if (at >= 0) {
            u8[] info = auth[0:at];
            auth = auth[at + 1:];
            out.hasUser = true;
            i64 colon = indexOf(info, ':');
            u8[] user = colon >= 0 ? info[0:colon] : info;
            if (!str.urlDecode(user, &out.user)) { reset(out); return false; }
            if (colon >= 0) {
                out.hasPassword = true;
                if (!str.urlDecode(info[colon + 1:], &out.password)) { reset(out); return false; }
            }
        }
        u8[] hostPart = auth;
        u8[] portPart = "";
        bool hasPort = false;
        if (auth.len && auth[0] == '[') {
            i64 close = indexOf(auth, ']');
            if (close < 0) { reset(out); return false; }
            hostPart = auth[1:close];
            u8[] after = auth[close + 1:];
            if (after.len) {
                if (after[0] != ':') { reset(out); return false; }
                portPart = after[1:];
                hasPort = true;
            }
        } else {
            i64 colon = lastIndexOf(auth, ':');
            if (colon >= 0) {
                hostPart = auth[0:colon];
                portPart = auth[colon + 1:];
                hasPort = true;
            }
        }
        if (hasPort && portPart.len) {
            i64 p;
            for (i64 k = 0; k < portPart.len; k++) {
                if (portPart[k] < '0' || portPart[k] > '9') { reset(out); return false; }
            }
            if (!str.toInt(portPart, &p) || p < 1 || p > 65535) { reset(out); return false; }
            out.port = (i32)p;
        }
        if (!wellFormed(hostPart)) { reset(out); return false; }
        out.host = str.from(hostPart);
        out.host.toLower();
    }
    if (!wellFormed(rest) || !wellFormed(out.rawQuery[0:]) || !wellFormed(out.fragment[0:])) {
        reset(out);
        return false;
    }
    out.path = str.from(rest);
    return true;
}

/* Does it have a host ("//host" was there)? */
pub bool URL.isAbsolute() {
    return self.scheme.len > 0;
}

/* The port, or the scheme's usual one (http 80, https 443, ws 80, wss
 * 443, postgres 5432, mysql 3306, redis 6379, ftp 21); 0 if unknown */
pub i32 URL.portOrDefault() {
    if (self.port) { return self.port; }
    u8[] s = self.scheme[0:];
    if (str.equals(s, "http") || str.equals(s, "ws")) { return 80; }
    if (str.equals(s, "https") || str.equals(s, "wss")) { return 443; }
    if (str.equals(s, "postgres") || str.equals(s, "postgresql")) { return 5432; }
    if (str.equals(s, "mysql")) { return 3306; }
    if (str.equals(s, "redis") || str.equals(s, "rediss")) { return 6379; }
    if (str.equals(s, "ftp")) { return 21; }
    return 0;
}

/* The URL as text again: a new String, yours */
pub String URL.string() {
    String out;
    if (self.scheme.len) {
        out.append(self.scheme);
        out.appendByte(':');
    }
    if (self.hasAuthority || self.host.len || self.hasUser || self.port) {
        out.append("//");
        if (self.hasUser) {
            ad String u = str.urlEncode(self.user);
            out.append(u);
            if (self.hasPassword) {
                ad String p = str.urlEncode(self.password);
                out.appendByte(':');
                out.append(p);
            }
            out.appendByte('@');
        }
        bool v6 = indexOf(self.host[0:], ':') >= 0;
        if (v6) { out.appendByte('['); }
        out.append(self.host);
        if (v6) { out.appendByte(']'); }
        if (self.port) { out.appendFormat(":%d", self.port); }
        if (self.path.len && self.path[0] != '/') { out.appendByte('/'); }
    }
    out.append(self.path);
    if (self.hasQuery || self.rawQuery.len) {
        out.appendByte('?');
        out.append(self.rawQuery);
    }
    if (self.hasFragment || self.fragment.len) {
        out.appendByte('#');
        out.append(self.fragment);
    }
    return out;
}

/* ---- query strings ---- */

pub struct Param {
    String name;
    String value;
};

/* A query's name=value pairs, in order (a name may repeat) */
pub struct Values {
    vec.Vec<Param> items;
};

pub void Values.free() {
    for (i64 i = 0; i < self.items.len; i++) {
        self.items.data[i].name.free();
        self.items.data[i].value.free();
    }
    self.items.free();
}

static void resetValues(Values *v) {
    v.free();
    mem.set(v, 0, sizeof(Values));
}

/* raw ("a=1&b=two+words&c") into *out (replaced), each name and value
 * decoded ('+' is a space): false on a bad %-escape, with *out empty */
pub bool parseQuery(u8[] raw, Values *out) {
    resetValues(out);
    i64 start = 0;
    for (i64 i = 0; i <= raw.len; i++) {
        if (i < raw.len && raw[i] != '&' && raw[i] != ';') { continue; }
        u8[] part = raw[start:i];
        start = i + 1;
        if (!part.len) { continue; }
        i64 eq = indexOf(part, '=');
        Param p;
        bool ok = str.formDecode(eq >= 0 ? part[0:eq] : part, &p.name) &&
                  str.formDecode(eq >= 0 ? part[eq + 1:] : "", &p.value);
        if (!ok) {
            p.name.free();
            p.value.free();
            resetValues(out);
            return false;
        }
        out.items.push(p);
    }
    return true;
}

/* The first value of name ("" if none) */
pub u8[] Values.get(u8[] name) {
    for (i64 i = 0; i < self.items.len; i++) {
        if (str.equals(self.items.data[i].name[0:], name)) { return self.items.data[i].value[0:]; }
    }
    return "";
}

pub bool Values.has(u8[] name) {
    for (i64 i = 0; i < self.items.len; i++) {
        if (str.equals(self.items.data[i].name[0:], name)) { return true; }
    }
    return false;
}

/* name=value added at the end (copies of both) */
pub void Values.add(u8[] name, u8[] value) {
    Param p;
    p.name = str.from(name);
    p.value = str.from(value);
    self.items.push(p);
}

/* As a query string ("a=1&b=two%20words"): a new String, yours */
pub String Values.encode() {
    String out;
    for (i64 i = 0; i < self.items.len; i++) {
        if (i) { out.appendByte('&'); }
        ad String n = str.urlEncode(self.items.data[i].name);
        ad String v = str.urlEncode(self.items.data[i].value);
        out.append(n);
        out.appendByte('=');
        out.append(v);
    }
    return out;
}

/* ---- resolving a link ---- */

/* path with its "." and ".." segments taken out (RFC 3986 5.2.4) */
static String removeDots(u8[] path) {
    vec.Vec<u8[]> segs = vec.new<u8[]>();
    defer segs.free();
    bool abs = path.len && path[0] == '/';
    i64 start = abs ? 1 : 0;
    bool trailing = false;
    for (i64 i = start; i <= path.len; i++) {
        if (i < path.len && path[i] != '/') { continue; }
        u8[] seg = path[start:i];
        start = i + 1;
        trailing = false;
        if (str.equals(seg, ".")) { trailing = true; continue; }
        if (str.equals(seg, "..")) {
            if (segs.len) { segs.pop(); }
            trailing = true;
            continue;
        }
        segs.push(seg);
    }
    String out;
    if (abs) { out.appendByte('/'); }
    for (i64 i = 0; i < segs.len; i++) {
        if (i) { out.appendByte('/'); }
        out.append(segs.data[i]);
    }
    if (trailing && segs.len) { out.appendByte('/'); }
    return out;
}

/* ref (a link found at base: "../c", "/d", "?q", "//other/x",
 * "https://elsewhere/") as an absolute URL, into *out (replaced), as a
 * browser resolves it (RFC 3986 5.2). False when ref doesn't parse. */
pub bool URL.resolve(u8[] ref, URL *out) {
    URL r;
    if (!parse(ref, &r)) {
        reset(out);
        return false;
    }
    reset(out);
    bool hasAuthority = r.hasAuthority;
    if (r.scheme.len) {
        *out = r;
        String p = removeDots(r.path[0:]);
        out.path.free();
        out.path = p;
        return true;
    }
    out.scheme = str.from(self.scheme);
    if (hasAuthority) {
        out.user = r.user; out.password = r.password; out.hasUser = r.hasUser; out.hasPassword = r.hasPassword;
        out.host = r.host; out.port = r.port; out.hasAuthority = true;
        out.path = removeDots(r.path[0:]);
        r.path.free();
        out.rawQuery = r.rawQuery; out.hasQuery = r.hasQuery;
    } else {
        out.user = str.from(self.user); out.password = str.from(self.password);
        out.hasUser = self.hasUser; out.hasPassword = self.hasPassword;
        out.host = str.from(self.host); out.port = self.port; out.hasAuthority = self.hasAuthority;
        r.user.free(); r.password.free(); r.host.free();
        if (!r.path.len) {
            out.path = str.from(self.path);
            if (r.hasQuery) {
                out.rawQuery = r.rawQuery;
                out.hasQuery = true;
            } else {
                out.rawQuery = str.from(self.rawQuery);
                out.hasQuery = self.hasQuery;
                r.rawQuery.free();
            }
            r.path.free();
        } else {
            if (r.path[0] == '/') {
                out.path = removeDots(r.path[0:]);
            } else {
                /* merge: the base path up to its last '/', then ref's */
                String merged;
                if (self.hasAuthority && !self.path.len) {
                    merged.appendByte('/');
                } else {
                    i64 slash = lastIndexOf(self.path[0:], '/');
                    if (slash >= 0) { merged.append(self.path[0:slash + 1]); }
                }
                merged.append(r.path);
                out.path = removeDots(merged[0:]);
                merged.free();
            }
            r.path.free();
            out.rawQuery = r.rawQuery;
            out.hasQuery = r.hasQuery;
        }
    }
    out.fragment = r.fragment;
    out.hasFragment = r.hasFragment;
    r.scheme.free();
    return true;
}
