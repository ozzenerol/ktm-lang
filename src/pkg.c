/* Packages: ktm.mod and ktm.sum, and `ktm mod ...`. See pkg.h. */
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "aostr.h"
#include "cli.h"
#include "pkg.h"
#include "version.h"

static char *pkgDup(const char *s, int len) {
    char *d = malloc(len + 1);
    memcpy(d, s, len);
    d[len] = '\0';
    return d;
}

static void *pkgGrow(void *arr, int len, size_t size) {
    /* room for one more: arrays grow by powers of two */
    if ((len & (len - 1)) == 0) {
        arr = realloc(arr, size * (len ? len * 2 : 1));
    }
    return arr;
}

static char *pkgErr(const char *file, int line, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    char *msg = mprintVa(fmt, ap, NULL);
    va_end(ap);
    return line ? mprintf("%s:%d: %s", file, line, msg)
                : mprintf("%s: %s", file, msg);
}

static int pkgPathChar(char c) {
    return isalnum((unsigned char)c) || c == '.' || c == '_' || c == '~' || c == '-';
}

int pkgValidPath(const char *path, int remote) {
    const char *p = path;
    if (!*p) return 0;
    int first = 1, first_dot = 0;
    while (*p) {
        const char *e = p;
        while (*e && *e != '/') {
            if (!pkgPathChar(*e)) return 0;
            if (first && *e == '.') first_dot = 1;
            e++;
        }
        if (e == p || *p == '.') return 0;          /* "", ".", "..", ".x" */
        if (e[-1] == '.') return 0;
        if (*e == '/' && !e[1]) return 0;          /* a trailing slash */
        first = 0;
        p = *e ? e + 1 : e;
    }
    return !remote || first_dot;
}

/* digits with no leading zero; the end, or NULL */
static const char *pkgNum(const char *s, long *out) {
    if (!isdigit((unsigned char)*s)) return NULL;
    if (*s == '0' && isdigit((unsigned char)s[1])) return NULL;
    long n = 0;
    while (isdigit((unsigned char)*s)) {
        if (n > LONG_MAX / 10 - 10) return NULL;
        n = n * 10 + (*s++ - '0');
    }
    *out = n;
    return s;
}

/* major, minor and patch of v, the prerelease after them ("" if none) */
static int pkgSplitVersion(const char *v, long n[3], const char **pre) {
    if (*v != 'v') return 0;
    const char *p = v + 1;
    for (int i = 0; i < 3; ++i) {
        if (!(p = pkgNum(p, &n[i]))) return 0;
        if (i < 2) {
            if (*p != '.') return 0;
            p++;
        }
    }
    if (*p == '-') {
        const char *q = ++p;
        if (!*q) return 0;
        for (; *q; ++q) {
            if (!(isalnum((unsigned char)*q) || *q == '-' || *q == '.')) return 0;
            if (*q == '.' && (q[1] == '.' || !q[1])) return 0;
        }
        *pre = p;
        return 1;
    }
    *pre = "";
    return *p == '\0';
}

int pkgValidVersion(const char *v) {
    long n[3];
    const char *pre;
    return pkgSplitVersion(v, n, &pre);
}

int pkgVersionCmp(const char *a, const char *b) {
    long na[3], nb[3];
    const char *pa, *pb;
    if (!pkgSplitVersion(a, na, &pa) || !pkgSplitVersion(b, nb, &pb)) {
        return strcmp(a, b);
    }
    for (int i = 0; i < 3; ++i) {
        if (na[i] != nb[i]) return na[i] < nb[i] ? -1 : 1;
    }
    /* a release is newer than its prereleases */
    if (!*pa || !*pb) return (!*pa) - (!*pb);
    /* dot-separated identifiers: numbers by value and below words */
    while (*pa && *pb) {
        int la = strcspn(pa, "."), lb = strcspn(pb, ".");
        int da = 1, db = 1;
        for (int i = 0; i < la; ++i) da &= isdigit((unsigned char)pa[i]) != 0;
        for (int i = 0; i < lb; ++i) db &= isdigit((unsigned char)pb[i]) != 0;
        int c;
        if (da && db) {
            c = la != lb ? (la < lb ? -1 : 1) : strncmp(pa, pb, la);
        } else if (da != db) {
            c = da ? -1 : 1;
        } else {
            c = strncmp(pa, pb, la < lb ? la : lb);
            if (!c && la != lb) c = la < lb ? -1 : 1;
        }
        if (c) return c < 0 ? -1 : 1;
        pa += la;
        pb += lb;
        if (*pa) pa++;
        if (*pb) pb++;
    }
    return (*pa != 0) - (*pb != 0);
}

/* The major version of v ("v1" of v1.2.3) */
static char *pkgMajor(const char *v) {
    const char *dot = strchr(v, '.');
    return dot ? pkgDup(v, dot - v) : strdup(v);
}

/* "0.20" from "v0.20.3" */
static char *pkgCompilerKtm(void) {
    const char *v = cctrlGetVersion();
    if (*v == 'v') v++;
    const char *dot = strchr(v, '.');
    const char *end = dot ? strchr(dot + 1, '.') : NULL;
    return end ? pkgDup(v, end - v) : pkgDup(v, strlen(v));
}

static int pkgParseKtm(const char *s, long *maj, long *min) {
    const char *p = pkgNum(s, maj);
    if (!p || *p != '.') return 0;
    p = pkgNum(p + 1, min);
    return p && *p == '\0';
}

int pkgKtmTooNew(const char *ktm) {
    long a, b, ca, cb;
    char *mine = pkgCompilerKtm();
    int ok = pkgParseKtm(ktm, &a, &b) && pkgParseKtm(mine, &ca, &cb);
    free(mine);
    return ok && (a > ca || (a == ca && b > cb));
}

/* ---- ktm.mod ---- */

#define PKG_MAX_FIELDS 8

/* The line's fields (split at blanks, up to a `//` comment); the
 * comment's text in *comment */
static int pkgFields(char *line, char **fields, char **comment) {
    int n = 0;
    *comment = NULL;
    char *p = line;
    while (*p) {
        while (*p == ' ' || *p == '\t' || *p == '\r') p++;
        if (!*p) break;
        if (p[0] == '/' && p[1] == '/') {
            p += 2;
            while (*p == ' ' || *p == '\t') p++;
            *comment = p;
            char *e = p + strlen(p);
            while (e > p && (e[-1] == ' ' || e[-1] == '\t' || e[-1] == '\r')) *--e = '\0';
            break;
        }
        char *start = p;
        while (*p && *p != ' ' && *p != '\t' && *p != '\r') p++;
        if (n == PKG_MAX_FIELDS) return -1;
        fields[n++] = start;
        if (*p) *p++ = '\0';
    }
    return n;
}

static char *pkgModRequire(PkgMod *m, char **f, int n, char *comment, int line) {
    if (n != 2) return pkgErr(m->file, line, "a require is `require <path> <version>`");
    if (!pkgValidPath(f[0], 1)) {
        return pkgErr(m->file, line, "`%s` is not a package path (it starts with a host name, as in github.com/user/repo)", f[0]);
    }
    if (!pkgValidVersion(f[1])) {
        return pkgErr(m->file, line, "`%s` is not a version: versions are vMAJOR.MINOR.PATCH, as in v1.2.0", f[1]);
    }
    for (int i = 0; i < m->req_len; ++i) {
        if (!strcmp(m->req[i].path, f[0])) {
            return pkgErr(m->file, line, "%s is required twice (line %d)", f[0], m->req[i].line);
        }
    }
    m->req = pkgGrow(m->req, m->req_len, sizeof(PkgReq));
    PkgReq *r = &m->req[m->req_len++];
    r->path = strdup(f[0]);
    r->version = strdup(f[1]);
    r->line = line;
    r->indirect = comment && !strcmp(comment, "indirect");
    return NULL;
}

