#ifndef PKG_H__
#define PKG_H__

/* Packages (v0.20.0): ktm.mod and ktm.sum, and the commands that work on
 * them (`ktm mod ...`). The format is go.mod's: a module line, the ktm
 * version, requires and replaces. */

typedef struct PkgReq {
    char *path;
    char *version;
    int line;
    int indirect;           /* `// indirect`: only needed by a dependency */
} PkgReq;

typedef struct PkgReplace {
    char *path;
    char *version;          /* NULL: every version of path */
    char *new_path;
    char *new_version;      /* NULL: new_path is a local directory */
    int line;
} PkgReplace;

typedef struct PkgMod {
    char *file;             /* the ktm.mod read */
    char *dir;              /* its directory: the project's root */
    char *path;             /* the module line */
    char *ktm;              /* the ktm line: "0.20" */
    PkgReq *req;
    int req_len;
    PkgReplace *rep;
    int rep_len;
} PkgMod;

typedef struct PkgSumLine {
    char *path;
    char *version;
    char *hash;             /* "h1:..." */
} PkgSumLine;

typedef struct PkgSum {
    PkgSumLine *lines;
    int len;
} PkgSum;

/* Parses ktm.mod text; NULL with *err set ("ktm.mod:3: ...") on an error */
PkgMod *pkgModParse(const char *file, const char *text, char **err);
/* Reads and parses the file */
PkgMod *pkgModLoad(const char *file, char **err);
/* The ktm.mod in dir or the closest directory above it, else NULL */
char *pkgModFind(const char *dir);
/* The file's text, in the canonical layout */
char *pkgModFormat(PkgMod *m);
/* Writes m to m->file; 0 on an error, with errno set */
int pkgModWrite(PkgMod *m);

PkgSum *pkgSumParse(const char *file, const char *text, char **err);
char *pkgSumFormat(PkgSum *s);

/* A module path: elements of [A-Za-z0-9._~-] separated by '/', none
 * empty, ".", ".." or starting with a dot. remote: the first element
 * has a dot (a host name), as every path a package is fetched from. */
int pkgValidPath(const char *path, int remote);
/* vMAJOR.MINOR.PATCH with an optional -prerelease */
int pkgValidVersion(const char *v);
/* Semantic version order: <0, 0, >0 */
int pkgVersionCmp(const char *a, const char *b);
/* Is "major.minor" newer than this compiler? */
int pkgKtmTooNew(const char *ktm);

/* The module cache: $KTM_CACHE, else ~/.cache/ktm/mod */
char *pkgCacheDir(void);
/* Where `import "path";` in a program under program_dir is: a directory,
 * or NULL with *err set (what to run to fix it) */
char *pkgResolveImport(const char *program_dir, const char *path, char **err);
/* Is path one of the project's own packages (under its module path)? */
int pkgIsProjectPath(const char *program_dir, const char *path);
/* The name on the `module` line of the package in dir, NULL if none */
char *pkgModuleName(const char *dir);

/* Downloading (pkgfetch.c) */
char *pkgDirHash(const char *root);
char *pkgRepoRoot(const char *path);
char **pkgVersions(const char *root, int *count, char **err);
char *pkgLatest(const char *root, char **err);
char *pkgDownload(const char *path, const char *version, char **err);

/* `ktm mod ...` and the other package commands: the exit code, -1 when
 * argv[1] isn't one (a file to compile), or -2 for `ktm build` and
 * `ktm run`, which become an ordinary compile: *new_argc and *new_argv
 * are its command line */
int pkgMain(int argc, char **argv, int *new_argc, char ***new_argv);

#endif
