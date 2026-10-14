#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "aostr.h"
#include "ast.h"
#include "cctrl.h"
#include "cli.h"
#include "compile.h"
#include "memory.h"
#include "asm.h"
#include "lexer.h"
#include "list.h"
#include "parser.h"
#include "util.h"

void compilePrintAst(Cctrl *cc) {
    List *it;
    Ast *ast;
    char *tmp;

    printf("AST: \n");

    it = cc->ast_list->next;
    while (it != cc->ast_list) {
        ast = (Ast *)it->value;
        if (ast == NULL) {
            printf("here\n");
        }

        if (ast->kind != AST_FUN_PROTO &&
            ast->kind != AST_EXTERN_FUNC && 
            ast->kind != AST_ASM_FUNC_BIND)
        {
            tmp = astToString(ast);
            printf("%s\n", tmp);
        }
        it = it->next;
    }
}

AoStr *compileToAsm(Cctrl *cc) {
    if (cc->ast_list == NULL) {
        loggerWarning("Create AST before compiling AST\n");
        return NULL;
    }

    AoStr *asmbuf = asmGenerate(cc);
    return asmbuf;
}

void compileToTokens(Cctrl *cc, CliArgs *args, int lexer_flags) {
    Lexer *l = (Lexer *)globalArenaAllocate(sizeof(Lexer));
    char *root_dir = mprintf("%s/include/", args->install_dir);

    lexInit(l,NULL,CCF_PRE_PROC|lexer_flags);
    l->lineno = 1;
    lexSetBuiltinRoot(l,root_dir);

    lexPushFile(l,aoStrDupRaw(args->infile,strlen(args->infile)));

    Lexeme *token = NULL;
    while ((token = lexToken(cc->macro_defs,l))) {
        lexemePrint(token);
    }

    setRelease(l->seen_files);
}

int compileToAst(Cctrl *cc, CliArgs *args, int lexer_flags) {
    Lexer *l = (Lexer *)globalArenaAllocate(sizeof(Lexer));
    char *root_dir = mprintf("%s/include/", args->install_dir);

    lexInit(l,NULL,CCF_PRE_PROC|lexer_flags);
    l->lineno = 1;
    lexSetBuiltinRoot(l,root_dir);
    if (args->test_dir) l->test_dir = realpath(args->test_dir, NULL);
    if (args->test_root) l->program_root = mprintf("%s", args->test_root);

    if (args->module_dir) {
        /* `ktm -lib a.b dir/a/b`: compile the module as a program that
         * imports it, found from dir (dir/a/b less the a/b) */
        char *src;
        if (lexSetModuleLibRoot(l, args->module_dir, args->lib_name)) {
            src = mprintf("import %s;\n", args->lib_name);
        } else {
            /* `ktm -lib name dir`: every module under dir, one library */
            args->lib_tree_paths = vecNew(&vec_unsigned_long_type);
            args->lib_tree_dirs = vecNew(&vec_unsigned_long_type);
            src = lexModuleTreeImports(l, args->module_dir,
                                       args->lib_tree_paths,
                                       args->lib_tree_dirs);
            if (!src) {
                fprintf(stderr, "ktm: %s is neither module %s's directory (one "
                        "ending in %s) nor a tree with modules under it\n",
                        args->module_dir, args->lib_name, args->lib_name);
                return 0;
            }
        }
        lexPushString(l, "<module library>", src, strlen(src));
    } else {
        lexPushFile(l,aoStrDupRaw(args->infile,strlen(args->infile)));
    }
    /* the runtime, after the header and before the program */
    l->no_crash = args->emit_object || args->emit_dylib;
    lexPushRuntime(l);
    cc->crash_report = l->with_crash;
    /* the prelude, read first (the stdlib is modules, read when imported) */
    lexPushPrelude(l);

    cctrlInitParse(cc,l);

    parseToAst(cc);

    /* Print any accumulated diagnostics in source order. If the
     * parser saw real errors, bail before handing a half-built
     * AST to the backend - the IR / codegen still hard-panic on
     * malformed input. */
    int n_errors = cctrlDiagFlush(cc);

    lexReleaseAllFiles(l);
    listRelease(l->files,NULL);
    if (n_errors > 0) return 0;
    return 1;
}