static char *pkgModReplace(PkgMod *m, char **f, int n, int line) {
    /* path [version] => new [version] */
    int arrow = -1;
    for (int i = 0; i < n; ++i) if (!strcmp(f[i], "=>")) arrow = i;
    if (arrow < 1 || arrow > 2 || n - arrow - 1 < 1 || n - arrow - 1 > 2) {
        return pkgErr(m->file, line, "a replace is `replace <path> [version] => <directory>` or `=> <path> <version>`");
    }
    PkgReplace r = {0};
    r.line = line;
    r.path = f[0];
    if (!pkgValidPath(r.path, 1)) return pkgErr(m->file, line, "`%s` is not a package path", r.path);
    if (arrow == 2) {
        if (!pkgValidVersion(f[1])) return pkgErr(m->file, line, "`%s` is not a version", f[1]);
        r.version = strdup(f[1]);
    }
    r.new_path = f[arrow + 1];
    if (n - arrow - 1 == 2) {
        if (!pkgValidPath(r.new_path, 1)) return pkgErr(m->file, line, "`%s` is not a package path", r.new_path);
        if (!pkgValidVersion(f[arrow + 2])) return pkgErr(m->file, line, "`%s` is not a version", f[arrow + 2]);
        r.new_version = strdup(f[arrow + 2]);
    } else if (r.new_path[0] != '.' && r.new_path[0] != '/') {
        return pkgErr(m->file, line, "a replacement directory starts with ./, ../ or /; a package needs a version: `=> %s v1.2.3`", r.new_path);
    }
    for (int i = 0; i < m->rep_len; ++i) {
        PkgReplace *o = &m->rep[i];
        if (!strcmp(o->path, r.path) && (!o->version) == (!r.version) &&
            (!o->version || !strcmp(o->version, r.version))) {
            return pkgErr(m->file, line, "%s is replaced twice (line %d)", r.path, o->line);
        }
    }
    r.path = strdup(r.path);
    r.new_path = strdup(r.new_path);
    m->rep = pkgGrow(m->rep, m->rep_len, sizeof(PkgReplace));
    m->rep[m->rep_len++] = r;
    return NULL;
}

PkgMod *pkgModParse(const char *file, const char *text, char **err) {
    PkgMod *m = calloc(1, sizeof(PkgMod));
    m->file = strdup(file);
    const char *slash = strrchr(file, '/');
    m->dir = slash ? pkgDup(file, slash == file ? 1 : slash - file) : strdup(".");
    char *copy = strdup(text);
    char *f[PKG_MAX_FIELDS], *comment;
    char *block = NULL;         /* "require" or "replace" inside ( ) */
    int line = 0, block_line = 0;
    char *e = NULL;
    /* by hand: strtok would skip empty lines and miscount them */
    char *p = copy;
    while (p && !e) {
        char *nl = strchr(p, '\n');
        if (nl) *nl = '\0';
        line++;
        int n = pkgFields(p, f, &comment);
        p = nl ? nl + 1 : NULL;
        if (n < 0) { e = pkgErr(file, line, "too many words on one line"); break; }
        if (n == 0) continue;
        if (block) {
            if (n == 1 && !strcmp(f[0], ")")) { block = NULL; continue; }
            e = !strcmp(block, "require") ? pkgModRequire(m, f, n, comment, line)
                                          : pkgModReplace(m, f, n, line);
            continue;
        }
        char *kw = f[0];
        if (!strcmp(kw, "module")) {
            if (m->path) { e = pkgErr(file, line, "a second module line"); break; }
            if (n != 2 || !pkgValidPath(f[1], 0)) {
                e = pkgErr(file, line, "the module line is `module <path>`, as in `module github.com/you/app`");
                break;
            }
            m->path = strdup(f[1]);
        } else if (!strcmp(kw, "ktm")) {
            long a, b;
            if (m->ktm) { e = pkgErr(file, line, "a second ktm line"); break; }
            if (n != 2 || !pkgParseKtm(f[1], &a, &b)) {
                e = pkgErr(file, line, "the ktm line is `ktm <major>.<minor>`, as in `ktm 0.20`");
                break;
            }
            m->ktm = strdup(f[1]);
        } else if (!strcmp(kw, "require") || !strcmp(kw, "replace")) {
            if (n == 2 && !strcmp(f[1], "(")) {
                block = !strcmp(kw, "require") ? "require" : "replace";
                block_line = line;
            } else if (!strcmp(kw, "require")) {
                e = pkgModRequire(m, f + 1, n - 1, comment, line);
            } else {
                e = pkgModReplace(m, f + 1, n - 1, line);
            }
        } else {
            e = pkgErr(file, line, "unknown line `%s`: ktm.mod has module, ktm, require and replace lines", kw);
        }
    }
    free(copy);
    if (!e && block) e = pkgErr(file, block_line, "the %s ( block has no closing )", block);
    if (!e && !m->path) e = pkgErr(file, 0, "no module line: the file starts with `module <path>`");
    if (e) {
        *err = e;
        return NULL;
    }
    return m;
}

PkgMod *pkgModLoad(const char *file, char **err) {
    FILE *fp = fopen(file, "r");
    if (!fp) {
        *err = mprintf("%s: %s", file, strerror(errno));
        return NULL;
    }
    AoStr *buf = aoStrNew();
    char chunk[4096];
    size_t n;
    while ((n = fread(chunk, 1, sizeof(chunk), fp)) > 0) aoStrCatLen(buf, chunk, n);
    fclose(fp);
    PkgMod *m = pkgModParse(file, buf->data, err);
    aoStrRelease(buf);
    return m;
}

/* path relative to the current directory when it is under it, for
 * messages */
static char *pkgShortPath(const char *path) {
    char cwd[PATH_MAX];
    if (!getcwd(cwd, sizeof(cwd))) return strdup(path);
    size_t len = strlen(cwd);
    if (!strncmp(path, cwd, len) && path[len] == '/') return strdup(path + len + 1);
    return strdup(path);
}

char *pkgModFind(const char *dir) {
    char *d = realpath(dir, NULL);
    if (!d) return NULL;
    for (;;) {
        char *cand = mprintf("%s/ktm.mod", strcmp(d, "/") ? d : "");
        struct stat st;
        if (stat(cand, &st) == 0 && S_ISREG(st.st_mode)) {
            free(d);
            return pkgShortPath(cand);
        }
        char *slash = strrchr(d, '/');
        if (!slash || !strcmp(d, "/")) break;
        if (slash == d) slash[1] = '\0';
        else *slash = '\0';
    }
    free(d);
    return NULL;
}

char *pkgModFormat(PkgMod *m) {
    AoStr *b = aoStrNew();
    aoStrCatPrintf(b, "module %s\n", m->path);
    if (m->ktm) aoStrCatPrintf(b, "\nktm %s\n", m->ktm);
    if (m->req_len) {
        aoStrCatPrintf(b, "\nrequire (\n");
        for (int i = 0; i < m->req_len; ++i) {
            aoStrCatPrintf(b, "\t%s %s%s\n", m->req[i].path, m->req[i].version,
                           m->req[i].indirect ? " // indirect" : "");
        }
        aoStrCatPrintf(b, ")\n");
    }
    if (m->rep_len) aoStrCatPrintf(b, "\n");
    for (int i = 0; i < m->rep_len; ++i) {
        PkgReplace *r = &m->rep[i];
        aoStrCatPrintf(b, "replace %s%s%s => %s%s%s\n", r->path,
                       r->version ? " " : "", r->version ? r->version : "",
                       r->new_path, r->new_version ? " " : "",
                       r->new_version ? r->new_version : "");
    }
    return aoStrMove(b);
}

static int pkgWriteFile(const char *file, const char *text) {
    char *tmp = mprintf("%s.tmp%d", file, (int)getpid());
    FILE *fp = fopen(tmp, "w");
    if (!fp) return 0;
    size_t len = strlen(text);
    int ok = fwrite(text, 1, len, fp) == len;
    ok = (fclose(fp) == 0) && ok;
    if (ok) ok = rename(tmp, file) == 0;
    if (!ok) unlink(tmp);
    return ok;
}

int pkgModWrite(PkgMod *m) {
    return pkgWriteFile(m->file, pkgModFormat(m));
}

/* ---- ktm.sum ---- */

PkgSum *pkgSumParse(const char *file, const char *text, char **err) {
    PkgSum *s = calloc(1, sizeof(PkgSum));
    char *copy = strdup(text), *p = copy, *f[PKG_MAX_FIELDS], *comment;
    int line = 0;
    while (p) {
        char *nl = strchr(p, '\n');
        if (nl) *nl = '\0';
        line++;
        int n = pkgFields(p, f, &comment);
        p = nl ? nl + 1 : NULL;
        if (n == 0) continue;
        if (n != 3 || !pkgValidPath(f[0], 1) || !pkgValidVersion(f[1]) ||
            strncmp(f[2], "h1:", 3)) {
            *err = pkgErr(file, line, "a line is `<path> <version> h1:<hash>`");
            free(copy);
            return NULL;
        }
        s->lines = pkgGrow(s->lines, s->len, sizeof(PkgSumLine));
        PkgSumLine *l = &s->lines[s->len++];
        l->path = strdup(f[0]);
        l->version = strdup(f[1]);
        l->hash = strdup(f[2]);
    }
    free(copy);
    return s;
}

