/* `ktm test`: finds the *_test.ktm files of one or more directories and
 * the `void testXxx(testing.T *t)` functions in them, writes a runner
 * (a main() that calls each through the stdlib's testing module) and
 * compiles and runs it, once per directory. See pkg.h. */
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#if defined(__APPLE__)
#include <mach-o/dyld.h>
#endif

#include "aostr.h"
#include "pkg.h"

typedef struct PkgTestDir {
    char *dir;          /* as the user wrote it (or found under ./...) */
    char **files;       /* its *_test.ktm files, in name order */
    int nfiles;
} PkgTestDir;

static int pkgTestIsDir(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 && S_ISDIR(st.st_mode);
}

static int pkgTestCmp(const void *a, const void *b) {
    return strcmp(*(char **)a, *(char **)b);
}

static int pkgTestIsTestFile(const char *name) {
    size_t n = strlen(name);
    return n > 9 && name[0] != '.' && !strcmp(name + n - 9, "_test.ktm");
}

/* The *_test.ktm files of dir, sorted; 0 when there are none */
static int pkgTestFiles(const char *dir, PkgTestDir *out) {
    DIR *d = opendir(dir);
    if (!d) return 0;
    char **files = NULL;
    int n = 0;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (!pkgTestIsTestFile(e->d_name)) continue;
        files = realloc(files, sizeof(char *) * (n + 1));
        files[n++] = strcmp(dir, ".") ? mprintf("%s/%s", dir, e->d_name)
                                      : mprintf("%s", e->d_name);
    }
    closedir(d);
    if (!n) return 0;
    qsort(files, n, sizeof(char *), pkgTestCmp);
    out->dir = mprintf("%s", dir);
    out->files = files;
    out->nfiles = n;
    return 1;
}

/* dir and every directory under it with test files (as `go test ./...`:
 * directories starting with `.` or `_` and testdata are left out) */
static void pkgTestWalk(const char *dir, PkgTestDir **dirs, int *n) {
    PkgTestDir td;
    if (pkgTestFiles(dir, &td)) {
        *dirs = realloc(*dirs, sizeof(PkgTestDir) * (*n + 1));
        (*dirs)[(*n)++] = td;
    }
    DIR *d = opendir(dir);
    if (!d) return;
    char **subs = NULL;
    int nsubs = 0;
    struct dirent *e;
    while ((e = readdir(d))) {
        if (e->d_name[0] == '.' || e->d_name[0] == '_' || !strcmp(e->d_name, "testdata")) continue;
        char *sub = strcmp(dir, ".") ? mprintf("%s/%s", dir, e->d_name)
                                     : mprintf("%s", e->d_name);
        if (!pkgTestIsDir(sub)) continue;
        subs = realloc(subs, sizeof(char *) * (nsubs + 1));
        subs[nsubs++] = sub;
    }
    closedir(d);
    qsort(subs, nsubs, sizeof(char *), pkgTestCmp);
    for (int i = 0; i < nsubs; ++i) pkgTestWalk(subs[i], dirs, n);
    free(subs);
}

static char *pkgTestRead(const char *file) {
    FILE *fp = fopen(file, "r");
    if (!fp) return NULL;
    AoStr *buf = aoStrNew();
    char chunk[4096];
    size_t got;
    while ((got = fread(chunk, 1, sizeof(chunk), fp)) > 0) aoStrCatLen(buf, chunk, got);
    fclose(fp);
    return buf->data;
}

/* The text with its comments, strings and character literals blanked
 * (newlines kept), so only code is searched */
static void pkgTestBlank(char *s) {
    for (char *p = s; *p;) {
        if (p[0] == '/' && p[1] == '/') {
            while (*p && *p != '\n') *p++ = ' ';
        } else if (p[0] == '/' && p[1] == '*') {
            *p++ = ' ';
            *p++ = ' ';
            while (*p && !(p[0] == '*' && p[1] == '/')) {
                if (*p != '\n') *p = ' ';
                p++;
            }
            if (*p) { *p++ = ' '; *p++ = ' '; }
        } else if (*p == '"' || *p == '\'') {
            char q = *p++;
            while (*p && *p != q && *p != '\n') {
                if (*p == '\\' && p[1]) *p++ = ' ';
                *p++ = ' ';
            }
            if (*p == q) p++;
        } else {
            p++;
        }
    }
}

