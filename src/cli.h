#ifndef CLI_H
#define CLI_H

#include <stdint.h>
#include <stdarg.h>

#include "config.h"
#include "containers.h"
#include "list.h"
#include "types.h"

enum CliTarget {
    TARGET_AARCH64_APPLE_DARWIN,
    TARGET_AARCH64_UNKNOWN_LINUX_GNU,
    TARGET_X86_64_APPLE_DARWIN,
    TARGET_X86_64_UNKNOWN_LINUX_GNU,
};

enum CliType {
    CLI_STRING,
    CLI_INT,
    CLI_FLAG,
};

enum CliArgType {
    CLI_ASM_DEBUG_COMMENTS,
    CLI_ASSEMBLE,
    CLI_ASSEMBLE_ONLY,
    CLI_CFG_CREATE,
    CLI_CFG_CREATE_PNG,
    CLI_CFG_CREATE_SVG,
    CLI_CLIBS,
    CLI_DEFINES_LIST,
    CLI_DUMP_IR,
    CLI_EXPAND,
    CLI_EMIT_DYLIB,
    CLI_EMIT_OBJECT,
    CLI_fPIC,
    CLI_HELP,
    CLI_INSTALL_DIR,
    CLI_JIT,
    CLI_LSP,
    CLI_MEMSAFE,
    CLI_MEM_STATS,
    CLI_TEST_DIR,
    CLI_TEST_ROOT,
    CLI_PREBUILD_PKG,
    CLI_PREBUILD_MOD,
    CLI_PREBUILD_DIR,
    CLI_OUTPUT_FILENAME,
    CLI_PRINT_AST,
    CLI_PRINT_TOKENS,
    CLI_REPL,
    CLI_RUN,
    CLI_TARGET,
    CLI_TO_STDOUT,
    CLI_USE_LEGACY_X86,
    CLI_VERSION,
    CLI_VERBOSE,
    CLI_WERROR,
};

typedef struct CliValue {
    enum CliType type;
    union {
        char *str;
        int integer;
        int boolean;
    };
} CliValue;

typedef struct CliParser {
    char *optname;
    u64 optlen;
    int arg_count;
    enum CliArgType arg_type; 
    char *usage;
    char *help;
    int (*parse)(struct CliValue *value, char *rawarg);
} CliParser;

typedef struct CliArgs {
    int print_ast;
    int print_tokens;
    int print_help;
    int print_mem_stats;
    int cfg_create;
    int cfg_create_png;
    int cfg_create_svg;
    int dump_ir;
    int expand;
    int asm_debug_comments;
    int assemble_only;
    int emit_dylib;
    int emit_object;
    int run;
    int jit;
    int lsp;
    int memsafe;
    int repl;
    int assemble;
    int to_stdout;
    int use_legacy_x86;
    int fPIC;
    int werror;
    char *infile;
    char *infile_no_ext;
    char *asm_outfile;
    char *obj_outfile;
    char *lib_name;
    /* `ktm -lib a.b dir/a/b`: build the module in dir/a/b into a library
     * there, with a .ktmlib marker importers link it by */
    char *module_dir;
    /* `ktm test` compiles its runner with these: the module whose
     * *_test.ktm files are read, and where modules are found from */
    char *test_dir;
    char *test_root;
    /* A package prebuilt into the cache (lexer.c lexPackagePrebuilt):
     * its import path, module path and directory; -lib <name> <dir>
     * then writes its library and marker into dir */
    char *prebuild_pkg;
    char *prebuild_mod;
    char *prebuild_dir;
    char *output_filename;
    char *clibs;
    char *install_dir;
    int is_cross_compile;
    /* -v: print the commands that build a library */
    int verbose;
    /* `ktm -lib name dir` over a tree of modules: their paths and
     * directories (the library is dir/lib<name>) */
    Vec *lib_tree_paths;
    Vec *lib_tree_dirs;
    enum CliTarget target;
    List *defines_list;
    List *object_files;
    List *shared_object_files;
    int argc;
    List *argv;
} CliArgs;

void cliArgsInit(CliArgs *args);
int cliParseArgs(struct CliArgs *cli_args, int argc, char **argv);
int cliOptionTakesValue(char *arg);
__noreturn void cliPanicGeneric(const char *const_msg, const char *fmt, va_list ap);

AoStr *cliConcatArgv(CliArgs *args);
const char *cliTargetToString(enum CliTarget target);

#endif