static int pkgSumLineCmp(const void *a, const void *b) {
    const PkgSumLine *x = a, *y = b;
    int c = strcmp(x->path, y->path);
    return c ? c : pkgVersionCmp(x->version, y->version);
}

char *pkgSumFormat(PkgSum *s) {
    qsort(s->lines, s->len, sizeof(PkgSumLine), pkgSumLineCmp);
    AoStr *b = aoStrNew();
    for (int i = 0; i < s->len; ++i) {
        aoStrCatPrintf(b, "%s %s %s\n", s->lines[i].path, s->lines[i].version, s->lines[i].hash);
    }
    return aoStrMove(b);
}

/* ---- imports ---- */

char *pkgCacheDir(void) {
    char *env = getenv("KTM_CACHE");
    if (env && *env) return mprintf("%s", env);
    char *home = getenv("HOME");
    return mprintf("%s/.cache/ktm/mod", home && *home ? home : "/tmp");
}

static int pkgIsDir(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 && S_ISDIR(st.st_mode);
}

/* path is prefix or below it: the rest ("" or "/sub"), else NULL */
static const char *pkgUnder(const char *path, const char *prefix) {
    size_t n = strlen(prefix);
    if (strncmp(path, prefix, n)) return NULL;
    if (path[n] == '\0' || path[n] == '/') return path + n;
    return NULL;
}

/* ktm.mod of the project, read once per directory */
static PkgMod *pkgModFor(const char *dir, char **err) {
    static char *last_dir = NULL;
    static PkgMod *last_mod = NULL;
    static char *last_err = NULL;
    if (last_dir && !strcmp(last_dir, dir)) {
        if (!last_mod) *err = last_err;
        return last_mod;
    }
    free(last_dir);
    last_dir = strdup(dir);
    last_mod = NULL;
    last_err = NULL;
    char *file = pkgModFind(dir);
    if (!file) {
        last_err = mprintf("no ktm.mod: `ktm mod init <path>` in the project's directory makes one");
    } else {
        last_mod = pkgModLoad(file, &last_err);
    }
    if (!last_mod) *err = last_err;
    return last_mod;
}

/* a directory of a replace: relative to ktm.mod's */
static char *pkgReplaceDir(PkgMod *m, const char *dir) {
    if (dir[0] == '/') return mprintf("%s", dir);
    return mprintf("%s/%s", m->dir, dir);
}

/* ---- the build list (minimal version selection, as Go's) ----
 * Every requirement reachable from the project's ktm.mod through its
 * dependencies' ktm.mod files, the highest version of each path: a
 * dependency always gets at least what it asked for, and nothing newer
 * than someone asked for. One major version per path. */

typedef struct PkgSel {
    char *path;
    char *version;
    char *by;           /* who requires that version: the project, or "path version" */
} PkgSel;

typedef struct PkgList {
    PkgSel *sel;
    int n;
} PkgList;

static PkgReplace *pkgReplaceFor(PkgMod *m, const char *path, const char *version) {
    PkgReplace *rep = NULL;
    for (int i = 0; i < m->rep_len; ++i) {
        PkgReplace *r = &m->rep[i];
        if (strcmp(r->path, path)) continue;
        if (r->version && strcmp(r->version, version)) continue;
        /* a replace of this version wins over one of every version */
        if (!rep || r->version) rep = r;
    }
    return rep;
}

/* Where path@version's files are, as the project sees them: a replace's
 * directory or the cache's (which may not be downloaded yet) */
static char *pkgModuleRoot(PkgMod *m, const char *path, const char *version) {
    PkgReplace *rep = pkgReplaceFor(m, path, version);
    if (rep && !rep->new_version) return pkgReplaceDir(m, rep->new_path);
    if (rep) return mprintf("%s/%s@%s", pkgCacheDir(), rep->new_path, rep->new_version);
    return mprintf("%s/%s@%s", pkgCacheDir(), path, version);
}

static PkgSel *pkgListFind(PkgList *l, const char *path) {
    for (int i = 0; i < l->n; ++i) {
        if (!strcmp(l->sel[i].path, path)) return &l->sel[i];
    }
    return NULL;
}

static int pkgStrIn(char **set, int n, const char *s) {
    for (int i = 0; i < n; ++i) {
        if (!strcmp(set[i], s)) return 1;
    }
    return 0;
}

/* The build list from roots (path, version pairs, required by `by`):
 * NULL with *err on two majors of one path. Lazy (Go 1.17's pruning):
 * only the requirements of the modules in need (those providing a
 * package the build imports, pkgNeeded) are read; any other module is in
 * the list at the version required, its own requirements left out. A
 * needed module not downloaded yet adds nothing (they are unknown). */
static PkgList *pkgBuildListFrom(PkgMod *m, PkgReq *roots, int nroots, char **need, int nneed,
                                 char **err) {
    PkgList *l = calloc(1, sizeof(PkgList));
    typedef struct { char *path, *version, *by; } Node;
    Node *q = NULL;
    int qn = 0, qcap = 0, qi = 0;
    char **seen = NULL;
    int nseen = 0;
#define PKG_PUSH(P, V, B) do { \
        if (qn == qcap) { qcap = qcap ? qcap * 2 : 16; q = realloc(q, sizeof(Node) * qcap); } \
        q[qn].path = (P); q[qn].version = (V); q[qn].by = (B); qn++; } while (0)
    for (int i = 0; i < nroots; ++i) PKG_PUSH(roots[i].path, roots[i].version, m->path);
    while (qi < qn) {
        Node nd = q[qi++];
        if (!strcmp(nd.path, m->path)) continue;
        PkgSel *sel = pkgListFind(l, nd.path);
        if (!sel) {
            l->sel = realloc(l->sel, sizeof(PkgSel) * (l->n + 1));
            sel = &l->sel[l->n++];
            sel->path = nd.path;
            sel->version = nd.version;
            sel->by = nd.by;
        } else if (strcmp(pkgMajor(sel->version), pkgMajor(nd.version))) {
            *err = mprintf("one major version per package: %s requires %s %s, %s requires %s %s",
                           sel->by, sel->path, sel->version, nd.by, nd.path, nd.version);
            return NULL;
        } else if (pkgVersionCmp(nd.version, sel->version) > 0) {
            sel->version = nd.version;
            sel->by = nd.by;
        }
        char *key = mprintf("%s@%s", nd.path, nd.version);
        int done = 0;
        for (int i = 0; i < nseen && !done; ++i) done = !strcmp(seen[i], key);
        if (done) continue;
        seen = realloc(seen, sizeof(char *) * (nseen + 1));
        seen[nseen++] = key;
        if (!pkgStrIn(need, nneed, nd.path)) continue;
        char *file = mprintf("%s/ktm.mod", pkgModuleRoot(m, nd.path, nd.version));
        struct stat st;
        if (stat(file, &st) != 0) continue;
        char *derr = NULL;
        PkgMod *dep = pkgModLoad(file, &derr);
        if (!dep) {
            *err = derr;
            return NULL;
        }
        char *by = mprintf("%s %s", nd.path, nd.version);
        for (int i = 0; i < dep->req_len; ++i) PKG_PUSH(dep->req[i].path, dep->req[i].version, by);
    }
#undef PKG_PUSH
    free(q);
    return l;
}

typedef struct PkgImport {
    char *path;
    char *file;
    int line;
} PkgImport;

static void pkgScanImports(const char *file, PkgImport **out, int *n);
static void pkgScanTree(const char *dir, int top, PkgImport **out, int *n);
static PkgSel *pkgSelFor(PkgList *l, const char *path);
static int pkgFetchOne(PkgMod *m, PkgSum **s, const char *path, const char *version, int recheck,
                       int *changed);

/* The quoted imports of the package in dir: its .ktm files, not its
 * tests nor its subdirectories (other packages) */
static void pkgScanPackage(const char *dir, PkgImport **out, int *n) {
    DIR *d = opendir(dir);
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d))) {
        size_t len = strlen(e->d_name);
        if (e->d_name[0] == '.' || len < 5 || strcmp(e->d_name + len - 4, ".ktm")) continue;
        if (len > 9 && !strcmp(e->d_name + len - 9, "_test.ktm")) continue;
        pkgScanImports(mprintf("%s/%s", dir, e->d_name), out, n);
    }
    closedir(d);
}

