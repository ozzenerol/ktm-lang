/* Packages: downloading them. `ktm get` and `ktm mod download` find a
 * package's versions in its git repository's tags, fetch a version into
 * the module cache and record its hash in ktm.sum. Fetching never runs
 * the package's code: git clones, ktm hashes. See pkg.h. */
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include "aostr.h"
#include "pkg.h"

/* ---- SHA-256 (FIPS 180-4), for the h1: hashes ---- */

typedef struct PkgSha {
    uint32_t h[8];
    uint64_t len;
    uint8_t buf[64];
    int used;
} PkgSha;

static const uint32_t pkg_sha_k[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};

#define PKG_ROR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void pkgShaBlock(PkgSha *s, const uint8_t *p) {
    uint32_t w[64];
    for (int i = 0; i < 16; ++i) {
        w[i] = (uint32_t)p[i * 4] << 24 | (uint32_t)p[i * 4 + 1] << 16 |
               (uint32_t)p[i * 4 + 2] << 8 | p[i * 4 + 3];
    }
    for (int i = 16; i < 64; ++i) {
        uint32_t s0 = PKG_ROR(w[i - 15], 7) ^ PKG_ROR(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = PKG_ROR(w[i - 2], 17) ^ PKG_ROR(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = s->h[0], b = s->h[1], c = s->h[2], d = s->h[3];
    uint32_t e = s->h[4], f = s->h[5], g = s->h[6], h = s->h[7];
    for (int i = 0; i < 64; ++i) {
        uint32_t t1 = h + (PKG_ROR(e, 6) ^ PKG_ROR(e, 11) ^ PKG_ROR(e, 25)) +
                      ((e & f) ^ (~e & g)) + pkg_sha_k[i] + w[i];
        uint32_t t2 = (PKG_ROR(a, 2) ^ PKG_ROR(a, 13) ^ PKG_ROR(a, 22)) +
                      ((a & b) ^ (a & c) ^ (b & c));
        h = g; g = f; f = e; e = d + t1;
        d = c; c = b; b = a; a = t1 + t2;
    }
    s->h[0] += a; s->h[1] += b; s->h[2] += c; s->h[3] += d;
    s->h[4] += e; s->h[5] += f; s->h[6] += g; s->h[7] += h;
}

static void pkgShaInit(PkgSha *s) {
    static const uint32_t iv[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                                   0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
    memcpy(s->h, iv, sizeof(iv));
    s->len = 0;
    s->used = 0;
}

static void pkgShaUpdate(PkgSha *s, const void *data, size_t n) {
    const uint8_t *p = data;
    s->len += n;
    while (n) {
        size_t take = 64 - s->used;
        if (take > n) take = n;
        memcpy(s->buf + s->used, p, take);
        s->used += take;
        p += take;
        n -= take;
        if (s->used == 64) {
            pkgShaBlock(s, s->buf);
            s->used = 0;
        }
    }
}

static void pkgShaFinal(PkgSha *s, uint8_t out[32]) {
    uint64_t bits = s->len * 8;
    uint8_t pad = 0x80, zero = 0;
    pkgShaUpdate(s, &pad, 1);
    while (s->used != 56) pkgShaUpdate(s, &zero, 1);
    uint8_t lenb[8];
    for (int i = 0; i < 8; ++i) lenb[i] = (uint8_t)(bits >> (56 - 8 * i));
    pkgShaUpdate(s, lenb, 8);
    for (int i = 0; i < 8; ++i) {
        out[i * 4] = s->h[i] >> 24;
        out[i * 4 + 1] = s->h[i] >> 16;
        out[i * 4 + 2] = s->h[i] >> 8;
        out[i * 4 + 3] = s->h[i];
    }
}

/* The sha256 of n bytes at data, as 64 hex digits */
char *pkgSha256Hex(const void *data, size_t n) {
    PkgSha s;
    uint8_t out[32];
    pkgShaInit(&s);
    pkgShaUpdate(&s, data, n);
    pkgShaFinal(&s, out);
    AoStr *hex = aoStrNew();
    for (int i = 0; i < 32; ++i) aoStrCatPrintf(hex, "%02x", out[i]);
    return aoStrMove(hex);
}

/* The sha256 of a file's bytes, as hex; NULL when it can't be read */
char *pkgSha256File(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    PkgSha s;
    uint8_t buf[65536], out[32];
    size_t n;
    pkgShaInit(&s);
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) pkgShaUpdate(&s, buf, n);
    fclose(f);
    pkgShaFinal(&s, out);
    AoStr *hex = aoStrNew();
    for (int i = 0; i < 32; ++i) aoStrCatPrintf(hex, "%02x", out[i]);
    return aoStrMove(hex);
}

static char *pkgBase64(const uint8_t *p, int n) {
    static const char *abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    AoStr *b = aoStrNew();
    for (int i = 0; i < n; i += 3) {
        uint32_t v = (uint32_t)p[i] << 16 | (i + 1 < n ? (uint32_t)p[i + 1] << 8 : 0) |
                     (i + 2 < n ? p[i + 2] : 0);
        aoStrPutChar(b, abc[v >> 18 & 63]);
        aoStrPutChar(b, abc[v >> 12 & 63]);
        aoStrPutChar(b, i + 1 < n ? abc[v >> 6 & 63] : '=');
        aoStrPutChar(b, i + 2 < n ? abc[v & 63] : '=');
    }
    return aoStrMove(b);
}

/* ---- the h1: hash of a directory (Go's dirhash.Hash1) ----
 * sha256 of the lines "<sha256 hex of a file>  <path>\n", one per file,
 * sorted by path; "h1:" and its base64 */

static int pkgStrCmp(const void *a, const void *b) {
    return strcmp(*(char **)a, *(char **)b);
}

static void pkgListFiles(const char *root, const char *rel, char ***files, int *n, int *cap) {
    char *dir = *rel ? mprintf("%s/%s", root, rel) : mprintf("%s", root);
    DIR *d = opendir(dir);
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        char *r = *rel ? mprintf("%s/%s", rel, e->d_name) : mprintf("%s", e->d_name);
        char *full = mprintf("%s/%s", root, r);
        struct stat st;
        if (lstat(full, &st) != 0) continue;
        if (S_ISDIR(st.st_mode)) {
            pkgListFiles(root, r, files, n, cap);
        } else if (S_ISREG(st.st_mode)) {
            if (*n == *cap) {
                *cap = *cap ? *cap * 2 : 64;
                *files = realloc(*files, sizeof(char *) * *cap);
            }
            (*files)[(*n)++] = r;
        }
    }
    closedir(d);
}

static int pkgHashFile(const char *path, char hex[65]) {
    FILE *fp = fopen(path, "rb");
    if (!fp) return 0;
    PkgSha s;
    pkgShaInit(&s);
    char buf[16384];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), fp)) > 0) pkgShaUpdate(&s, buf, n);
    fclose(fp);
    uint8_t d[32];
    pkgShaFinal(&s, d);
    for (int i = 0; i < 32; ++i) sprintf(hex + i * 2, "%02x", d[i]);
    return 1;
}