static const char *pkgTestSpace(const char *p) {
    while (isspace((unsigned char)*p)) p++;
    return p;
}

static int pkgTestIdentChar(char c) {
    return isalnum((unsigned char)c) || c == '_';
}

/* p at `word` as a whole word: just after it, else NULL */
static const char *pkgTestWord(const char *p, const char *word) {
    size_t n = strlen(word);
    if (strncmp(p, word, n) || pkgTestIdentChar(p[n])) return NULL;
    return p + n;
}

/* `module name;` at the top of a blanked file: name, else NULL */
static char *pkgTestModuleName(const char *code) {
    const char *p = pkgTestWord(pkgTestSpace(code), "module");
    if (!p) return NULL;
    p = pkgTestSpace(p);
    const char *start = p;
    while (pkgTestIdentChar(*p)) p++;
    return p > start ? mprintf("%.*s", (int)(p - start), start) : NULL;
}

/* The test functions of a blanked file, at the top level: `void testXxx(
 * testing.T *t)`, Xxx empty or starting with no lower-case letter */
static void pkgTestFuncs(const char *code, char ***names, int *n) {
    int depth = 0;
    for (const char *p = code; *p; ++p) {
        if (*p == '{') { depth++; continue; }
        if (*p == '}') { depth--; continue; }
        if (depth || (p > code && pkgTestIdentChar(p[-1]))) continue;
        const char *q = pkgTestWord(p, "void");
        if (!q) continue;
        q = pkgTestSpace(q);
        const char *name = q;
        if (strncmp(q, "test", 4) || islower((unsigned char)q[4])) continue;
        while (pkgTestIdentChar(*q)) q++;
        int len = q - name;
        q = pkgTestSpace(q);
        if (*q++ != '(') continue;
        if (!(q = pkgTestWord(pkgTestSpace(q), "testing"))) continue;
        q = pkgTestSpace(q);
        if (*q++ != '.') continue;
        if (!(q = pkgTestWord(pkgTestSpace(q), "T"))) continue;
        q = pkgTestSpace(q);
        if (*q++ != '*') continue;
        q = pkgTestSpace(q);
        while (pkgTestIdentChar(*q)) q++;
        if (*pkgTestSpace(q) != ')') continue;
        *names = realloc(*names, sizeof(char *) * (*n + 1));
        (*names)[(*n)++] = mprintf("%.*s", len, name);
        p = q;
    }
}

/* This ktm's executable, to run again (argv0 when it can't be found
 * another way; NULL: "ktm") */
char *pkgSelfPath(const char *argv0) {
    if (!argv0) argv0 = "ktm";
    char buf[PATH_MAX];
#if defined(__APPLE__)
    uint32_t size = sizeof(buf);
    if (_NSGetExecutablePath(buf, &size) == 0) {
        char *real = realpath(buf, NULL);
        if (real) return real;
    }
#else
    ssize_t len = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    if (len > 0) {
        buf[len] = '\0';
        return mprintf("%s", buf);
    }
#endif
    char *real = strchr(argv0, '/') ? realpath(argv0, NULL) : NULL;
    return real ? real : mprintf("%s", argv0);
}

static int pkgTestUsage(void) {
    fprintf(stderr,
            "usage: ktm test [-v] [-run name] [-jit] [compiler flags] [dir | dir/... | ./...]\n"
            "  runs the void testXxx(testing.T *t) functions of the *_test.ktm files\n"
            "  -v          print every test and its messages, not only the failures\n"
            "  -run name   only the tests whose name contains name\n"
            "  -jit        run with the JIT instead of compiling (-Memsafe works with it)\n");
    return 2;
}

/* One directory's tests: the runner written to a temporary directory,
 * then `ktm [flags] --test-dir ... --test-root ... -run|-jit runner
 * <label> [-v] [-run name]`. 0 when they passed. */