/* The modules the build needs (lazy loading, v0.21.0): those providing a
 * package that seeds (import paths: the project's code's, or `ktm get`'s
 * arguments) name, then the packages those import, and so on, with the
 * versions the build list gives them. With download, each is fetched
 * (and checked against ktm.sum, *s loaded when first needed) as it is
 * found, so its packages can be read; 2: one already in the cache is
 * checked again too; 0: one not downloaded ends the search there. The final build list into *_list; NULL (*err) on an
 * error, a failed download reported. */
static int pkgNeeded(PkgMod *m, PkgSum **s, int download, char **seeds, int nseeds,
                     char ***_need, int *_n, PkgList **_list, int *changed, char **err) {
    char **need = NULL;
    int nneed = 0;
    char **pkgs = NULL;
    int npkgs = 0;
    for (int i = 0; i < nseeds; ++i) {
        if (!pkgValidPath(seeds[i], 1) || pkgUnder(seeds[i], m->path) || pkgStrIn(pkgs, npkgs, seeds[i])) continue;
        pkgs = realloc(pkgs, sizeof(char *) * (npkgs + 1));
        pkgs[npkgs++] = seeds[i];
    }
    char **scanned = NULL;      /* "package@version" read */
    int nscanned = 0;
    char **fetched_mods = NULL; /* "module@version" fetched or checked */
    int nfetched = 0;
    PkgList *l = NULL;
    for (int progress = 1; progress;) {
        progress = 0;
        l = pkgBuildListFrom(m, m->req, m->req_len, need, nneed, err);
        if (!l) return 0;
        for (int i = 0; i < npkgs; ++i) {
            PkgSel *sel = pkgSelFor(l, pkgs[i]);
            if (!sel) continue;         /* not required: the compiler says so */
            if (!pkgStrIn(need, nneed, sel->path)) {
                need = realloc(need, sizeof(char *) * (nneed + 1));
                need[nneed++] = sel->path;
                progress = 1;
            }
            char *key = mprintf("%s@%s", pkgs[i], sel->version);
            if (pkgStrIn(scanned, nscanned, key)) continue;
            char *root = pkgModuleRoot(m, sel->path, sel->version);
            char *mkey = mprintf("%s@%s", sel->path, sel->version);
            if (download && !pkgStrIn(fetched_mods, nfetched, mkey)) {
                fetched_mods = realloc(fetched_mods, sizeof(char *) * (nfetched + 1));
                fetched_mods[nfetched++] = mkey;
                int fetched = 0;
                if (!pkgFetchOne(m, s, sel->path, sel->version, download > 1, &fetched)) {
                    *err = NULL;
                    return 0;
                }
                if (fetched) *changed = 1;
            }
            if (!pkgIsDir(root)) continue;
            scanned = realloc(scanned, sizeof(char *) * (nscanned + 1));
            scanned[nscanned++] = key;
            progress = 1;
            PkgImport *imps = NULL;
            int nimp = 0;
            pkgScanPackage(mprintf("%s%s", root, pkgs[i] + strlen(sel->path)), &imps, &nimp);
            for (int j = 0; j < nimp; ++j) {
                const char *p = imps[j].path;
                if (!pkgValidPath(p, 1) || pkgUnder(p, m->path) || pkgStrIn(pkgs, npkgs, p)) continue;
                pkgs = realloc(pkgs, sizeof(char *) * (npkgs + 1));
                pkgs[npkgs++] = imps[j].path;
            }
        }
    }
    if (_list) *_list = l;
    if (_need) *_need = need;
    if (_n) *_n = nneed;
    return 1;
}

/* The quoted imports of the project's code (its tests too) */
static char **pkgProjectImports(PkgMod *m, int *n) {
    PkgImport *imps = NULL;
    int nimp = 0;
    pkgScanTree(m->dir, 1, &imps, &nimp);
    char **out = malloc(sizeof(char *) * (nimp + 1));
    for (int i = 0; i < nimp; ++i) out[i] = imps[i].path;
    *n = nimp;
    return out;
}

/* The modules the project's code needs (*need) and the build list,
 * fetching the missing ones when download; 0 on an error (reported) */
static int pkgNeededHere(PkgMod *m, PkgSum **s, int download, char **extra, int nextra,
                         char ***need, int *nneed, PkgList **l, int *changed) {
    int nseeds = 0;
    char **seeds = pkgProjectImports(m, &nseeds);
    seeds = realloc(seeds, sizeof(char *) * (nseeds + nextra + 1));
    for (int i = 0; i < nextra; ++i) seeds[nseeds++] = extra[i];
    char *err = NULL;
    int c = 0;
    if (!pkgNeeded(m, s, download, seeds, nseeds, need, nneed, l, changed ? changed : &c, &err)) {
        if (err) pkgFail("%s", err);
        return 0;
    }
    return 1;
}

/* The project's build list: what its code needs, from what is downloaded */
static PkgList *pkgBuildList(PkgMod *m, char **err) {
    int nseeds = 0, changed = 0;
    char **seeds = pkgProjectImports(m, &nseeds);
    PkgList *l = NULL;
    if (!pkgNeeded(m, NULL, 0, seeds, nseeds, NULL, NULL, &l, &changed, err)) return NULL;
    return l;
}

/* The build list of the project in dir, made once per program */
static PkgList *pkgBuildListFor(PkgMod *m, char **err) {
    static PkgMod *last_mod = NULL;
    static PkgList *last = NULL;
    static char *last_err = NULL;
    if (last_mod != m) {
        last_mod = m;
        last_err = NULL;
        last = pkgBuildList(m, &last_err);
    }
    if (!last) *err = last_err;
    return last;
}

char *pkgResolveImport(const char *program_dir, const char *path, char **err) {
    if (!pkgValidPath(path, 0)) {
        *err = mprintf("\"%s\" is not a package path, as in \"github.com/user/repo\"", path);
        return NULL;
    }
    PkgMod *m = pkgModFor(program_dir, err);
    if (!m) return NULL;
    if (m->ktm && pkgKtmTooNew(m->ktm)) {
        *err = mprintf("%s needs ktm %s or newer; this is ktm %s", m->file, m->ktm, cctrlGetVersion());
        return NULL;
    }
    char *dir = NULL;
    const char *rest;
    /* a package of the project itself */
    if ((rest = pkgUnder(path, m->path))) {
        dir = mprintf("%s%s", m->dir, rest);
    } else {
        PkgList *l = pkgBuildListFor(m, err);
        if (!l) return NULL;
        PkgSel *best = NULL;
        for (int i = 0; i < l->n; ++i) {
            if (pkgUnder(path, l->sel[i].path) &&
                (!best || strlen(l->sel[i].path) > strlen(best->path))) {
                best = &l->sel[i];
            }
        }
        if (!best) {
            *err = mprintf("\"%s\" is not in ktm.mod: `ktm get %s` adds it", path, path);
            return NULL;
        }
        rest = path + strlen(best->path);
        PkgReplace *rep = pkgReplaceFor(m, best->path, best->version);
        if (rep && !rep->new_version) {
            dir = mprintf("%s%s", pkgReplaceDir(m, rep->new_path), rest);
        } else {
            const char *from = rep ? rep->new_path : best->path;
            const char *version = rep ? rep->new_version : best->version;
            char *root = mprintf("%s/%s@%s", pkgCacheDir(), from, version);
            if (!pkgIsDir(root)) {
                *err = mprintf("%s %s is not downloaded: `ktm mod download` fetches it", from, version);
                return NULL;
            }
            dir = mprintf("%s%s", root, rest);
        }
    }
    if (!pkgIsDir(dir)) {
        *err = mprintf("\"%s\" is not a package: no directory %s", path, dir);
        return NULL;
    }
    return dir;
}

int pkgIsProjectPath(const char *program_dir, const char *path) {
    char *err = NULL;
    PkgMod *m = pkgModFor(program_dir, &err);
    return m && pkgUnder(path, m->path) != NULL;
}

/* skips blanks and comments */
static const char *pkgSkipSpace(const char *p) {
    for (;;) {
        while (isspace((unsigned char)*p)) p++;
        if (p[0] == '/' && p[1] == '/') {
            while (*p && *p != '\n') p++;
        } else if (p[0] == '/' && p[1] == '*') {
            const char *e = strstr(p + 2, "*/");
            p = e ? e + 2 : p + strlen(p);
        } else {
            return p;
        }
    }
}