char *pkgDirHash(const char *root) {
    char **files = NULL;
    int n = 0, cap = 0;
    pkgListFiles(root, "", &files, &n, &cap);
    qsort(files, n, sizeof(char *), pkgStrCmp);
    PkgSha s;
    pkgShaInit(&s);
    for (int i = 0; i < n; ++i) {
        char hex[65];
        if (!pkgHashFile(mprintf("%s/%s", root, files[i]), hex)) {
            free(files);
            return NULL;
        }
        char *line = mprintf("%s  %s\n", hex, files[i]);
        pkgShaUpdate(&s, line, strlen(line));
    }
    free(files);
    uint8_t d[32];
    pkgShaFinal(&s, d);
    return mprintf("h1:%s", pkgBase64(d, 32));
}

/* ---- running git ---- */

/* argv run with its output in *out (stderr too when err_too); the exit
 * status, -1 when it couldn't start */
static int pkgRun(char **argv, AoStr *out, int err_too) {
    int fds[2];
    if (pipe(fds) != 0) return -1;
    pid_t pid = fork();
    if (pid < 0) {
        close(fds[0]);
        close(fds[1]);
        return -1;
    }
    if (pid == 0) {
        dup2(fds[1], 1);
        if (err_too) dup2(fds[1], 2);
        else {
            int null = open("/dev/null", O_WRONLY);
            if (null >= 0) dup2(null, 2);
        }
        close(fds[0]);
        close(fds[1]);
        /* no prompt for a password: a private repo needs a credential
         * helper or an SSH key, else it fails at once */
        setenv("GIT_TERMINAL_PROMPT", "0", 1);
        execvp(argv[0], argv);
        _exit(127);
    }
    close(fds[1]);
    char buf[4096];
    ssize_t n;
    while ((n = read(fds[0], buf, sizeof(buf))) > 0) {
        if (out) aoStrCatLen(out, buf, n);
    }
    close(fds[0]);
    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR);
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

