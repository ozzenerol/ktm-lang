module http;
import date, fs, libc, mem, os, str;

/* mux.files: static files from a directory. The path under the prefix
 * comes unescaped from the route's {path...}; the mux already turned a
 * raw "/../" into a redirect, so only an escaped ".." (%2e%2e) is left to
 * refuse. Symlinks are followed (as Go's http.Dir does). A directory
 * serves its index.html; there are no listings. */

/* content types by extension (lower case); anything else is
 * application/octet-stream */
static u8[][] gFileExts = {
    "html", "htm", "css", "js", "mjs", "json", "map", "txt", "md", "csv", "xml",
    "svg", "png", "jpg", "jpeg", "gif", "webp", "avif", "ico",
    "woff", "woff2", "ttf", "otf", "wasm", "pdf", "zip", "gz",
    "mp4", "webm", "mp3", "ogg", "wav"
};
static u8[][] gFileTypes = {
    "text/html; charset=utf-8", "text/html; charset=utf-8", "text/css; charset=utf-8",
    "text/javascript; charset=utf-8", "text/javascript; charset=utf-8",
    "application/json", "application/json", "text/plain; charset=utf-8",
    "text/markdown; charset=utf-8", "text/csv; charset=utf-8", "text/xml; charset=utf-8",
    "image/svg+xml", "image/png", "image/jpeg", "image/jpeg", "image/gif", "image/webp",
    "image/avif", "image/x-icon",
    "font/woff", "font/woff2", "font/ttf", "font/otf", "application/wasm", "application/pdf",
    "application/zip", "application/gzip",
    "video/mp4", "video/webm", "audio/mpeg", "audio/ogg", "audio/wav"
};

/* The Content-Type for a file name */
pub u8[] contentType(u8[] name)
{
    i64 dot = str.findLast(name, ".");
    if (dot < 0 || dot < str.findLast(name, "/")) { return "application/octet-stream"; }
    u8[] ext = name[dot + 1:];
    for (i64 i = 0; i < gFileExts.len; i++) {
        if (str.equalsFold(ext, gFileExts[i])) { return gFileTypes[i]; }
    }
    return "application/octet-stream";
}

/* rel has no ".." segment and no NUL */
static bool safeRel(u8[] rel)
{
    i64 start = 0;
    for (i64 i = 0; i <= rel.len; i++) {
        if (i < rel.len && rel[i] == 0) { return false; }
        if (i == rel.len || rel[i] == '/') {
            if (i - start == 2 && rel[start] == '.' && rel[start + 1] == '.') { return false; }
            start = i + 1;
        }
    }
    return true;
}

/* GET <prefix>{path...} of a mux.files route */
static void fileServe(Writer *w, Request *r, u8[] dir)
{
    u8[] rel = r.param("path");
    if (!safeRel(rel)) {
        w.error(400, "invalid path");
        return;
    }
    bool slashEnd = rel.len == 0 || rel[rel.len - 1] == '/';
    u8[] index = "index.html";
    u8 *full = r.alloc(dir.len + 1 + rel.len + index.len + 1);
    mem.copy(full, dir.ptr, dir.len);
    i64 at = dir.len;
    if (at == 0 || full[at - 1] != '/') {
        full[at] = '/';
        at++;
    }
    mem.copy(full + at, rel.ptr, rel.len);
    at += rel.len;

    fs.FileStat st;
    if (fs.stat(&st, full[0:at]) != 0) {
        w.error(404, "not found");
        return;
    }
    if (st.isDir()) {
        if (!slashEnd) {
            /* "/static/docs" -> "/static/docs/", as Go's FileServer does */
            u8 *b = r.alloc(r.path.len + 1);
            mem.copy(b, r.path.ptr, r.path.len);
            b[r.path.len] = '/';
            muxRedirect(w, r, b[0:r.path.len + 1]);
            return;
        }
        if (full[at - 1] != '/') {
            full[at] = '/';
            at++;
        }
        mem.copy(full + at, index.ptr, index.len);
        at += index.len;
        if (fs.stat(&st, full[0:at]) != 0 || !st.isFile()) {
            w.error(404, "not found");
            return;
        }
    } else if (!st.isFile()) {
        w.error(404, "not found");
        return;
    }

    i64 mtime = st.st_mtim.tv_sec;
    u8 lm[date.HTTP_DATE_LEN];
    w.header("Last-Modified", lm[0:date.httpDateTo(mtime, lm)]);
    u8[] ims = r.header("If-Modified-Since");
    i64 since = 0;
    if (ims.len && date.parseHttpDate(ims, &since) && mtime <= since) {
        w.status(304);
        return;
    }
    String body = fs.readAll(full[0:at]);
    if (!body.ptr) {
        if (os.errno() == libc.EACCES) {
            w.error(403, "forbidden");
        } else {
            w.error(404, "not found");
        }
        return;
    }
    w.header("Content-Type", contentType(full[0:at]));
    w.write(body.ptr[0:body.len]);
    body.free();
}