char *pkgModuleName(const char *dir) {
    DIR *d = opendir(dir);
    if (!d) return NULL;
    char *first = NULL;
    struct dirent *e;
    while ((e = readdir(d))) {
        size_t n = strlen(e->d_name);
        if (n > 4 && !strcmp(e->d_name + n - 4, ".ktm") && e->d_name[0] != '.') {
            if (!first || strcmp(e->d_name, first) < 0) {
                free(first);
                first = strdup(e->d_name);
            }
        }
    }
    closedir(d);
    if (!first) return NULL;
    char *file = mprintf("%s/%s", dir, first);
    free(first);
    FILE *fp = fopen(file, "r");
    if (!fp) return NULL;
    char buf[4096];
    size_t len = fread(buf, 1, sizeof(buf) - 1, fp);
    fclose(fp);
    buf[len] = '\0';
    const char *p = pkgSkipSpace(buf);
    if (strncmp(p, "module", 6) || !isspace((unsigned char)p[6])) return NULL;
    p = pkgSkipSpace(p + 6);
    const char *start = p;
    while (isalnum((unsigned char)*p) || *p == '_') p++;
    if (p == start || isdigit((unsigned char)*start)) return NULL;
    return pkgDup(start, p - start);
}

/* ---- commands ---- */

int pkgFail(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "ktm: ");
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
    return 1;
}

static int pkgModInit(int argc, char **argv) {
    if (argc > 1) return pkgFail("usage: ktm mod init [module path]");
    struct stat st;
    if (stat("ktm.mod", &st) == 0) return pkgFail("ktm.mod already exists here");
    char *path = argc == 1 ? argv[0] : NULL;
    if (!path) {
        char cwd[PATH_MAX];
        if (!getcwd(cwd, sizeof(cwd))) return pkgFail("can't read the current directory: %s", strerror(errno));
        char *base = strrchr(cwd, '/');
        path = strdup(base && base[1] ? base + 1 : cwd);
    }
    if (!pkgValidPath(path, 0)) {
        return pkgFail("`%s` is not a module path: use letters, digits, . _ ~ - and / (as in github.com/you/app)", path);
    }
    PkgMod m = {0};
    m.file = "ktm.mod";
    m.path = path;
    m.ktm = pkgCompilerKtm();
    if (!pkgModWrite(&m)) return pkgFail("can't write ktm.mod: %s", strerror(errno));
    fprintf(stderr, "ktm: created ktm.mod for %s\n", path);
    return 0;
}

/* Finds the project's ktm.mod from the current directory */
static PkgMod *pkgModHere(int *rc) {
    char *file = pkgModFind(".");
    if (!file) {
        *rc = pkgFail("no ktm.mod here or in a directory above: `ktm mod init <path>` makes one");
        return NULL;
    }
    char *err = NULL;
    PkgMod *m = pkgModLoad(file, &err);
    if (!m) *rc = pkgFail("%s", err);
    return m;
}

/* Checks ktm.mod and rewrites it in the canonical layout */
static int pkgModFmt(int argc) {
    if (argc) return pkgFail("usage: ktm mod fmt");
    int rc = 0;
    PkgMod *m = pkgModHere(&rc);
    if (!m) return rc;
    if (!pkgModWrite(m)) return pkgFail("can't write %s: %s", m->file, strerror(errno));
    return 0;
}

/* ---- ktm get, ktm mod download ---- */

static PkgSum *pkgSumLoad(PkgMod *m, char **err) {
    char *file = mprintf("%s/ktm.sum", m->dir);
    FILE *fp = fopen(file, "r");
    if (!fp) return calloc(1, sizeof(PkgSum));
    AoStr *buf = aoStrNew();
    char chunk[4096];
    size_t n;
    while ((n = fread(chunk, 1, sizeof(chunk), fp)) > 0) aoStrCatLen(buf, chunk, n);
    fclose(fp);
    return pkgSumParse(file, buf->data, err);
}

static int pkgSumWrite(PkgMod *m, PkgSum *s) {
    return pkgWriteFile(mprintf("%s/ktm.sum", m->dir), pkgSumFormat(s));
}

/* Records path@version's hash; 0 (reported) when ktm.sum has another */
static int pkgSumCheck(PkgSum *s, const char *path, const char *version, const char *hash) {
    for (int i = 0; i < s->len; ++i) {
        PkgSumLine *l = &s->lines[i];
        if (strcmp(l->path, path) || strcmp(l->version, version)) continue;
        if (!strcmp(l->hash, hash)) return 1;
        pkgFail("%s %s doesn't match ktm.sum: downloaded %s, ktm.sum has %s.\n"
                "  The version's files changed since it was recorded (a moved tag, or someone\n"
                "  tampering): refused. Check the repository; delete the line to accept it.",
                path, version, hash, l->hash);
        return 0;
    }
    s->lines = realloc(s->lines, sizeof(PkgSumLine) * (s->len + 1));
    PkgSumLine *l = &s->lines[s->len++];
    l->path = strdup(path);
    l->version = strdup(version);
    l->hash = strdup(hash);
    return 1;
}

static PkgReq *pkgModReq(PkgMod *m, const char *path) {
    for (int i = 0; i < m->req_len; ++i) {
        if (!strcmp(m->req[i].path, path)) return &m->req[i];
    }
    return NULL;
}

/* Sets path's version in ktm.mod (added when new) */
static void pkgModSetReq(PkgMod *m, const char *path, const char *version, int indirect) {
    PkgReq *r = pkgModReq(m, path);
    if (!r) {
        m->req = realloc(m->req, sizeof(PkgReq) * (m->req_len + 1));
        r = &m->req[m->req_len++];
        memset(r, 0, sizeof(*r));
        r->path = strdup(path);
    }
    r->version = strdup(version);
    r->indirect = indirect;
}

static void pkgModDropReq(PkgMod *m, const char *path) {
    for (int i = 0; i < m->req_len; ++i) {
        if (strcmp(m->req[i].path, path)) continue;
        memmove(&m->req[i], &m->req[i + 1], sizeof(PkgReq) * (m->req_len - i - 1));
        m->req_len--;
        return;
    }
}


/* path@version (or what replaces it, nothing for a local directory) into
 * the cache if it isn't there, its hash checked against ktm.sum (*s,
 * loaded now if NULL) and recorded if new; recheck: a cached one is
 * hashed and checked again too. *changed when ktm.sum got a line. 0 on
 * an error (reported). */
static int pkgFetchOne(PkgMod *m, PkgSum **s, const char *path, const char *version, int recheck,
                       int *changed) {
    PkgReplace *rep = pkgReplaceFor(m, path, version);
    if (rep && !rep->new_version) return 1;
    const char *from = rep ? rep->new_path : path;
    const char *ver = rep ? rep->new_version : version;
    char *err = NULL;
    if (!*s && !(*s = pkgSumLoad(m, &err))) return pkgFail("%s", err) == 0;
    int summed = 0;
    for (int i = 0; i < (*s)->len && !summed; ++i) {
        summed = !strcmp((*s)->lines[i].path, from) && !strcmp((*s)->lines[i].version, ver);
    }
    int cached = pkgIsDir(mprintf("%s/%s@%s", pkgCacheDir(), from, ver));
    if (cached && summed && !recheck) return 1;
    if (!cached) fprintf(stderr, "ktm: downloading %s %s\n", from, ver);
    char *hash = pkgDownload(from, ver, &err);
    if (!hash) return pkgFail("%s", err) == 0;
    if (!pkgSumCheck(*s, from, ver, hash)) return 0;
    if (!summed) *changed = 1;
    return 1;
}

static void pkgSyncReqs(PkgMod *m, PkgList *l, char **need, int nneed);