/* The repository of a package path: its first three elements
 * (host/user/repo), as on GitHub, GitLab and most hosts */
char *pkgRepoRoot(const char *path) {
    const char *p = path;
    for (int i = 0; i < 3; ++i) {
        p = strchr(p, '/');
        if (!p) return mprintf("%s", path);
        if (i < 2) p++;
    }
    return mprintf("%.*s", (int)(p - path), path);
}

static char *pkgRepoUrl(const char *root) {
    return mprintf("https://%s", root);
}

/* The release tags of repo root, highest first; NULL with *err set */
char **pkgVersions(const char *root, int *count, char **err) {
    char *url = pkgRepoUrl(root);
    AoStr *out = aoStrNew();
    char *argv[] = {"git", "ls-remote", "--tags", "--refs", url, NULL};
    int rc = pkgRun(argv, out, 1);
    if (rc != 0) {
        *err = rc == 127 || rc < 0
             ? mprintf("can't run git: packages are fetched with git, install it")
             : mprintf("can't read %s: %.*s", url, (int)(out->len > 300 ? 300 : out->len), out->data);
        return NULL;
    }
    char **vs = NULL;
    int n = 0;
    char *line = out->data;
    while (line && *line) {
        char *nl = strchr(line, '\n');
        if (nl) *nl = '\0';
        char *ref = strstr(line, "refs/tags/");
        if (ref) {
            char *tag = ref + 10;
            if (pkgValidVersion(tag)) {
                vs = realloc(vs, sizeof(char *) * (n + 1));
                vs[n++] = mprintf("%s", tag);
            }
        }
        line = nl ? nl + 1 : NULL;
    }
    /* highest first */
    for (int i = 0; i < n; ++i) {
        for (int j = i + 1; j < n; ++j) {
            if (pkgVersionCmp(vs[j], vs[i]) > 0) {
                char *t = vs[i];
                vs[i] = vs[j];
                vs[j] = t;
            }
        }
    }
    *count = n;
    return vs;
}

/* The newest release (a prerelease only when there is nothing else) */
char *pkgLatest(const char *root, char **err) {
    int n = 0;
    char **vs = pkgVersions(root, &n, err);
    if (!vs && *err) return NULL;
    for (int i = 0; i < n; ++i) {
        if (!strchr(vs[i], '-')) return vs[i];
    }
    if (n) return vs[0];
    *err = mprintf("%s has no version tags: a release is a git tag such as v0.1.0", root);
    return NULL;
}

static int pkgRemoveTree(const char *path) {
    char *argv[] = {"rm", "-rf", (char *)path, NULL};
    return pkgRun(argv, NULL, 0) == 0;
}

