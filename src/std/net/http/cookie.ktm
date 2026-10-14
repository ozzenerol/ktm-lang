module http;
import date, libc, str;

/* Cookies (v0.21.0, RFC 6265): r.cookie(name) reads one the client sent,
 * w.setCookie(&c) sends one.
 *
 *   http.Cookie c;
 *   c.name = "session";
 *   c.value = token;
 *   c.maxAge = 30 * 24 * 3600;     // seconds; < 0 deletes it now
 *   c.httpOnly = true;             // not readable from JavaScript
 *   c.secure = true;               // HTTPS only
 *   c.sameSite = http.SameSite.lax;
 *   w.setCookie(&c);
 *
 * Path defaults to "/". */

pub enum SameSite {
    unset,
    lax,
    strict,
    none
};

pub struct Cookie {
    u8[] name;
    u8[] value;
    u8[] path;          /* "": "/" */
    u8[] domain;        /* "": this host only */
    i64 maxAge;         /* seconds; 0: until the browser closes; < 0: delete now */
    i64 expires;        /* Unix seconds, 0: none (maxAge is better) */
    bool secure;
    bool httpOnly;
    SameSite sameSite;
};

/* RFC 6265's cookie-name: an HTTP token */
static bool cookieName(u8[] s)
{
    if (!s.len) { return false; }
    for (i64 i = 0; i < s.len; i++) {
        u8 c = s[i];
        if (c <= ' ' || c >= 127 || str.find("()<>@,;:\\\"/[]?={}", s[i:i + 1]) >= 0) { return false; }
    }
    return true;
}

/* RFC 6265's cookie-octet: no controls, space, DQUOTE, comma, semicolon or backslash */
static bool cookieValue(u8[] s)
{
    for (i64 i = 0; i < s.len; i++) {
        u8 c = s[i];
        if (c <= ' ' || c >= 127 || c == '"' || c == ',' || c == ';' || c == '\\') { return false; }
    }
    return true;
}

/* An attribute's text (path, domain): no controls and no semicolon */
static bool cookieAttr(u8[] s)
{
    for (i64 i = 0; i < s.len; i++) {
        if (s[i] < ' ' || s[i] == 127 || s[i] == ';') { return false; }
    }
    return true;
}

/* The value of the cookie name the client sent ("" when it sent none;
 * hasCookie tells an empty one apart): a view into the request */
pub u8[] Request.cookie(u8[] name)
{
    u8[] v;
    if (findCookie(self, name, &v)) { return v; }
    return "";
}

pub bool Request.hasCookie(u8[] name)
{
    u8[] v;
    return findCookie(self, name, &v);
}

static bool findCookie(Request *r, u8[] name, u8[] *out)
{
    for (i64 h = 0; h < r.nheaders; h++) {
        u8[] hn = r.headers[h].name;
        if (hn.len != 6 || libc.strncasecmp(hn.ptr, "cookie", 6) != 0) { continue; }
        u8[] rest = r.headers[h].value;
        while (rest.len) {
            i64 semi = str.find(rest, ";");
            u8[] pair = str.trim(semi < 0 ? rest : rest[0:semi]);
            rest = semi < 0 ? rest[rest.len:] : rest[semi + 1:];
            i64 eq = str.find(pair, "=");
            if (eq < 0) { continue; }
            if (!str.equals(str.trim(pair[0:eq]), name)) { continue; }
            u8[] v = str.trim(pair[eq + 1:]);
            if (v.len >= 2 && v[0] == '"' && v[v.len - 1] == '"') { v = v[1:v.len - 1]; }
            *out = v;
            return true;
        }
    }
    return false;
}

/* Adds a Set-Cookie header for c (one per call: several cookies, several
 * calls). Throws ERR_HTTP for a name or value a browser would refuse:
 * encode such values first (base64url, hex). */
pub void Writer.setCookie(Cookie *c)
{
    if (!cookieName(c.name)) { throw(ERR_HTTP, "http: `%s` isn't a cookie name", c.name); }
    if (!cookieValue(c.value)) {
        throw(ERR_HTTP, "http: cookie %s: a value with a space, quote, comma, semicolon or backslash (encode it)", c.name);
    }
    if (!cookieAttr(c.path) || !cookieAttr(c.domain)) {
        throw(ERR_HTTP, "http: cookie %s: a path or domain with a semicolon or control character", c.name);
    }
    String v;
    v.append(c.name);
    v.appendByte('=');
    v.append(c.value);
    v.append("; Path=");
    v.append(c.path.len ? c.path : "/");
    if (c.domain.len) {
        v.append("; Domain=");
        v.append(c.domain);
    }
    if (c.maxAge > 0) {
        v.appendFormat("; Max-Age=%d", c.maxAge);
    } else if (c.maxAge < 0) {
        v.append("; Max-Age=0");
    }
    if (c.expires > 0 || c.maxAge < 0) {
        u8 d[40];
        date.httpDateTo(c.maxAge < 0 ? 0 : c.expires, d);
        v.append("; Expires=");
        v.append(d[0:date.HTTP_DATE_LEN]);
    }
    if (c.httpOnly) { v.append("; HttpOnly"); }
    if (c.secure || c.sameSite == SameSite.none) { v.append("; Secure"); }
    if (c.sameSite == SameSite.lax) { v.append("; SameSite=Lax"); }
    if (c.sameSite == SameSite.strict) { v.append("; SameSite=Strict"); }
    if (c.sameSite == SameSite.none) { v.append("; SameSite=None"); }
    writerHeader(self, "Set-Cookie", v[0:]);
    v.free();
}

/* Tells the client to forget the cookie name (at path, "" for "/") */
pub void Writer.deleteCookie(u8[] name, u8[] path = "")
{
    Cookie c;
    c.name = name;
    c.path = path;
    c.maxAge = -1;
    self.setCookie(&c);
}