static int pkgGet(int argc, char **argv) {
    if (argc < 1) return pkgFail("usage: ktm get <path>[@version|@latest|@none] ...");
    int rc = 0;
    PkgMod *m = pkgModHere(&rc);
    if (!m) return rc;
    char *err = NULL;
    PkgSum *s = pkgSumLoad(m, &err);
    if (!s) return pkgFail("%s", err);
    PkgReq *done = NULL;
    int ndone = 0, fetched = 0;
    /* the packages named: what the build will import from them */
    char **named = malloc(sizeof(char *) * (argc + 1));
    int nnamed = 0;
    for (int i = 0; i < argc; ++i) {
        char *arg = mprintf("%s", argv[i]);
        char *at = strchr(arg, '@');
        char *want = at ? at + 1 : "latest";
        if (at) *at = '\0';
        if (!pkgValidPath(arg, 1)) {
            return pkgFail("`%s` is not a package path: it starts with a host, as in github.com/user/repo", arg);
        }
        char *path = pkgRepoRoot(arg);
        PkgReq *had = pkgModReq(m, path);
        char *old = had ? strdup(had->version) : NULL;
        if (!strcmp(want, "none")) {
            if (!had) return pkgFail("%s is not in ktm.mod", path);
            pkgModDropReq(m, path);
            fprintf(stderr, "ktm: removed %s %s\n", path, old);
            continue;
        }
        char *version = want;
        if (!strcmp(want, "latest")) {
            version = pkgLatest(path, &err);
            if (!version) return pkgFail("%s", err);
        } else if (!pkgValidVersion(want)) {
            return pkgFail("`%s` is not a version: vMAJOR.MINOR.PATCH, latest or none", want);
        }
        if (old && strcmp(pkgMajor(old), pkgMajor(version))) {
            fprintf(stderr, "ktm: %s goes from %s to %s, a new major version: its API may have changed\n",
                    path, old, version);
        }
        pkgModSetReq(m, path, version, 0);
        if (!pkgFetchOne(m, &s, path, version, 1, &fetched)) return 1;
        named[nnamed++] = arg;
        done = realloc(done, sizeof(PkgReq) * (ndone + 1));
        done[ndone].path = path;
        done[ndone].version = old;      /* what it was */
        ndone++;
    }
    /* what those packages need, fetched; only that goes into ktm.mod */
    char **need = NULL;
    int nneed = 0;
    PkgList *l = NULL;
    if (!pkgNeededHere(m, &s, 2, named, nnamed, &need, &nneed, &l, &fetched)) return 1;
    pkgSyncReqs(m, l, need, nneed);
    /* what changed, as the build will use it */
    for (int i = 0; i < ndone; ++i) {
        PkgReq *now = pkgModReq(m, done[i].path);
        if (!now) continue;
        if (!done[i].version) fprintf(stderr, "ktm: added %s %s\n", now->path, now->version);
        else if (strcmp(done[i].version, now->version)) {
            fprintf(stderr, "ktm: %s %s -> %s\n", now->path, done[i].version, now->version);
        }
    }
    if (!pkgModWrite(m)) return pkgFail("can't write %s: %s", m->file, strerror(errno));
    if (!pkgSumWrite(m, s)) return pkgFail("can't write ktm.sum: %s", strerror(errno));
    return 0;
}

/* What the build needs into the cache, each checked against ktm.sum: the
 * packages the code imports, and those of the direct requirements */
static int pkgModDownload(int argc) {
    if (argc) return pkgFail("usage: ktm mod download");
    int rc = 0;
    PkgMod *m = pkgModHere(&rc);
    if (!m) return rc;
    char **direct = malloc(sizeof(char *) * (m->req_len + 1));
    int ndirect = 0, changed = 0;
    for (int i = 0; i < m->req_len; ++i) {
        if (!m->req[i].indirect) direct[ndirect++] = m->req[i].path;
    }
    PkgSum *s = NULL;
    if (!pkgNeededHere(m, &s, 2, direct, ndirect, NULL, NULL, NULL, &changed)) return 1;
    if (changed && !pkgSumWrite(m, s)) return pkgFail("can't write ktm.sum: %s", strerror(errno));
    return 0;
}

/* ktm.mod's requirements raised to what the build uses (l): a dependency
 * that needs a newer version of something gets it (a downgrade below
 * that is noted, not done); a needed module ktm.mod doesn't list is
 * added, `// indirect`. A module no imported package needs isn't. */
static void pkgSyncReqs(PkgMod *m, PkgList *l, char **need, int nneed) {
    for (int i = 0; i < m->req_len; ++i) {
        PkgSel *sel = pkgListFind(l, m->req[i].path);
        if (sel && pkgVersionCmp(sel->version, m->req[i].version) > 0) {
            fprintf(stderr, "ktm: %s stays at %s: %s requires it\n", m->req[i].path, sel->version, sel->by);
            m->req[i].version = strdup(sel->version);
        }
    }
    for (int i = 0; i < nneed; ++i) {
        PkgSel *sel = pkgListFind(l, need[i]);
        if (sel && !pkgModReq(m, sel->path)) pkgModSetReq(m, sel->path, sel->version, 1);
    }
}

/* ---- ktm mod tidy, why, list, verify ---- */

/* The quoted imports of a ktm source: `import "a/b";`, `import x = "a";`,
 * lists. Comments and other strings are skipped. */
static void pkgScanImports(const char *file, PkgImport **out, int *n) {
    FILE *fp = fopen(file, "r");
    if (!fp) return;
    AoStr *b = aoStrNew();
    char chunk[4096];
    size_t k;
    while ((k = fread(chunk, 1, sizeof(chunk), fp)) > 0) aoStrCatLen(b, chunk, k);
    fclose(fp);
    const char *p = b->data;
    int line = 1, in_import = 0, stmt_start = 1;
    while (*p) {
        if (*p == '\n') { line++; p++; continue; }
        if (p[0] == '/' && p[1] == '/') { while (*p && *p != '\n') p++; continue; }
        if (p[0] == '/' && p[1] == '*') {
            p += 2;
            while (*p && !(p[0] == '*' && p[1] == '/')) { if (*p == '\n') line++; p++; }
            if (*p) p += 2;
            continue;
        }
        if (*p == '"' || *p == '\'') {
            char q = *p++;
            const char *st = p;
            while (*p && *p != q) { if (*p == '\\' && p[1]) p++; if (*p == '\n') line++; p++; }
            if (in_import && q == '"') {
                *out = realloc(*out, sizeof(PkgImport) * (*n + 1));
                (*out)[*n].path = pkgDup(st, (int)(p - st));
                (*out)[*n].file = strdup(file);
                (*out)[*n].line = line;
                (*n)++;
            }
            if (*p) p++;
            stmt_start = 0;
            continue;
        }
        if (isspace((unsigned char)*p)) { p++; continue; }
        if (stmt_start && !strncmp(p, "import", 6) && !isalnum((unsigned char)p[6]) && p[6] != '_') {
            in_import = 1;
            p += 6;
            stmt_start = 0;
            continue;
        }
        if (*p == ';' || *p == '{' || *p == '}') {
            in_import = 0;
            stmt_start = 1;
            p++;
            continue;
        }
        if (*p == '#') {           /* a directive: to the end of the line */
            while (*p && *p != '\n') p++;
            continue;
        }
        stmt_start = 0;
        p++;
    }
    aoStrRelease(b);
}

/* Every .ktm file under dir (not hidden ones, nor another project's: a
 * directory with its own ktm.mod) */
static void pkgScanTree(const char *dir, int top, PkgImport **out, int *n) {
    if (!top) {
        struct stat st;
        if (stat(mprintf("%s/ktm.mod", dir), &st) == 0) return;
    }
    DIR *d = opendir(dir);
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.') continue;
        char *path = mprintf("%s/%s", dir, e->d_name);
        struct stat st;
        if (stat(path, &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) {
            pkgScanTree(path, 0, out, n);
        } else {
            size_t len = strlen(e->d_name);
            if (len > 4 && !strcmp(e->d_name + len - 4, ".ktm")) pkgScanImports(path, out, n);
        }
    }
    closedir(d);
}

/* The requirement (from the build list) a package path falls under */
static PkgSel *pkgSelFor(PkgList *l, const char *path) {
    PkgSel *best = NULL;
    for (int i = 0; l && i < l->n; ++i) {
        if (pkgUnder(path, l->sel[i].path) && (!best || strlen(l->sel[i].path) > strlen(best->path))) {
            best = &l->sel[i];
        }
    }
    return best;
}

static int pkgReqCmp(const void *a, const void *b) {
    const PkgReq *x = a, *y = b;
    if (x->indirect != y->indirect) return x->indirect - y->indirect;
    return strcmp(x->path, y->path);
}

/* Makes ktm.mod and ktm.sum match the code: requires what it imports
 * (fetching the newest version of a new one), marks what only
 * dependencies need indirect, drops what nothing needs */