static int pkgTestDir(char *self, PkgTestDir *td, char **cflags, int ncflags,
                      int jit, char **rflags, int nrflags) {
    char *module = NULL;
    char **names = NULL;
    int nnames = 0;
    for (int i = 0; i < td->nfiles; ++i) {
        char *code = pkgTestRead(td->files[i]);
        if (!code) return pkgFail("can't read %s: %s", td->files[i], strerror(errno));
        pkgTestBlank(code);
        char *mod = pkgTestModuleName(code);
        if (i && ((mod == NULL) != (module == NULL) || (mod && strcmp(mod, module)))) {
            return pkgFail("%s: the test files of %s must all be in the same module "
                              "(or all in none)", td->files[i], td->dir);
        }
        module = mod;
        pkgTestFuncs(code, &names, &nnames);
    }

    char *real = realpath(td->dir, NULL);
    if (!real) return pkgFail("%s: %s", td->dir, strerror(errno));
    AoStr *src = aoStrNew();
    aoStrCatPrintf(src, "import testing;\n");
    char *root = real;
    const char *prefix = "";
    if (module) {
        /* the module, imported from the project's root: by its package
         * path in a ktm.mod project, else by its name from the directory
         * above it */
        char *mod_file = pkgModFind(td->dir);
        PkgMod *m = NULL;
        if (mod_file) {
            char *err = NULL;
            if (!(m = pkgModLoad(mod_file, &err))) return pkgFail("%s", err);
            if (!pkgEnsureDownloaded(m)) return 1;
        }
        char *mod_root = m ? realpath(m->dir, NULL) : NULL;
        size_t rlen = mod_root ? strlen(mod_root) : 0;
        if (mod_root && !strncmp(real, mod_root, rlen) && (real[rlen] == '/' || !real[rlen])) {
            root = mod_root;
            aoStrCatPrintf(src, "import ktm_test_pkg = \"%s%s\";\n", m->path, real + rlen);
        } else {
            char *slash = strrchr(real, '/');
            root = slash == real ? "/" : mprintf("%.*s", (int)(slash - real), real);
            aoStrCatPrintf(src, "import ktm_test_pkg = %s;\n", slash + 1);
        }
        prefix = "ktm_test_pkg.";
    } else {
        for (int i = 0; i < td->nfiles; ++i) {
            char *file = realpath(td->files[i], NULL);
            aoStrCatPrintf(src, "#include \"%s\"\n", file ? file : td->files[i]);
            free(file);
        }
    }
    aoStrCatPrintf(src, "\ni32 main(i32 argc, u8 **argv) {\n"
                        "    testing.M *ktm_test_m = testing.start(argc, argv);\n");
    for (int i = 0; i < nnames; ++i) {
        aoStrCatPrintf(src, "    ktm_test_m.run(\"%s\", &%s%s);\n", names[i], prefix, names[i]);
    }
    aoStrCatPrintf(src, "    return ktm_test_m.finish();\n}\n");

    const char *tmp = getenv("TMPDIR");
    char *tmp_dir = mprintf("%s/ktm-test-XXXXXX", tmp && *tmp ? tmp : "/tmp");
    if (!mkdtemp(tmp_dir)) return pkgFail("can't make a temporary directory: %s", strerror(errno));
    char *runner = mprintf("%s/ktm_test_main.ktm", tmp_dir);
    FILE *fp = fopen(runner, "w");
    if (!fp) return pkgFail("can't write %s: %s", runner, strerror(errno));
    fwrite(src->data, 1, src->len, fp);
    fclose(fp);

    char **args = calloc(ncflags + nrflags + 12, sizeof(char *));
    int n = 0;
    args[n++] = "ktm";
    for (int i = 0; i < ncflags; ++i) args[n++] = cflags[i];
    if (module) {
        args[n++] = "--test-dir";
        args[n++] = real;
    }
    args[n++] = "--test-root";
    args[n++] = root;
    args[n++] = jit ? "-jit" : "-run";
    args[n++] = runner;
    args[n++] = td->dir;
    for (int i = 0; i < nrflags; ++i) args[n++] = rflags[i];
    args[n] = NULL;

    fflush(stdout);
    fflush(stderr);
    int status = 0;
    pid_t pid = fork();
    if (pid == -1) return pkgFail("can't fork: %s", strerror(errno));
    if (pid == 0) {
        /* in the package's directory, as go test: a test opens
         * "testdata/x" or "../migrations" from where it is */
        if (chdir(real) != 0) {
            fprintf(stderr, "ktm: can't enter %s: %s\n", real, strerror(errno));
            _exit(127);
        }
        execv(self, args);
        fprintf(stderr, "ktm: can't run %s: %s\n", self, strerror(errno));
        _exit(127);
    }
    while (waitpid(pid, &status, 0) == -1 && errno == EINTR);
    unlink(runner);
    rmdir(tmp_dir);
    free(args);

    int rc = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    /* 3: the runner's "a test failed", its summary printed */
    if (rc == 0 || rc == 3) return rc;
    printf("FAIL\t%s\t[%s]\n", td->dir,
           WIFSIGNALED(status) || rc > 128 ? "crashed: -v shows in which test"
                                            : "build failed or exited early");
    return 1;
}