/* Every file and directory read-only: the cache is never edited by hand */
static void pkgReadOnly(const char *path) {
    char *argv[] = {"chmod", "-R", "a-w", (char *)path, NULL};
    pkgRun(argv, NULL, 0);
}

static int pkgMkdirs(const char *path) {
    char *copy = mprintf("%s", path);
    for (char *p = copy + 1; *p; ++p) {
        if (*p != '/') continue;
        *p = '\0';
        if (mkdir(copy, 0755) != 0 && errno != EEXIST) return 0;
        *p = '/';
    }
    return mkdir(copy, 0755) == 0 || errno == EEXIST;
}

/* The cache's lock, held while a download moves into place */
static int pkgLockCache(void) {
    char *cache = pkgCacheDir();
    if (!pkgMkdirs(cache)) return -1;
    int fd = open(mprintf("%s/.lock", cache), O_RDWR | O_CREAT, 0644);
    if (fd >= 0) flock(fd, LOCK_EX);
    return fd;
}

static void pkgUnlockCache(int fd) {
    if (fd >= 0) {
        flock(fd, LOCK_UN);
        close(fd);
    }
}

/* Module path@version into the cache (if it isn't there) and its hash;
 * NULL with *err set */
char *pkgDownload(const char *path, const char *version, char **err) {
    char *dir = mprintf("%s/%s@%s", pkgCacheDir(), path, version);
    struct stat st;
    if (stat(dir, &st) == 0 && S_ISDIR(st.st_mode)) {
        char *h = pkgDirHash(dir);
        if (!h) *err = mprintf("can't read %s", dir);
        return h;
    }
    char *root = pkgRepoRoot(path);
    if (strcmp(root, path)) {
        *err = mprintf("%s is a directory of %s: require the repository, %s", path, root, root);
        return NULL;
    }
    char *tmp_parent = mprintf("%s/.tmp", pkgCacheDir());
    if (!pkgMkdirs(tmp_parent)) {
        *err = mprintf("can't make %s: %s", tmp_parent, strerror(errno));
        return NULL;
    }
    char *tmp = mprintf("%s/dl-%d-%ld", tmp_parent, (int)getpid(), (long)random());
    AoStr *out = aoStrNew();
    char *argv[] = {"git", "-c", "advice.detachedHead=false", "clone", "--quiet", "--depth", "1",
                    "--branch", (char *)version, pkgRepoUrl(root), tmp, NULL};
    if (pkgRun(argv, out, 1) != 0) {
        pkgRemoveTree(tmp);
        *err = mprintf("can't fetch %s %s: %.*s", path, version,
                       (int)(out->len > 300 ? 300 : out->len), out->data);
        return NULL;
    }
    pkgRemoveTree(mprintf("%s/.git", tmp));
    /* the version's own ktm.mod must name it */
    char *mod_file = mprintf("%s/ktm.mod", tmp);
    if (stat(mod_file, &st) == 0) {
        char *merr = NULL;
        PkgMod *m = pkgModLoad(mod_file, &merr);
        if (!m) {
            pkgRemoveTree(tmp);
            *err = mprintf("%s %s: %s", path, version, merr);
            return NULL;
        }
        if (strcmp(m->path, path)) {
            pkgRemoveTree(tmp);
            *err = mprintf("%s %s calls itself %s in its ktm.mod", path, version, m->path);
            return NULL;
        }
    }
    char *hash = pkgDirHash(tmp);
    int lock = pkgLockCache();
    char *parent = mprintf("%s", dir);
    *strrchr(parent, '/') = '\0';
    int ok = pkgMkdirs(parent);
    if (ok && rename(tmp, dir) != 0) {
        /* another ktm got it there first */
        ok = stat(dir, &st) == 0 && S_ISDIR(st.st_mode);
        pkgRemoveTree(tmp);
    }
    if (ok) pkgReadOnly(dir);
    pkgUnlockCache(lock);
    if (!ok) {
        *err = mprintf("can't store %s in the cache %s", path, dir);
        return NULL;
    }
    return hash;
}