static int pkgModTidy(int argc) {
    if (argc) return pkgFail("usage: ktm mod tidy");
    int rc = 0;
    PkgMod *m = pkgModHere(&rc);
    if (!m) return rc;
    char *err = NULL;
    PkgSum *s = pkgSumLoad(m, &err);
    if (!s) return pkgFail("%s", err);
    PkgImport *imps = NULL;
    int nimp = 0;
    pkgScanTree(m->dir, 1, &imps, &nimp);
    PkgList *l = pkgBuildList(m, &err);
    if (!l) return pkgFail("%s", err);
    /* the modules the code imports directly */
    PkgReq *direct = NULL;
    int ndirect = 0;
    for (int i = 0; i < nimp; ++i) {
        const char *path = imps[i].path;
        if (!pkgValidPath(path, 0) || pkgUnder(path, m->path)) continue;
        if (!pkgValidPath(path, 1)) {
            fprintf(stderr, "ktm: %s:%d: \"%s\" is not a package path\n", imps[i].file, imps[i].line, path);
            continue;
        }
        PkgSel *sel = pkgSelFor(l, path);
        char *mod = sel ? sel->path : pkgRepoRoot(path);
        char *ver = sel ? sel->version : NULL;
        int dup = 0;
        for (int j = 0; j < ndirect && !dup; ++j) dup = !strcmp(direct[j].path, mod);
        if (dup) continue;
        if (!ver) {
            ver = pkgLatest(mod, &err);
            if (!ver) return pkgFail("%s:%d: %s", imps[i].file, imps[i].line, err);
            fprintf(stderr, "ktm: added %s %s\n", mod, ver);
        }
        direct = realloc(direct, sizeof(PkgReq) * (ndirect + 1));
        memset(&direct[ndirect], 0, sizeof(PkgReq));
        direct[ndirect].path = mod;
        direct[ndirect].version = ver;
        ndirect++;
    }
    /* the imported modules at those versions (the indirect lines kept
     * while the build list is worked out, so no version goes down), then
     * what their packages need, fetched */
    PkgMod tmp = *m;
    tmp.req = NULL;
    tmp.req_len = 0;
    for (int j = 0; j < ndirect; ++j) pkgModSetReq(&tmp, direct[j].path, direct[j].version, 0);
    for (int i = 0; i < m->req_len; ++i) {
        if (!pkgModReq(&tmp, m->req[i].path)) pkgModSetReq(&tmp, m->req[i].path, m->req[i].version, 1);
    }
    char **need = NULL;
    int nneed = 0, changed = 0;
    PkgList *nl = NULL;
    if (!pkgNeededHere(&tmp, &s, 2, NULL, 0, &need, &nneed, &nl, &changed)) return 1;
    PkgReq *old = m->req;
    int nold = m->req_len;
    m->req = NULL;
    m->req_len = 0;
    for (int i = 0; i < nneed; ++i) {
        PkgSel *sel = pkgListFind(nl, need[i]);
        if (!sel) continue;
        int is_direct = 0;
        for (int j = 0; j < ndirect && !is_direct; ++j) is_direct = !strcmp(direct[j].path, sel->path);
        pkgModSetReq(m, sel->path, sel->version, !is_direct);
    }
    for (int i = 0; i < nold; ++i) {
        if (!pkgModReq(m, old[i].path)) {
            fprintf(stderr, "ktm: dropped %s %s (no imported package needs it)\n", old[i].path, old[i].version);
        }
    }
    /* what the code imports first, then the indirect ones, each by path */
    qsort(m->req, m->req_len, sizeof(PkgReq), pkgReqCmp);
    /* ktm.sum: the versions in use */
    PkgSum kept = {0};
    for (int i = 0; i < s->len; ++i) {
        PkgReq *r = pkgModReq(m, s->lines[i].path);
        int used = r && !strcmp(r->version, s->lines[i].version);
        for (int k = 0; !used && k < m->rep_len; ++k) {
            PkgReplace *rp = &m->rep[k];
            used = rp->new_version && !strcmp(rp->new_path, s->lines[i].path) &&
                   !strcmp(rp->new_version, s->lines[i].version);
        }
        if (!used) continue;
        kept.lines = realloc(kept.lines, sizeof(PkgSumLine) * (kept.len + 1));
        kept.lines[kept.len++] = s->lines[i];
    }
    if (!pkgModWrite(m)) return pkgFail("can't write %s: %s", m->file, strerror(errno));
    if (!pkgSumWrite(m, &kept)) return pkgFail("can't write ktm.sum: %s", strerror(errno));
    return 0;
}

/* Why the project needs path: who imports it, who requires it */
static int pkgModWhy(int argc, char **argv) {
    if (argc != 1) return pkgFail("usage: ktm mod why <path>");
    int rc = 0;
    PkgMod *m = pkgModHere(&rc);
    if (!m) return rc;
    char **need = NULL;
    int nneed = 0;
    PkgList *l = NULL;
    if (!pkgNeededHere(m, NULL, 0, NULL, 0, &need, &nneed, &l, NULL)) return 1;
    const char *path = argv[0];
    PkgImport *imps = NULL;
    int nimp = 0, found = 0;
    pkgScanTree(m->dir, 1, &imps, &nimp);
    for (int i = 0; i < nimp; ++i) {
        PkgSel *sel = pkgSelFor(l, imps[i].path);
        if (!strcmp(imps[i].path, path) || (sel && !strcmp(sel->path, path))) {
            printf("%s:%d imports \"%s\"\n", pkgShortPath(imps[i].file), imps[i].line, imps[i].path);
            found = 1;
        }
    }
    PkgSel *sel = pkgListFind(l, path);
    if (sel) {
        /* the chain of requirements down from the project */
        printf("%s %s", sel->path, sel->version);
        PkgSel *at = sel;
        for (int hops = 0; hops < 64 && strcmp(at->by, m->path); ++hops) {
            printf(" <- %s", at->by);
            char *by = strdup(at->by);
            char *sp = strchr(by, ' ');
            if (sp) *sp = '\0';
            at = pkgListFind(l, by);
            if (!at) break;
        }
        printf(" <- %s (ktm.mod)\n", m->path);
        if (!pkgStrIn(need, nneed, sel->path)) {
            printf("but no package the project imports needs it: it isn't downloaded or built\n");
        }
        found = 1;
    }
    if (!found) printf("%s isn't needed: nothing imports or requires it\n", path);
    return 0;
}

/* The build list: what a build of the project uses (the modules its
 * imported packages need) */
static int pkgModList(int argc) {
    if (argc) return pkgFail("usage: ktm mod list");
    int rc = 0;
    PkgMod *m = pkgModHere(&rc);
    if (!m) return rc;
    char **need = NULL;
    int nneed = 0;
    PkgList *l = NULL;
    if (!pkgNeededHere(m, NULL, 0, NULL, 0, &need, &nneed, &l, NULL)) return 1;
    printf("%s\n", m->path);
    for (int i = 0; i < l->n; ++i) {
        if (!pkgStrIn(need, nneed, l->sel[i].path)) continue;
        PkgReplace *rep = pkgReplaceFor(m, l->sel[i].path, l->sel[i].version);
        printf("%s %s", l->sel[i].path, l->sel[i].version);
        if (rep) printf(" => %s%s%s", rep->new_path, rep->new_version ? " " : "", rep->new_version ? rep->new_version : "");
        printf("\n");
    }
    return 0;
}

/* Checks every package the build needs against ktm.sum */
static int pkgModVerify(int argc) {
    if (argc) return pkgFail("usage: ktm mod verify");
    int rc = 0;
    PkgMod *m = pkgModHere(&rc);
    if (!m) return rc;
    char *err = NULL;
    PkgSum *s = pkgSumLoad(m, &err);
    if (!s) return pkgFail("%s", err);
    char **need = NULL;
    int nneed = 0;
    PkgList *l = NULL;
    if (!pkgNeededHere(m, NULL, 0, NULL, 0, &need, &nneed, &l, NULL)) return 1;
    int bad = 0;
    for (int i = 0; i < l->n; ++i) {
        if (!pkgStrIn(need, nneed, l->sel[i].path)) continue;
        PkgReplace *rep = pkgReplaceFor(m, l->sel[i].path, l->sel[i].version);
        if (rep && !rep->new_version) continue;          /* a local directory */
        const char *path = rep ? rep->new_path : l->sel[i].path;
        const char *ver = rep ? rep->new_version : l->sel[i].version;
        char *dir = mprintf("%s/%s@%s", pkgCacheDir(), path, ver);
        if (!pkgIsDir(dir)) {
            printf("%s %s: not downloaded\n", path, ver);
            bad = 1;
            continue;
        }
        char *h = pkgDirHash(dir);
        const char *want = NULL;
        for (int j = 0; j < s->len; ++j) {
            if (!strcmp(s->lines[j].path, path) && !strcmp(s->lines[j].version, ver)) want = s->lines[j].hash;
        }
        if (!want) { printf("%s %s: not in ktm.sum\n", path, ver); bad = 1; }
        else if (!h || strcmp(h, want)) { printf("%s %s: CHANGED since it was downloaded\n", path, ver); bad = 1; }
    }
    if (!bad) printf("all packages verified\n");
    return bad;
}

