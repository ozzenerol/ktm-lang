#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <ctype.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>

#include "aostr.h"
#include "ast.h"
#include "cctrl.h"
#include "cfg-print.h"
#include "cfg.h"
#include "cli.h"
#include "pkg.h"
#include "compile.h"
#include "config.h"
#include "ir.h"
#include "ir-types.h"
#ifdef KTM_ENABLE_JIT
#include "aarch64-jit.h"
#include "x86_64-jit.h"
#include "memsafe.h"
#include "repl.h"
#endif
#include "lexer.h"
#include "parser.h"
#include "list.h"
#include "lsp.h"
#include "memory.h"
#include "types.h"
#include "util.h"

int is_terminal;

#define LIB_BUFSIZ 256

#ifndef INSTALL_PREFIX
    #define INSTALL_PREFIX "/usr/local"
#endif

#define CLIBS_BASE "-lpthread -lc -lm"

#ifdef KTM_LINK_SQLITE3
#define CLIBS CLIBS_BASE" -lsqlite3"
#else
#define CLIBS CLIBS_BASE
#endif

/* Run the link command `cmd`, its messages (ld's "undefined reference to
 * `_K3geo4area'") printed with ktm's symbols by their names (`geo.area`).
 * The command's exit status. */
static AoStr *link_messages = NULL;  /* the last link's, names demangled */

static int linkCommand(const char *cmd) {
    char errfile[PATH_MAX];
    const char *dir = getenv("TMPDIR");
    if (!dir || !*dir) dir = "/tmp";
    snprintf(errfile, sizeof(errfile), "%s/ktm-link-XXXXXX", dir);
    int fd = mkstemp(errfile);
    if (fd < 0) return system(cmd);
    close(fd);
    char *full = mprintf("%s 2> %s", cmd, errfile);
    int rc = system(full);
    FILE *f = fopen(errfile, "r");
    if (f) {
        AoStr *out = aoStrNew();
        int ch;
        AoStr *word = aoStrNew();
        while (1) {
            ch = fgetc(f);
            if (ch != EOF && (isalnum(ch) || ch == '_')) {
                aoStrPutChar(word, (char)ch);
                continue;
            }
            if (word->len) {
                aoStrCat(out, (char *)astSymbolDisplayName(word->data));
                word->len = 0;
                word->data[0] = '\0';
            }
            if (ch == EOF) break;
            aoStrPutChar(out, (char)ch);
        }
        fclose(f);
        fwrite(out->data, 1, out->len, stderr);
        link_messages = out;
    }
    unlink(errfile);
    return rc;
}

void safeSystem(const char *cmd, int print_cmd) {
    int ok = system(cmd);
    if (print_cmd)
        fprintf(stderr, "%s\n", cmd);
    if (ok != 0) {
        loggerPanic("Failed to execute command: '%s'\n", cmd);
    }
}

typedef struct ktmLib {
    char *name;
    char *version;
    char *objectfile;
    char *dylib_ext;
    char dylib_name[LIB_BUFSIZ];
    char dylib_version_name[LIB_BUFSIZ];
    char *stylib_ext;
    char stylib_name[LIB_BUFSIZ];
    char *dylib_cmd;
    char *stylib_cmd;
    char *install_cmd;
} ktmLib;

/* `-l` flags for every `#link <name>` directive, ready to splice into
 * a link command. Empty string when nothing was #link'd. Also adds
 * `-L` for lib dirs the platform linker doesn't necessarily search on
 * its own (Homebrew on Apple Silicon most notably) - but only ones
 * that exist, so ld doesn't warn, and only when a `#link <name>` is
 * actually in play. */
/* After a failed link: the program's functions that are declared but
 * never defined. Their symbols are the program's own (`_K06strlen`), so
 * one meant as a C function needs `extern "c"`. */
