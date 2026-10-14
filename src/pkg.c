/* Packages: ktm.mod and ktm.sum, and `ktm mod ...`. See pkg.h. */
#include <ctype.h>
#include <errno.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "aostr.h"
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

/* ---- commands ---- */

static int pkgFail(const char *fmt, ...) {
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

static int pkgModUsage(void) {
    fprintf(stderr,
            "usage: ktm mod <command>\n"
            "  init [path]   create ktm.mod for a new project in this directory\n"
            "  fmt           check ktm.mod and rewrite it in the standard layout\n");
    return 2;
}

static int pkgModCommand(int argc, char **argv) {
    if (argc < 1) return pkgModUsage();
    if (!strcmp(argv[0], "init")) return pkgModInit(argc - 1, argv + 1);
    if (!strcmp(argv[0], "fmt")) return pkgModFmt(argc - 1);
    if (!strcmp(argv[0], "help") || !strcmp(argv[0], "--help")) {
        pkgModUsage();
        return 0;
    }
    fprintf(stderr, "ktm: unknown command `ktm mod %s`\n", argv[0]);
    return pkgModUsage();
}

int pkgMain(int argc, char **argv) {
    if (argc < 2) return -1;
    if (!strcmp(argv[1], "mod")) return pkgModCommand(argc - 2, argv + 2);
    return -1;
}