static int pkgModUsage(void) {
    fprintf(stderr,
            "usage: ktm mod <command>\n"
            "  init [path]   create ktm.mod for a new project in this directory\n"
            "  fmt           check ktm.mod and rewrite it in the standard layout\n"
            "  download      fetch what the build needs into the cache\n"
            "  tidy          make ktm.mod and ktm.sum match the imports\n"
            "  why <path>    who imports and who requires a package\n"
            "  list          the packages and versions a build uses\n"
            "  verify        check the downloaded packages against ktm.sum\n");
    return 2;
}

static int pkgModCommand(int argc, char **argv) {
    if (argc < 1) return pkgModUsage();
    if (!strcmp(argv[0], "init")) return pkgModInit(argc - 1, argv + 1);
    if (!strcmp(argv[0], "fmt")) return pkgModFmt(argc - 1);
    if (!strcmp(argv[0], "download")) return pkgModDownload(argc - 1);
    if (!strcmp(argv[0], "tidy")) return pkgModTidy(argc - 1);
    if (!strcmp(argv[0], "why")) return pkgModWhy(argc - 1, argv + 1);
    if (!strcmp(argv[0], "list")) return pkgModList(argc - 1);
    if (!strcmp(argv[0], "verify")) return pkgModVerify(argc - 1);
    if (!strcmp(argv[0], "help") || !strcmp(argv[0], "--help")) {
        pkgModUsage();
        return 0;
    }
    fprintf(stderr, "ktm: unknown command `ktm mod %s`\n", argv[0]);
    return pkgModUsage();
}

/* Every package the build list needs, downloaded (and checked against
 * ktm.sum) when it isn't yet; quiet when there's nothing to do. 0 on an
 * error (reported). */
int pkgEnsureDownloaded(PkgMod *m) {
    PkgSum *s = NULL;
    int changed = 0;
    if (!pkgNeededHere(m, &s, 1, NULL, 0, NULL, NULL, NULL, &changed)) return 0;
    if (changed && !pkgSumWrite(m, s)) return pkgFail("can't write ktm.sum: %s", strerror(errno)) == 0;
    return 1;
}

/* `ktm build [flags] [dir] [flags]` and `ktm run [flags] [dir] [args]`
 * (flags: the compiler's, as -Werror, -o name, --install-dir=...): dir (default the
 * current one) holds main.ktm; its project's packages are downloaded if
 * they aren't, then it compiles as `ktm dir/main.ktm -o dir/<name> [flags]`
 * (name: the module path's last element, or the directory's) or runs as
 * `ktm -run dir/main.ktm [args]`. */
static int pkgBuildOrRun(int run, int argc, char **argv, int *new_argc, char ***new_argv) {
    const char *dir = ".";
    struct stat st;
    /* compiler flags first (-o takes a value), then the directory */
    int nflags = 0;
    while (nflags < argc && argv[nflags][0] == '-') {
        if (cliOptionTakesValue(argv[nflags]) && nflags + 1 < argc) nflags++;
        nflags++;
    }
    int at = nflags;
    if (at < argc && stat(argv[at], &st) == 0 && S_ISDIR(st.st_mode)) {
        dir = argv[at];
        at++;
    }
    char *main_file = mprintf("%s/main.ktm", dir);
    if (stat(main_file, &st) != 0) {
        return pkgFail("no main.ktm in %s: `ktm %s` builds the program whose main.ktm is there",
                       dir, run ? "run" : "build");
    }
    char *mod_file = pkgModFind(dir);
    char *name = NULL;
    if (mod_file) {
        char *err = NULL;
        PkgMod *m = pkgModLoad(mod_file, &err);
        if (!m) return pkgFail("%s", err);
        if (!pkgEnsureDownloaded(m)) return 1;
        char *real_dir = realpath(dir, NULL), *real_mod = realpath(m->dir, NULL);
        if (real_dir && real_mod && !strcmp(real_dir, real_mod)) {
            const char *slash = strrchr(m->path, '/');
            name = mprintf("%s", slash ? slash + 1 : m->path);
        }
        free(real_dir);
        free(real_mod);
    }
    if (!name) {
        char *real_dir = realpath(dir, NULL);
        const char *slash = real_dir ? strrchr(real_dir, '/') : NULL;
        name = mprintf("%s", slash && slash[1] ? slash + 1 : "a.out");
        free(real_dir);
    }
    char **out = calloc(argc + 8, sizeof(char *));
    int n = 0;
    out[n++] = "ktm";
    for (int i = 0; i < nflags; ++i) out[n++] = argv[i];
    if (run) {
        /* what follows the directory is the program's */
        out[n++] = "-run";
        out[n++] = main_file;
        for (int i = at; i < argc; ++i) out[n++] = argv[i];
    } else {
        int has_o = 0;
        for (int i = 0; i < argc; ++i) has_o |= !strcmp(argv[i], "-o");
        for (int i = at; i < argc; ++i) out[n++] = argv[i];
        out[n++] = main_file;
        if (!has_o) {
            /* next to main.ktm, where `ktm build` in that directory puts it */
            out[n++] = "-o";
            out[n++] = strcmp(dir, ".") ? mprintf("%s/%s", dir, name) : name;
        }
    }
    out[n] = NULL;
    *new_argc = n;
    *new_argv = out;
    return -2;
}

static int pkgIsCommand(const char *s) {
    return !strcmp(s, "build") || !strcmp(s, "run") || !strcmp(s, "mod") ||
           !strcmp(s, "get") || !strcmp(s, "test");
}

/* `ktm --install-dir=X mod init`: options before the command. They move
 * after it: build, run and test take the compiler's options there; mod
 * and get take none, and only --install-dir (which they don't need) is
 * let through, dropped. NULL when argv has no options before a command;
 * an error message in *err when one can't be used. */
static char **pkgOptionsFirst(int argc, char **argv, int *new_argc, char **err) {
    int k = 1;
    while (k < argc && argv[k][0] == '-' && strcmp(argv[k], "--")) {
        k += cliOptionTakesValue(argv[k]) && k + 1 < argc ? 2 : 1;
    }
    if (k == 1 || k >= argc || !pkgIsCommand(argv[k])) return NULL;
    int bare = !strcmp(argv[k], "mod") || !strcmp(argv[k], "get");
    char **out = calloc(argc + 1, sizeof(char *));
    int n = 0;
    out[n++] = argv[0];
    out[n++] = argv[k];
    for (int i = 1; i < k; ++i) {
        int with_value = cliOptionTakesValue(argv[i]) && i + 1 < k;
        if (bare) {
            if (strcmp(argv[i], "--install-dir") && strncmp(argv[i], "--install-dir=", 14)) {
                *err = mprintf("`ktm %s` doesn't take %s", argv[k], argv[i]);
                return NULL;
            }
        } else {
            out[n++] = argv[i];
            if (with_value) out[n++] = argv[i + 1];
        }
        if (with_value) i++;
    }
    for (int i = k + 1; i < argc; ++i) out[n++] = argv[i];
    out[n] = NULL;
    *new_argc = n;
    return out;
}

int pkgMain(int argc, char **argv, int *new_argc, char ***new_argv) {
    if (argc < 2) return -1;
    char *err = NULL;
    int moved_argc = 0;
    char **moved = pkgOptionsFirst(argc, argv, &moved_argc, &err);
    if (err) return pkgFail("%s", err);
    if (moved) {
        argc = moved_argc;
        argv = moved;
    }
    if (!strcmp(argv[1], "build")) return pkgBuildOrRun(0, argc - 2, argv + 2, new_argc, new_argv);
    if (!strcmp(argv[1], "run")) return pkgBuildOrRun(1, argc - 2, argv + 2, new_argc, new_argv);
    if (!strcmp(argv[1], "mod")) return pkgModCommand(argc - 2, argv + 2);
    if (!strcmp(argv[1], "get")) return pkgGet(argc - 2, argv + 2);
    if (!strcmp(argv[1], "test")) return pkgTest(argc - 2, argv + 2, argv[0]);
    return -1;
}