static void noteUndefinedPrototypes(Cctrl *cc) {
    MapIter it;
    mapIterInit(cc->global_env, &it);
    /* a module's function is in global_env by its name and its symbol */
    Set *seen = setNew(32, &set_uint_type);
    while (mapIterNext(&it)) {
        Ast *fn = (Ast *)it.node->value;
        if (!fn || setHas(seen, (void *)fn)) continue;
        setAdd(seen, (void *)fn);
        int module_fn = fn->fname && !strncmp(fn->fname->data, "_K", 2);
        /* only what the linker reported missing (a prebuilt module's
         * functions are prototypes here, defined in its library) */
        const char *name = fn->fname ? astSymbolDisplayName(fn->fname->data) : NULL;
        int missing = name && (!link_messages || strstr(link_messages->data, name));
        if (fn->kind == AST_FUN_PROTO && missing &&
            ((fn->flags & AST_FLAG_PROGRAM) || module_fn)) {
            fprintf(stderr, "note: `%s` is declared but not defined; a C function "
                    "is declared with extern \"c\"\n",
                    astSymbolDisplayName(fn->fname->data));
        }
        /* an `extern T x;` of the program is a ktm global too */
        if (fn->kind == AST_GVAR && (fn->flags & AST_FLAG_PROGRAM) &&
            (fn->flags & AST_FLAG_EXTERN) &&
            (!link_messages || strstr(link_messages->data, fn->gname->data))) {
            fprintf(stderr, "note: `%s` is declared but not defined; a C variable "
                    "is declared with extern \"c\"\n", fn->gname->data);
        }
    }
}

static AoStr *linkLibFlags(Cctrl *cc) {
    AoStr *flags = aoStrNew();
    flags->data[0] = '\0';
    if (!listEmpty(cc->link_libs)) {
        static const char *const extra_dirs[] = {
            "/opt/homebrew/lib",
            "/usr/local/lib",
        };
        for (size_t i = 0; i < sizeof(extra_dirs)/sizeof(extra_dirs[0]); ++i) {
            if (access(extra_dirs[i], F_OK) == 0) {
                aoStrCatFmt(flags, "-L%s ", extra_dirs[i]);
            }
        }
        listForEach(cc->link_libs) {
            AoStr *name = it->value;
            aoStrCatFmt(flags, "-l%s ", name->data);
        }
    }
    return flags;
}