int pkgTest(int argc, char **argv, char *argv0) {
    char **cflags = calloc(argc + 1, sizeof(char *));
    char **rflags = calloc(argc + 1, sizeof(char *));
    char **pats = calloc(argc + 1, sizeof(char *));
    int ncflags = 0, nrflags = 0, npats = 0, jit = 0;
    for (int i = 0; i < argc; ++i) {
        char *a = argv[i];
        if (!strcmp(a, "-h") || !strcmp(a, "--help") || !strcmp(a, "help")) {
            pkgTestUsage();
            return 0;
        } else if (!strcmp(a, "-v")) {
            rflags[nrflags++] = a;
        } else if (!strcmp(a, "-run")) {
            if (i + 1 >= argc) return pkgTestUsage();
            rflags[nrflags++] = a;
            rflags[nrflags++] = argv[++i];
        } else if (!strcmp(a, "-jit")) {
            jit = 1;
        } else if (a[0] == '-') {
            /* the compiler's: -Memsafe, -Werror, -D..., --install-dir=... */
            if (!strcmp(a, "-o") || !strcmp(a, "-lib") || !strcmp(a, "-repl") ||
                !strcmp(a, "-lsp") || !strcmp(a, "-S") || !strcmp(a, "-c")) {
                return pkgFail("`ktm test` doesn't take %s", a);
            }
            /* the tests run in their own directory: a relative
             * --install-dir is made absolute here */
            if (!strncmp(a, "--install-dir=", 14)) {
                char *real = realpath(a + 14, NULL);
                if (real) a = mprintf("--install-dir=%s", real);
            }
            cflags[ncflags++] = a;
            if ((!strcmp(a, "--install-dir") || !strcmp(a, "-clibs")) && i + 1 < argc) {
                char *v = argv[++i];
                char *real = !strcmp(a, "--install-dir") ? realpath(v, NULL) : NULL;
                cflags[ncflags++] = real ? real : v;
            }
        } else {
            pats[npats++] = a;
        }
    }
    if (!npats) pats[npats++] = ".";

    PkgTestDir *dirs = NULL;
    int ndirs = 0, bad = 0;
    for (int i = 0; i < npats; ++i) {
        char *p = pats[i];
        size_t len = strlen(p);
        if (len >= 3 && !strcmp(p + len - 3, "...") && (len == 3 || p[len - 4] == '/')) {
            char *base = len == 3 ? "." : len == 4 ? "/" : mprintf("%.*s", (int)len - 4, p);
            if (!pkgTestIsDir(base)) {
                bad = pkgFail("%s: no such directory", base);
                continue;
            }
            int before = ndirs;
            pkgTestWalk(base, &dirs, &ndirs);
            if (ndirs == before) printf("?   \t%s\t[no test files]\n", p);
        } else if (pkgTestIsDir(p)) {
            PkgTestDir td;
            if (pkgTestFiles(p, &td)) {
                dirs = realloc(dirs, sizeof(PkgTestDir) * (ndirs + 1));
                dirs[ndirs++] = td;
            } else {
                printf("?   \t%s\t[no test files]\n", p);
            }
        } else {
            bad = pkgFail("%s: not a directory (`ktm test dir` or `ktm test ./...`)", p);
        }
    }

    char *self = pkgSelfPath(argv0);
    for (int i = 0; i < ndirs; ++i) {
        if (pkgTestDir(self, &dirs[i], cflags, ncflags, jit, rflags, nrflags)) bad = 1;
    }
    return bad;
}