int ktmLibInit(Cctrl *cc, ktmLib *lib, CliArgs *args, char *name) {
    AoStr *dylib_cmd = aoStrNew();
    AoStr *stylib_cmd = aoStrNew();
    AoStr *installcmd = aoStrNew();
    AoStr *link_flags = linkLibFlags(cc);
    
    /* All literal INSTALL_PREFIX paths are now plumbed through
     * args->install_dir so a `-lib ktm --install-dir=X` build lands
     * in X/lib/ rather than always /usr/local/. The default install
     * dir is still INSTALL_PREFIX (set in cliArgsInit), so existing
     * invocations behave unchanged - this is purely an opt-in
     * redirection for cross-builds and sandboxed installs. */
#if IS_BSD
    /* `lib`-prefixed throughout, matching the Linux naming below and
     * what jitLoadLibtos probes for. */
    snprintf(lib->stylib_name,LIB_BUFSIZ,"lib%s.a",name);
    snprintf(lib->dylib_name,LIB_BUFSIZ,"lib%s.dylib",name);
    snprintf(lib->dylib_version_name,LIB_BUFSIZ,"lib%s.0.0.1.dylib",name);

    char *lib_install_dir = tprintf("%s/lib", args->install_dir);

    /* `-install_name` is the path a client records to load this from
     * at runtime, so it has to name the file that actually gets
     * installed - the VERSIONED dylib, not the unversioned build
     * artefact (which is never installed) and not an extensionless
     * path. Nothing links against libktm today (AOT takes the
     * archive), but a wrong install_name would only surface later as
     * an image-not-found at load time. */
    aoStrCatPrintf(dylib_cmd,
            "%s -dynamiclib -Wl,-install_name,%s/%s %s -o %s "CLIBS" %s",
            cc->CC,
            lib_install_dir,
            lib->dylib_version_name,
            args->obj_outfile,
            lib->dylib_name,
            link_flags->data);

    /* Install the same two artefacts as Linux, for the same reasons
     * (see the long comment on the Linux branch): the static archive
     * that `-lktm` resolves to, and the VERSIONED dylib that the JIT
     * dlopen's - jitLoadLibtos probes `lib/libktm.0.0.1.dylib`.
     *
     * Deliberately NO unversioned `libktm.dylib`: `ld` prefers a dylib
     * over an archive for `-lktm`, so installing one would silently
     * flip AOT builds to dynamic linking. */
    aoStrCatPrintf(installcmd,
            "cp -pPR ./%s %s/%s && ",
            lib->stylib_name,
            lib_install_dir,
            lib->stylib_name);

    /* Copy the versioned dylib to somewhere like /usr/local/lib */
    aoStrCatPrintf(installcmd, "cp -pPR ./%s %s/%s",
            lib->dylib_name,
            lib_install_dir,
            lib->dylib_version_name);

#elif IS_LINUX
    snprintf(lib->stylib_name,LIB_BUFSIZ,"lib%s.a",name);
    snprintf(lib->dylib_name,LIB_BUFSIZ,"lib%s.so",name);
    snprintf(lib->dylib_version_name,LIB_BUFSIZ,"lib%s.so.0.0.1",name);

    char *lib_install_dir = tprintf("%s/lib", args->install_dir);

    /* `-Bsymbolic` binds libktm's internal global references (e.g. the
     * pthread key the exception runtime keeps its per-thread state
     * under) to libktm's own definitions. That makes those symbols non-preemptible,
     * which is what lets the code generators use direct RIP-relative /
     * adrp+add addressing (already position independent) rather than
     * routing every global access through the GOT. The symbols stay in
     * the dynamic table, so the JIT can still dlsym them. */
    aoStrCatPrintf(dylib_cmd,
            "%s -fPIC -shared -Wl,-Bsymbolic -Wl,-soname,%s %s -o %s "CLIBS" %s",
            cc->CC,
            lib->dylib_version_name,
            args->obj_outfile,
            lib->dylib_name,
            link_flags->data);

    /* Install two artefacts:
     *   - the static archive `libktm.a`, which AOT links via `-lktm`.
     *   - the VERSIONED shared object `libktm.so.0.0.1`, which the JIT
     *     dlopen's (jitLoadLibtos probes `lib/libktm.so.0.0.1`).
     *
     * Deliberately NO unversioned `libktm.so` symlink: that would make
     * `-lktm` prefer the shared object and dynamic-link AOT. Because
     * libktm is built with `-Bsymbolic` (see above), a dynamically
     * linked executable gets a COPY relocation for exported data while
     * libktm's own functions keep using their internal copy - two
     * objects for one global. Static linking keeps a single one; `-lktm`
     * finds the archive because no `libktm.so` exists beside it. */
    aoStrCatPrintf(installcmd,
            "cp -pPR ./%s %s/%s && ",
            lib->stylib_name,
            lib_install_dir,
            lib->stylib_name);

    /* Copy the versioned .so file to somewhere like /usr/local/lib */
    aoStrCatPrintf(installcmd, "cp -pPR ./%s %s/%s",
            lib->dylib_name,
            lib_install_dir,
            lib->dylib_version_name);
#else
#error "System not supported"
#endif
    aoStrCatPrintf(stylib_cmd,"ar rcs %s %s",lib->stylib_name,args->obj_outfile);
    aoStrRelease(link_flags);
    lib->install_cmd = aoStrMove(installcmd);
    lib->dylib_cmd = aoStrMove(dylib_cmd);
    lib->stylib_cmd = aoStrMove(stylib_cmd);
    
    return 1;
}

/* Temporary files of this compile: the intermediate assembly and the
 * binary of `-run`. Each compile gets its own (mkstemp), so concurrent
 * compiles don't overwrite each other's, and they are removed on exit,
 * on a fatal error (loggerPanic exits) and on a terminating signal. */
static char asm_tmp_file[PATH_MAX];
static char run_tmp_file[PATH_MAX];

static void removeTmpFiles(void) {
    if (asm_tmp_file[0]) unlink(asm_tmp_file);
    if (run_tmp_file[0]) unlink(run_tmp_file);
    asm_tmp_file[0] = run_tmp_file[0] = '\0';
}

static void removeTmpFilesOnSignal(int sig) {
    if (asm_tmp_file[0]) unlink(asm_tmp_file);
    if (run_tmp_file[0]) unlink(run_tmp_file);
    signal(sig, SIG_DFL);
    raise(sig);
}

/* Create a unique file `$TMPDIR/ktm-<what>-XXXXXX<suffix>` in `path`. */
static int makeTmpFile(char *path, const char *what, const char *suffix) {
    static int cleanup_installed = 0;
    const char *dir = getenv("TMPDIR");
    if (dir == NULL || dir[0] == '\0') dir = "/tmp";

    if (!cleanup_installed) {
        int signals[] = {SIGHUP, SIGINT, SIGTERM};
        atexit(removeTmpFiles);
        for (size_t i = 0; i < sizeof(signals)/sizeof(signals[0]); ++i) {
            if (signal(signals[i], removeTmpFilesOnSignal) == SIG_IGN) {
                signal(signals[i], SIG_IGN);
            }
        }
        cleanup_installed = 1;
    }

    int len = snprintf(path, PATH_MAX, "%s%sktm-%s-XXXXXX%s", dir,
            dir[strlen(dir)-1] == '/' ? "" : "/", what, suffix);
    if (len < 0 || len >= PATH_MAX) {
        path[0] = '\0';
        loggerPanic("Temporary directory path too long: %s\n", dir);
    }
    int fd = mkstemps(path, strlen(suffix));
    if (fd == -1) {
        int err = errno;
        path[0] = '\0';
        loggerPanic("Failed to create a temporary file in %s: %s\n",
                dir, strerror(err));
    }
    return fd;
}

int writeAsmToTmp(AoStr *asmbuf) {
    int fd;
    s64 written = 0;
    u64 towrite = 0;
    char *ptr;

    if (asm_tmp_file[0]) {
        fd = open(asm_tmp_file,O_WRONLY|O_TRUNC);
        if (fd == -1) {
            loggerPanic("Failed to open file for intermediary assembly: %s\n",
                    strerror(errno));
        }
    } else {
        fd = makeTmpFile(asm_tmp_file, "asm", ".s");
    }

    towrite = asmbuf->len;
    ptr = asmbuf->data;

    while (towrite > 0) {
        written = write(fd,ptr,towrite);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            close(fd);
            loggerPanic("Failed to create file for intermediary assembly: %s\n",
                    strerror(errno));
        }
        towrite -= written;
        ptr += written;
    }
    close(fd);
    return 1;
} 

/* File `to` as a path relative to directory `from`, both real paths:
 * up from `from` to their longest common directory, then down */
static char *relativePath(const char *from, const char *to) {
    size_t common = 0, i = 0;
    while (from[i] && from[i] == to[i]) {
        if (from[i] == '/') common = i;
        i++;
    }
    if (from[i] == '\0' && to[i] == '/') common = i;
    AoStr *rel = aoStrNew();
    for (const char *p = from + common; *p; ++p) {
        if (*p == '/') aoStrCatPrintf(rel, "../");
    }
    aoStrCatPrintf(rel, "%s", to + common + 1);
    return aoStrMove(rel);
}

/* `ktm -lib a.b dir/a/b`: liba.b.a and the shared object next to the
 * module's sources; `ktm -lib name dir` over a tree: libname.a and the
 * shared object in dir, from every module under it (libktm: in the
 * install directory's lib/, see below). Each module's
 * directory gets a .ktmlib marker an importer finds the library by,
 * relative to the marker. The object file is a temporary one, nothing
 * is installed, and the commands are printed only with -v. */
static void emitModuleLibrary(Cctrl *cc, AoStr *asmbuf, CliArgs *args) {
    writeAsmToTmp(asmbuf);
    char *dir = realpath(args->module_dir, NULL);
    if (!dir) dir = args->module_dir;
#if IS_BSD
    char *shared = mprintf("%s/lib%s.dylib", dir, args->lib_name);
    char *shared_flags = "-dynamiclib -undefined dynamic_lookup";
#else
    char *shared = mprintf("%s/lib%s.so", dir, args->lib_name);
    char *shared_flags = "-shared -Wl,-Bsymbolic";
#endif
    char *archive = mprintf("%s/lib%s.a", dir, args->lib_name);
    char *shared_libs = "";
    if (cc->is_libktm && args->lib_tree_dirs) {
        /* `ktm -lib ktm <prefix>/modules`: the stdlib, into <prefix>/lib
         * as ktmLibInit names it: the archive `-lktm` finds, and only the
         * versioned shared object the JIT loads (an unversioned one would
         * make `-lktm` link dynamically) */
        char *lib_dir = mprintf("%s/lib", args->install_dir);
        safeSystem(mprintf("mkdir -p %s", lib_dir), args->verbose);
        char *real = realpath(lib_dir, NULL);
        if (real) lib_dir = real;
        archive = mprintf("%s/libktm.a", lib_dir);
#if IS_BSD
        shared = mprintf("%s/libktm.0.0.1.dylib", lib_dir);
        shared_flags = mprintf("-dynamiclib -Wl,-install_name,%s", shared);
#else
        shared = mprintf("%s/libktm.so.0.0.1", lib_dir);
        shared_flags = "-shared -Wl,-Bsymbolic -Wl,-soname,libktm.so.0.0.1";
#endif
        shared_libs = mprintf(CLIBS" %s", linkLibFlags(cc)->data);
    }
    close(makeTmpFile(run_tmp_file, "obj", ".o"));
    char *object = run_tmp_file;
    safeSystem(mprintf("%s -fPIC -c %s -o %s", cc->CC, asm_tmp_file, object),
               args->verbose);
    safeSystem(mprintf("rm -f %s && ar rcs %s %s", archive, archive, object),
               args->verbose);
    safeSystem(mprintf("%s -fPIC %s %s -o %s %s", cc->CC, shared_flags, object,
                       shared, shared_libs), args->verbose);

    int count = args->lib_tree_dirs ? (int)args->lib_tree_dirs->size : 1;
    for (int i = 0; i < count; ++i) {
        char *mod_dir = args->lib_tree_dirs
                      ? (char *)args->lib_tree_dirs->entries[i] : dir;
        char *marker = mprintf("%s/.ktmlib", mod_dir);
        FILE *mf = fopen(marker, "w");
        if (!mf) {
            loggerPanic("Failed to write '%s': %s\n", marker, strerror(errno));
        }
        fprintf(mf, "static %s\nshared %s\n", relativePath(mod_dir, archive),
                relativePath(mod_dir, shared));
        /* its globals' run-time initialisers are its `_init` (M2) */
        char *real = realpath(args->prebuild_dir ? args->prebuild_dir : mod_dir, NULL);
        if (cc->init_module_dirs && setHas(cc->init_module_dirs, real ? real : mod_dir)) {
            fprintf(mf, "init\n");
        }
        free(real);
        fclose(mf);
    }
}

void emitFile(Cctrl *cc, AoStr *asmbuf, CliArgs *args) {
    AoStr *cmd = aoStrNew();
    ktmLib lib;
    if (args->emit_object) {
        writeAsmToTmp(asmbuf);
        char *object_file_name = args->output_filename ? 
                                 args->output_filename :
                                 args->obj_outfile;
        char *fPIC = args->fPIC ? "-fPIC -shared" : "";
        aoStrCatPrintf(cmd, "%s -c %s %s "CLIBS" %s -o ./%s",
                cc->CC,
                fPIC,
                asm_tmp_file,
                args->clibs,
                object_file_name);
        safeSystem(cmd->data, 0);
    } else if (args->asm_outfile && args->assemble_only) {
        int fd;
        u64 flags = O_CREAT|O_RDWR|O_TRUNC;

        if (args->to_stdout) {
            fd = STDOUT_FILENO;
        } else if (args->output_filename != NULL) {
            fd = open(args->output_filename, flags, 0644);
        } else {
            fd = open(args->asm_outfile, flags, 0644);
        }
        if (fd == -1) {
            loggerPanic("Failed to open '%s' - %s\n",
                args->asm_outfile, strerror(errno));
        }
        s64 written = write(fd,asmbuf->data,asmbuf->len);
        if (written != (s64)asmbuf->len) {
            loggerPanic("Failed to write data expected %ld got %ld\n",
                    (s64)asmbuf->len, written);
        }
        close(fd);
    } else if (args->emit_dylib && args->module_dir) {
        emitModuleLibrary(cc, asmbuf, args);
    } else if (args->emit_dylib) {
        writeAsmToTmp(asmbuf);
        ktmLibInit(cc, &lib,args,args->lib_name);
        aoStrCatPrintf(cmd, "%s -shared -fPIC -c %s -o ./%s",
                cc->CC,
                asm_tmp_file,args->obj_outfile);
        safeSystem(cmd->data, args->verbose);
        /* The archive first: install_cmd copies it, so building it
         * afterwards would ship a stale (or absent) one. */
        safeSystem(lib.stylib_cmd, args->verbose);
        safeSystem(lib.dylib_cmd, args->verbose);
        safeSystem(lib.install_cmd, args->verbose);
    } else {
        AoStr *ofiles = aoStrNew();
        AoStr *link_flags = linkLibFlags(cc);

        /* Concatinate a string with all of the .o and .so files. The
         * archives in a group: a library can use one that comes before
         * it (a prebuilt package and another it imports, linked in the
         * order they were found); ld64 searches them all anyway */
        if (!listEmpty(cc->object_files)) {
#if !IS_BSD
            aoStrCatFmt(ofiles, "-Wl,--start-group ");
#endif
            listForEach(cc->object_files) {
                AoStr *o = it->value;
                aoStrCatFmt(ofiles, "%s ", o->data);
            }
#if !IS_BSD
            aoStrCatFmt(ofiles, "-Wl,--end-group ");
#endif
        }
        if (!listEmpty(cc->shared_object_files)) {
            listForEach(cc->shared_object_files) {
                AoStr *so = it->value;
                aoStrCatFmt(ofiles, "%s ", so->data);
            }
        }

        if (ofiles->len == 0) {
            aoStrPutChar(ofiles, ' ');
        }

        if (args->run) {
            /* Link to a private temporary binary rather than ./a.out so a
             * user's a.out is never clobbered, run it with the extra
             * command line arguments and exit with its status. */
            char *bin = run_tmp_file;
            close(makeTmpFile(bin, "run", ""));

            writeAsmToTmp(asmbuf);
            aoStrCatPrintf(cmd, "%s -L%s/lib %s %s %s %s -lktm "CLIBS" -o %s",
                    cc->CC,
                    args->install_dir,
                    asm_tmp_file,
                    ofiles->data,
                    link_flags->data,
                    args->clibs ? args->clibs : "",
                    bin);
            int link_ok = linkCommand(cmd->data);
            unlink(asm_tmp_file);
            asm_tmp_file[0] = '\0';
            if (link_ok != 0) {
                noteUndefinedPrototypes(cc);
                loggerPanic("Failed to execute command: '%s'\n", cmd->data);
            }

            /* Same argv as -jit: the source file then any trailing args. */
            char **run_argv = malloc(sizeof(char *) * (args->argc + 2));
            int i = 0;
            run_argv[i++] = args->infile;
            listForEach(args->argv) {
                run_argv[i++] = it->value;
            }
            run_argv[i] = NULL;

            fflush(stdout);
            fflush(stderr);
            int status = 0;
            pid_t pid = fork();
            if (pid == -1) {
                loggerPanic("Failed to fork: %s\n", strerror(errno));
            } else if (pid == 0) {
                execv(bin, run_argv);
                fprintf(stderr, "ktm: failed to run '%s': %s\n",
                        bin, strerror(errno));
                _exit(127);
            }
            while (waitpid(pid, &status, 0) == -1 && errno == EINTR);
            removeTmpFiles();
            free(run_argv);

            if (WIFEXITED(status)) {
                exit(WEXITSTATUS(status));
            } else if (WIFSIGNALED(status)) {
                /* Mirror the shell's convention for a signalled child */
                exit(128 + WTERMSIG(status));
            }
            exit(EXIT_FAILURE);
        }

        writeAsmToTmp(asmbuf);

        if (args->output_filename == NULL) {
            args->output_filename = "a.out";
        }
        char *clibs = args->clibs ? args->clibs : "";

        aoStrCatPrintf(cmd, "%s -L%s/lib %s %s %s %s -lktm "CLIBS" -o %s",
                cc->CC,
                args->install_dir,
                asm_tmp_file,
                ofiles->data,
                link_flags->data,
                clibs,
                args->output_filename);

        if (linkCommand(cmd->data) != 0) {
            noteUndefinedPrototypes(cc);
            loggerPanic("Failed to execute command: '%s'\n", cmd->data);
        }
    }
    removeTmpFiles();
    aoStrRelease(cmd);
    aoStrRelease(asmbuf);
}

void assemble(Cctrl *cc, CliArgs *args) {
    AoStr *run_cmd = aoStrNew();

    if (args->run) {
        /* Link straight from the input into a private temporary binary
         * (not ./a.out, which concurrent runs would share). */
        close(makeTmpFile(run_tmp_file, "run", ""));
        aoStrCatPrintf(run_cmd,
                "%s -L"INSTALL_PREFIX"/lib %s "CLIBS" -lktm -o %s && %s",
                cc->CC,
                args->infile,
                run_tmp_file,
                run_tmp_file);
        int ret = system(run_cmd->data);
        (void)ret;
        removeTmpFiles();
    } else {
        aoStrCatPrintf(run_cmd, "%s %s -L"INSTALL_PREFIX"/lib "CLIBS" -lktm",
                cc->CC, args->infile);
        safeSystem(run_cmd->data, 0);
    }
    aoStrRelease(run_cmd);
}

void memoryInit(void) {
    astMemoryInit();
    lexemeMemoryInit();
    globalArenaInit(4096*10);
    irMemoryInit();
    aoStrTmpBufInit();
}

void memoryRelease(void) {
    astMemoryRelease();
    irMemoryRelease();
    lexemeMemoryRelease();
    globalArenaRelease();
}

void memoryPrintStats(void) {
    printf("============== Arena Stats ===========\n");
    lexemeMemoryStats();
    printf("\n\n");
    astMemoryStats();
    printf("\n\n");
    irMemoryStats();
    printf("\n\n");
    globalArenaPrintStats();
    printf("\n\n");
    aoStrPoolPrintStats();
    printf("=========== Arena Stats End ==========\n");
}

/* A compiler control set up from the command line: the flags, defines
 * and target that every parse of the input uses. */
static Cctrl *mainNewCctrl(CliArgs *args) {
    Cctrl *cc = cctrlNew(args->target);

    cc->install_dir = args->install_dir;
    cc->is_pic = args->fPIC;
    cc->is_library = args->emit_dylib;
    cc->is_libktm = args->emit_dylib && args->lib_name &&
                    !strcmp(args->lib_name, "ktm");

    cc->object_files = args->object_files;
    cc->shared_object_files = args->shared_object_files;

    /* Plumb in support for cross compiling... currently does nothing :)*/
    const char *str_target = cliTargetToString(args->target);
    if (args->is_cross_compile) {
        switch (args->target) {
            /* Let clang do the heavy lifting */
            case TARGET_AARCH64_UNKNOWN_LINUX_GNU:
            case TARGET_X86_64_APPLE_DARWIN:
            case TARGET_X86_64_UNKNOWN_LINUX_GNU:
            case TARGET_AARCH64_APPLE_DARWIN:
                cc->CC = mprintf("clang --target=%s", str_target);
                break;
        }
    } else {
        cc->CC = mprintf("cc");
    }

    if (args->use_legacy_x86) {
        fprintf(stderr, "ktm: --use-legacy-x86 was removed in v0.4.0, the "
                        "default x86_64 backend is the only one\n");
        exit(EXIT_FAILURE);
    }
    if (args->werror)         cc->flags |= CCTRL_WERROR;
    if (args->memsafe)        cc->flags |= CCTRL_MEMSAFE;

    /* Exactly one of the two is always defined; `#ifjit` / `#ifaot`
     * key off them. The REPL runs on the JIT, so it counts. */
    if (args->jit || args->repl) {
        cctrlAddDefine(cc, "__KTM_JIT__");
    } else {
        cctrlAddDefine(cc, "__KTM_AOT__");
    }

    if (!listEmpty(args->defines_list)) {
        cctrlSetCommandLineDefines(cc,args->defines_list);
    }
    return cc;
}

int main(int argc, char **argv) {
    memoryInit();
    is_terminal = isatty(STDOUT_FILENO) && isatty(STDERR_FILENO);

    /* Using these pools vastly simplifies everything as we can free everything 
     * at the end. */

    int lexer_flags = CCF_PRE_PROC;
    AoStr *asmbuf;
    Cctrl *cc;

    /* `ktm mod ...` and the other package commands */
    int pkg_argc = 0;
    char **pkg_argv = NULL;
    int pkg_rc = pkgMain(argc, argv, &pkg_argc, &pkg_argv);
    if (pkg_rc >= 0) {
        memoryRelease();
        return pkg_rc;
    }
    if (pkg_rc == -2) {
        /* `ktm build` / `ktm run`: the compile they stand for */
        argc = pkg_argc;
        argv = pkg_argv;
    }

    CliArgs args;
    cliArgsInit(&args);
    /* now parse cli options */
    args.install_dir = INSTALL_PREFIX;
    cliParseArgs(&args,argc,argv);
    parse_expand_generated = args.expand;

    /* The LSP owns its Cctrl lifecycle (fresh one after a parser
     * crash), so it dispatches before the shared `cc` is made. */
    if (args.lsp) {
        int rc = lspRun(&args);
        memoryRelease();
        return rc;
    }

    cc = mainNewCctrl(&args);

    if (args.assemble) {
        assemble(cc, &args);
        goto success;
    }

    if (args.repl) {
#ifdef KTM_ENABLE_JIT
        int rc = replRun(cc, &args);
        memoryRelease();
        return rc;
#else
        fprintf(stderr, "ktm: this build has no JIT; -repl is unavailable "
                "(rebuild with -DKTM_ENABLE_JIT=on)\n");
        memoryRelease();
        return 1;
#endif
    }

    if (args.print_tokens) {
        compileToTokens(cc,&args,lexer_flags);
        goto success;
    }

    if (!compileToAst(cc,&args,lexer_flags)) {
        /* Parser surfaced one or more errors and the diagnostics
         * have already been printed. Bail before codegen. */
        memoryRelease();
        return 1;
    }

    if (args.print_ast) {
        compilePrintAst(cc);
        goto success;
    }

    if (args.dump_ir) {
        irDump(cc);
        goto success;
    }

#ifdef KTM_ENABLE_JIT
    if (args.jit) {
        /* Arm the tracking allocator before compiling - the JIT's
         * finalize binds MAlloc/Free call sites at chunk compile. */
#if defined(__aarch64__) || defined(__arm64__)
        KtmJit *jit = aarch64JitCompile(cc);
#elif defined(__x86_64__)
        KtmJit *jit = x86_64JitCompile(cc);
#else
        KtmJit *jit = NULL;
        fprintf(stderr, "ktm: -jit is not supported on this host architecture\n");
#endif
        if (!jit) {
            /* The backend prints its own diagnostic. */
            memoryRelease();
            return 1;
        }

        listPrepend(args.argv, args.infile);
        args.argc++;

        char **jit_argv = malloc(sizeof(char *) * args.argc);
        int i = 0;
        listForEach(args.argv) {
            jit_argv[i++] = it->value;
        }
        if (args.memsafe) memsafeReportAtExit();
        int rc = ktmJitRunMain(jit, args.argc, jit_argv);
        if (args.memsafe) memsafeReportLeaks(stderr);
        ktmJitFree(jit);
        memoryRelease();
        return rc;
    }
#endif
    /* -jit returns above and -repl returned earlier; reaching here
     * with the flag set means an AOT-style invocation. */
    if (args.memsafe)
        fprintf(stderr,
                "ktm: -Memsafe currently requires -jit or -repl; ignored\n");

    if (args.cfg_create || args.cfg_create_png || args.cfg_create_svg) {
        Vec *cfgs = cfgConstruct(cc);
        char *dot_outfile = mprintf("./%s.dot",args.infile_no_ext);
        cfgsToFile(cfgs,dot_outfile);
        if (args.cfg_create_png || args.cfg_create_svg) {
            char *ext = args.cfg_create_png ? "png" : "svg";
            char *dot_cmd = mprintf("dot -T%s %s -o ./%s.%s",
                    ext,dot_outfile,args.infile_no_ext,ext);
            printf("Creating %s: %s\n",ext,dot_cmd);
            safeSystem(dot_cmd, 0);
            unlink(dot_outfile);
        }
        goto success;
    }

    asmbuf = compileToAsm(cc);
    emitFile(cc, asmbuf, &args);

    if (args.defines_list) {
        listRelease(args.defines_list,NULL);
    }

success:
    if (args.print_mem_stats) {
        memoryPrintStats();
    }
    memoryRelease();
    return 0;
}
