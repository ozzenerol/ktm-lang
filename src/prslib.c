#include <assert.h>
#include <ctype.h>
#include <limits.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "aostr.h"
#include "ast.h"
#include "cctrl.h"
#include "lexer.h"
#include "list.h"
#include "prsutil.h"
#include "prslib.h"
#include "util.h"

static AstType *parseArrayDimensionsInternal(Cctrl *cc, AstType *base_type);
Ast *parseSubscriptExpr(Cctrl *cc, Ast *ast);

void parseAssignAuto(Cctrl *cc, Ast *ast) {
    if (ast->declinit == NULL) {
        cctrlRaiseException(cc,"auto must have an initaliser");
    }

    if (ast->declinit->kind == AST_FUNC) {
        ast->declvar->type = astMakeFunctionType(
                ast->declinit->type->rettype,
                ast->declinit->params);
        ast->declvar->type->has_var_args = ast->declinit->type->has_var_args;
        ast->declvar->type->rettype->has_var_args = ast->declinit->type->has_var_args;
    //return astMakeFunctionType(rettype,params);
        return;
        cctrlRaiseException(cc,"auto with functions is not yet supported");
    }
    ast->declvar->type = ast->declinit->type;
}

AstType *parseReturnAuto(Cctrl *cc, Ast *retval) {
    switch (retval->kind) {
        case AST_LVAR:
        case AST_STRING:
        case AST_ASM_FUNCALL:
        case AST_FUNPTR_CALL:
        case AST_FUNCALL:
        case AST_FUNPTR:
        case AST_LITERAL:
        case AST_BINOP: {
            return astTypeCopy(retval->type);
        }
        default:
            cctrlRaiseException(cc,"Could not determine return type for %s()",
                    cc->tmp_fname->data);

    }
}

AstType *parsePointerType(Cctrl *cc, AstType *type) {
    Lexeme *tok;
    while (1) {
        tok = cctrlTokenGet(cc);
        if (!tokenPunctIs(tok, '*')) {
            cctrlTokenRewind(cc);
            return type;
        }
        type = astMakePointerType(type);
    }
    return type;
}

/* `fn` is a contextual keyword (ktm syntax v0.2): used as a plain
 * identifier in old code (`U0 (*fn)(...)`, `fn(x)`, `fn = &f`), so it
 * is never registered as a type name. Is `tok` the identifier `fn`? */
int tokenIsFnIdent(Lexeme *tok) {
    return tok && tok->tk_type == TK_IDENT && tok->len == 2 &&
           tok->start[0] == 'f' && tok->start[1] == 'n';
}

/* Can `tok` start an fn type: `fn`, or the `(` of the parenthesised
 * `(fn(...) R)` (to which a `*` after it applies)? */
int tokenMayStartFnType(Lexeme *tok) {
    return tokenIsFnIdent(tok) || tokenPunctIs(tok,'(');
}

/* Does the current, unconsumed `fn` (just peeked, not gotten) start a
 * type here? So does a `(` directly followed by such an `fn`. Only
 * when followed by `(` and a parameter list that is empty, `...` or
 * `...name` only, or starts with a type name, and (when the list is
 * empty or `...`/`...name` only, where the closing `)` is right there
 * to check) a type follows the `)`. A non-empty list starting with a
 * real type is never ambiguous with a call (`fn(x)` with a plain
 * argument `x` is not a type name), so that case does not need to
 * look past its `)`. Gets a handful of tokens and rewinds them all;
 * never consumes. */
int parseFnStartsType(Cctrl *cc) {
    int n = 0, result = 0;
    Lexeme *tok;

    tok = cctrlTokenGet(cc); n++; /* `fn`, or the `(` of `(fn(...) R)` */
    if (tokenPunctIs(tok,'(')) {
        tok = cctrlTokenGet(cc); n++;
        if (!tokenIsFnIdent(tok)) {
            while (n-- > 0) cctrlTokenRewind(cc);
            return 0;
        }
    }
    tok = cctrlTokenGet(cc); n++; /* expect `(` */
    if (tokenPunctIs(tok,'(')) {
        Lexeme *first = cctrlTokenGet(cc); n++;
        if (tokenPunctIs(first,')')) {
            Lexeme *after = cctrlTokenPeek(cc);
            result = after && (cctrlTokIsKeyword(cc, after) ||
                                tokenIsFnIdent(after));
        } else if (tokenPunctIs(first,TK_ELLIPSIS)) {
            Lexeme *close = cctrlTokenGet(cc); n++;
            /* `...name`, the named untyped form */
            if (close && close->tk_type == TK_IDENT) {
                close = cctrlTokenGet(cc); n++;
            }
            if (tokenPunctIs(close,')')) {
                Lexeme *after = cctrlTokenPeek(cc);
                result = after && (cctrlTokIsKeyword(cc, after) ||
                                    tokenIsFnIdent(after));
            }
        } else if (first && (cctrlTokIsKeyword(cc, first) ||
                              tokenIsFnIdent(first))) {
            /* `Name.member` (an enum member, ktm syntax v0.2's Y6) is an
             * expression, not a type: `fn(Color.blue)` is a call through
             * a variable named `fn`, not a one-parameter type. */
            Lexeme *after_first = cctrlTokenPeek(cc);
            result = !tokenPunctIs(after_first,'.');
        }
    }
    while (n-- > 0) cctrlTokenRewind(cc);
    return result;
}

/* At a declaration/expression-statement fork, should `fn` here be routed
 * to the declaration side at all, so a malformed one (`fn i64 x;`, a
 * missing `(`) gets parseBaseDeclSpec's precise error instead of being
 * read as the expression `fn` followed by a stray `i64`? True for a
 * real `fn(...)` type, or for `fn` directly followed by a type name
 * (whichever it is, that can never continue a valid expression). */
int parseFnLooksLikeType(Cctrl *cc) {
    if (parseFnStartsType(cc)) {
        return 1;
    }
    if (!tokenIsFnIdent(cctrlTokenPeek(cc))) {
        return 0;
    }
    cctrlTokenGet(cc); /* `fn` */
    Lexeme *after = cctrlTokenPeek(cc);
    int looks_like_type = after && (cctrlTokIsKeyword(cc, after) ||
                                     tokenIsFnIdent(after));
    cctrlTokenRewind(cc);
    return looks_like_type;
}

static int parseIsVarArgElemType(AstType *t);
Ast *parseDefaultFunctionParam(Cctrl *cc, Ast *var);

/* The two hidden parameters of `T ...name` (elem NULL: `...name`, 64-bit
 * slots): the count `name#len` and `name`, a pointer to the caller's
 * array of the extras, flagged AST_FLAG_VARARGS. */
static void parseVarArgsPair(Vec *params, AstType *elem, char *name, int len,
                             Ast **_count, Ast **_data)
{
    if (!elem) {
        elem = astTypeCopy(ast_int_type);
        elem->has_var_args = 1;
    }
    AoStr *len_name = aoStrPrintf("%.*s#len", len, name);
    Ast *count = astLVar(astTypeCopy(ast_int_type), len_name->data,
                         len_name->len);
    Ast *data = astLVar(astMakePointerType(elem), name, len);
    data->flags |= AST_FLAG_VARARGS;
    vecPush(params, count);
    vecPush(params, data);
    if (_count) *_count = count;
    if (_data) *_data = data;
}

/* The parameter TYPES of an `fn(...)` type: `[type {, type}] [, ...]`,
 * an optional, ignored name after each type. Not `parseParams`: that
 * registers names, varargs' argc/argv and defaults into the current
 * scope, which a type (not a real parameter list) must not do. */
static Vec *parseFnTypeParams(Cctrl *cc, int *has_var_args) {
    Vec *params = astVecNew();
    *has_var_args = 0;

    Lexeme *tok = cctrlTokenGet(cc);
    if (tokenPunctIs(tok,')')) {
        return params;
    }
    cctrlTokenRewind(cc);

    while (1) {
        if (tokenPunctIs(cctrlTokenPeek(cc),TK_ELLIPSIS)) {
            cctrlTokenGet(cc);
            /* `...name`: the untyped named form (as `fn(u8 *, ...args)`) */
            Lexeme *name = cctrlTokenPeek(cc);
            if (name && name->tk_type == TK_IDENT) {
                cctrlTokenGet(cc);
                parseVarArgsPair(params, NULL, name->start, name->len, NULL, NULL);
                cctrlTokenExpect(cc,')');
                return params;
            }
            *has_var_args = 1;
            /* The argument-count check (parseFunctionArgumentCheck) finds
             * the vararg point by looking for an AST_VAR_ARGS entry in
             * params, not just the has_var_args flag; unlike parseParams,
             * its argc/argv are not registered anywhere (there is no
             * real function body here to bind them in). */
            vecPush(params, astVarArgs());
            cctrlTokenExpect(cc,')');
            return params;
        }
        AstType *type = parseDeclSpec(cc);
        /* `T ...` (a name may follow): the typed named form */
        if (tokenPunctIs(cctrlTokenPeek(cc),TK_ELLIPSIS)) {
            Lexeme *ell = cctrlTokenGet(cc);
            if (!parseIsVarArgElemType(type)) {
                cctrlRaiseExceptionAt(cc, ell->line, ell->col, 3,
                    "the elements of `...` must be numbers or pointers, got %s",
                    astTypeToString(type));
            }
            Lexeme *name = cctrlTokenPeek(cc);
            if (name && name->tk_type == TK_IDENT) {
                cctrlTokenGet(cc);
                parseVarArgsPair(params, type, name->start, name->len, NULL, NULL);
            } else {
                parseVarArgsPair(params, type, "_", 1, NULL, NULL);
            }
            cctrlTokenExpect(cc,')');
            return params;
        }
        type = parseArrayDimensions(cc,type);
        if (type->kind == AST_TYPE_ARRAY) {
            type = astMakePointerType(type->ptr);
        }
        /* An optional parameter name, ignored, then an optional
         * default, filled in by a call through it that leaves the
         * argument out, as for `T (*f)(T x = 1)` */
        Lexeme *maybe_name = cctrlTokenPeek(cc);
        if (maybe_name && maybe_name->tk_type == TK_IDENT) {
            cctrlTokenGet(cc);
        }
        Ast *param = astLVar(type,"_unknown",7);
        if (tokenPunctIs(cctrlTokenPeek(cc),'=')) {
            cctrlTokenGet(cc);
            param = parseDefaultFunctionParam(cc,param);
        }
        vecPush(params, param);

        tok = cctrlTokenGet(cc);
        if (tokenPunctIs(tok,')')) {
            return params;
        }
        if (!tokenPunctIs(tok,',')) {
            cctrlRaiseException(cc,
                    "Expected `,` or `)` in `fn(...)`'s parameter list, got `%s`",
                    lexemeAsWritten(tok));
        }
    }
}

/* `fn ( [type {, type}] [, ...] ) RetType`. Entry: the current,
 * unconsumed token is the identifier `fn`. RetType is any type,
 * including another `fn` type, and a `*` after it is part of it:
 * `fn(u8 *) u8 *` returns a u8 *. A pointer to a function pointer
 * puts the fn type in parentheses, `(fn(i64) i64) *slot` (see
 * parseBaseDeclSpec). */
AstType *parseFnType(Cctrl *cc) {
    cctrlTokenGet(cc); /* `fn` */
    Lexeme *open = cctrlTokenGet(cc);
    if (!tokenPunctIs(open,'(')) {
        cctrlRaiseException(cc,"Expected `(` after `fn`, got `%s`",
                lexemeAsWritten(open));
    }

    int has_var_args = 0;
    Vec *params = parseFnTypeParams(cc,&has_var_args);

    Lexeme *ret_peek = cctrlTokenPeek(cc);
    if (!ret_peek || (!cctrlTokIsKeyword(cc, ret_peek) &&
                       !tokenIsFnIdent(ret_peek)))
    {
        cctrlRaiseException(cc,
                "Expected a return type after `fn(...)`, got `%s`",
                ret_peek ? lexemeAsWritten(ret_peek) : "end of input");
    }
    AstType *rettype = parsePointerType(cc,parseBaseDeclSpec(cc));

    AstType *fn_type = astMakeFunctionType(rettype,params);
    fn_type->has_var_args = has_var_args;
    return fn_type;
}

AstType *parseFunctionPointerType(Cctrl *cc,
                                  char **fnptr_name,
                                  int *fnptr_name_len,
                                  AstType *rettype)
{
    int has_var_args, depth = 0;
    Lexeme *fname;
    cctrlTokenExpect(cc,'*');
    /* `T (**name)(params)`: a pointer to a function pointer, one more
     * pointer for each further `*` */
    while (tokenPunctIs(cctrlTokenPeek(cc), '*')) {
        cctrlTokenGet(cc);
        depth++;
    }
    fname = cctrlTokenGetRequired(cc);
    if (fname->tk_type != TK_IDENT) {
        /* `T (*)(params)`: report it at the token in place of the name
         * and go on as if it had none. Unwinding here would resync
         * inside the parameter list (`(I64 x)` starts like a new
         * declaration) and report what follows as a second error. */
        char *msg = mprintf("Expected function pointer name got: %s",
                            lexemeToString(fname));
        AoStr *bold = aoStrNew();
        aoStrCatColoured(bold, ESC_BOLD, msg);
        AoStr *buf = cctrlCreateErrorLineAt(cc, fname->line, fname->col,
                                            fname->len, bold->data,
                                            CCTRL_ERROR, NULL);
        aoStrRelease(bold);
        CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, NULL);
        d->line = d->end_line = fname->line;
        d->col = fname->col;
        d->end_col = fname->col + fname->len;
        cctrlDiagPush(cc, d);
        /* A `)` (or `[`) belongs to the declarator */
        if (fname->tk_type == TK_PUNCT) {
            cctrlTokenRewind(cc);
        }
        *fnptr_name = "";
        *fnptr_name_len = 0;
    } else {
        *fnptr_name = fname->start;
        *fnptr_name_len = fname->len;
    }
    /* An array of function pointers `T (*name[N])(params)`: the element
     * type is filled in with the parameters once they are parsed. */
    AstType *fn_type = astMakeFunctionType(rettype, NULL);
    AstType *elem = fn_type;
    for (int i = 0; i < depth; ++i) elem = astMakePointerType(elem);
    AstType *type = parseArrayDimensions(cc,elem);
    cctrlTokenExpect(cc,')');
    cctrlTokenExpect(cc,'(');
    fn_type->params = parseParams(cc,')',&has_var_args,0);
    return type;
}

Ast *parseFunctionPointer(Cctrl *cc, AstType *rettype) {
    Ast *ast;
    char *fnptr_name;
    int fnptr_name_len;
    AstType *fnptr_type;

    fnptr_type = parseFunctionPointerType(cc,
            &fnptr_name,
            &fnptr_name_len,
            rettype);

    /* An array of function pointers, or a pointer to one, is an
     * ordinary variable */
    if (fnptr_type->kind == AST_TYPE_ARRAY ||
        fnptr_type->kind == AST_TYPE_POINTER) {
        ast = astLVar(fnptr_type,fnptr_name,fnptr_name_len);
        return ast;
    }

    Vec *params = fnptr_type->params;
    //fnptr_type = astMakeFunctionType(fnptr_type, params);

    ast = astFunctionPtr(
            fnptr_type,
            fnptr_name,
            fnptr_name_len,
            params);
    return ast;
}

Ast *parseDefaultFunctionParam(Cctrl *cc, Ast *var) {
    if (var->type->kind == AST_TYPE_ARRAY) {
        cctrlRaiseException(cc,"Cannot have a default value of type array");
    }
    Ast *init = parseExpr(cc,16);
    return astFunctionDefaultParam(var,init);
}

/* Whether a `T ...name` element type fits the caller's array: a number
 * or a pointer (also an enum or an intrinsic class), not a struct */
static int parseIsVarArgElemType(AstType *t) {
    if (!t) return 0;
    switch (t->kind) {
        case AST_TYPE_INT: case AST_TYPE_CHAR: case AST_TYPE_FLOAT:
        case AST_TYPE_POINTER: case AST_TYPE_FUNC:
            return 1;
        case AST_TYPE_CLASS:
            return t->is_enum || t->is_intrinsic;
        default:
            return 0;
    }
}

/* `T ...name` or `...name`, with the `...` just consumed (elem NULL for
 * the untyped form): two hidden ordinary parameters, the count `name#len`
 * (no source can name it) and `name`, a pointer to the caller's array of
 * the extras (see parseVarArgsPack). Untyped elements are 64-bit slots
 * like the old argv. Must be the last parameter. */
static void parseNamedVarArgs(Cctrl *cc, Vec *params, AstType *elem,
                              Lexeme *ellipsis, s64 terminator, int store)
{
    int line = ellipsis->line, col = ellipsis->col;
    Lexeme *name = cctrlTokenGet(cc);
    if (!name || name->tk_type != TK_IDENT) {
        cctrlRaiseExceptionAt(cc, line, col, 3,
            "a variadic parameter needs a name, as in `i64 ...nums` or `...args`");
    }
    if (cc->flags & CCTRL_TRANSPILING) {
        cc->current_recovery = NULL;
        cctrlRaiseExceptionAt(cc, line, col, 3,
            "`...%.*s` is not supported with -transpile", name->len, name->start);
    }
    if (elem && !parseIsVarArgElemType(elem)) {
        cctrlRaiseExceptionAt(cc, name->line, name->col, name->len,
            "the elements of `...%.*s` must be numbers or pointers, got %s",
            name->len, name->start, astTypeToString(elem));
    }
    Ast *count, *data;
    parseVarArgsPair(params, elem, name->start, name->len, &count, &data);
    count->line = data->line = name->line;
    count->col = data->col = name->col;
    if (store) {
        if (!mapAddOrErr(cc->localenv, data->lname->data, data)) {
            cctrlRaiseException(cc, "variable %s already declared",
                                astLValueToString(data, 0));
        }
        mapAdd(cc->localenv, count->lname->data, count);
    }
    if (cc->tmp_locals) {
        listAppend(cc->tmp_locals, count);
        listAppend(cc->tmp_locals, data);
    }

    Lexeme *tok = cctrlTokenGet(cc);
    if (!tokenPunctIs(tok, terminator)) {
        cctrlRaiseExceptionAt(cc, line, col, 3,
            "`...%.*s` must be the last parameter", name->len, name->start);
    }
}

Vec *parseParams(Cctrl *cc, s64 terminator, int *has_var_args, int store) {
    Vec *params = astVecNew();

    Lexeme *tok, *pname;
    AstType *type;
    Ast *var;
    int arg_count = 0;

    tok = cctrlTokenGet(cc);
    if (tokenPunctIs(tok,terminator)) {
        return params;
    }
    cctrlTokenRewind(cc);
    
    while (1) {
        pname = cctrlTokenPeek(cc);

        /* VarArgs */
        if (tokenPunctIs(pname, TK_ELLIPSIS)) {
            Lexeme ellipsis = *cctrlTokenGet(cc);
            Lexeme *after = cctrlTokenPeek(cc);
            if (after && after->tk_type == TK_IDENT) {
                parseNamedVarArgs(cc, params, NULL, &ellipsis, terminator, store);
                return params;
            }
            var = astVarArgs();
            mapAdd(cc->localenv,var->argc->lname->data,var->argc);
            mapAdd(cc->localenv,var->argv->lname->data,var->argv);
            
            vecPush(params, var);

            if (cc->tmp_locals) {
                listAppend(cc->tmp_locals, var->argc);
                listAppend(cc->tmp_locals, var->argv);
            }
            *has_var_args = 1;
            /* Var args _has_ to be last */
            cctrlTokenExpect(cc,terminator);
            return params;
        }

        int param_is_const = 0;
        Lexeme *const_tok = cctrlTokenPeek(cc);
        if (const_tok && const_tok->tk_type == TK_KEYWORD &&
            const_tok->i64 == KW_CONST)
        {
            cctrlTokenGet(cc);
            param_is_const = 1;
        }
        type = parseDeclSpec(cc);
        int pinned_kind;
        AoStr *pinned_reg;
        parseRegModifier(cc, &pinned_kind, &pinned_reg);
        pname = cctrlTokenGet(cc);
        if (pname == NULL) {
            cctrlRaiseException(cc,
                    "Unexpected end of input in parameter list");
        }
        if (tokenPunctIs(pname, TK_ELLIPSIS)) {
            Lexeme ellipsis = *pname;
            parseNamedVarArgs(cc, params, type, &ellipsis, terminator, store);
            return params;
        }

        if (tokenPunctIs(pname, ')') && arg_count == 0) {
            if (type->kind == AST_TYPE_VOID) {
                return params;
            }
        }

        arg_count++;
        /* a parameter may shadow an import prefix, see cctrlTokenGet */
        if (pname->tk_type != TK_IDENT) {
            /* Function pointer */
            if (tokenPunctIs(pname, '(')) {
                type = parseArrayDimensions(cc,type);
                if (type->kind == AST_TYPE_ARRAY) {
                    type = astMakePointerType(type->ptr);
                }
                var = parseFunctionPointer(cc, type);
                int is_array = var->kind == AST_LVAR;
                if (is_array) {
                    /* An array parameter is a pointer to its first element */
                    var->type = astMakePointerType(var->type->ptr);
                }
                AoStr *var_name = is_array ? var->lname : var->fname;
                /* A nameless one was reported by parseFunctionPointerType */
                if (var_name->len &&
                    !mapAddOrErr(cc->localenv,var_name->data,var)) {
                    cctrlRaiseException(cc,"variable %s already declared",
                            astLValueToString(var,0));
                }
                if (cc->tmp_locals) {
                    listAppend(cc->tmp_locals, var);
                }

                tok = cctrlTokenGet(cc);
                if (tokenPunctIs(tok, '=') && is_array) {
                    vecPush(params, parseDefaultFunctionParam(cc,var));
                    tok = cctrlTokenGet(cc);
                } else if (tokenPunctIs(tok, '=')) {
                    Ast *default_fnptr = parseDefaultFunctionParam(cc,var);
                    var->default_fn = default_fnptr;
                    vecPush(params, default_fnptr);
                    tok = cctrlTokenGet(cc);
                } else {
                    vecPush(params, var);
                }

                if (tokenPunctIs(tok, terminator)) {
                    return params;
                }
                continue;
            } else if (tokenPunctIs(pname,',') || tokenPunctIs(pname,')')) {
                type = parseArrayDimensions(cc,type);
                if (type->kind == AST_TYPE_ARRAY) {
                    type = astMakePointerType(type->ptr);
                }
                var = astLVar(type,"_unknown",7);
                /* Anonymous param can still carry a `reg <REG>` modifier
                 * (`Foo(I64 reg X19, I64 reg X20)` -
                 * names omitted, register IS the identity). */
                var->pinned_kind = pinned_kind;
                var->pinned_reg = pinned_reg;
                if (cc->tmp_locals) {
                    listAppend(cc->tmp_locals, var);
                }
                vecPush(params, var);

                if (tokenPunctIs(pname, terminator)) {
                    return params;
                }
                continue;
            } else {
                cctrlRaiseException(cc,"Identifier expected, got: %s",
                        lexemeToString(pname));
            }
        }

        type = parseArrayDimensions(cc,type);
        if (type->kind == AST_TYPE_ARRAY) {
            type = astMakePointerType(type->ptr);
        }
        var = astLVar(type,pname->start,pname->len);
        /* Anchor the param to its name token (astNew stamped whatever
         * token the parser had just consumed). */
        var->line = pname->line;
        var->col = pname->col;
        var->pinned_kind = pinned_kind;
        var->pinned_reg = pinned_reg;
        if (param_is_const) {
            var->flags |= AST_FLAG_CONST;
        }

        tok = cctrlTokenGet(cc);
        if (tokenPunctIs(tok, '=')) {
            var = parseDefaultFunctionParam(cc,var);
            if (store) {
                if (!mapAddOrErr(cc->localenv,var->declvar->lname->data,var)) {
                    cctrlRaiseException(cc,"variable %s already declared",
                            astLValueToString(var,0));
                }
            }
            tok = cctrlTokenGet(cc);
        } else {
            if (store) {
                if (!mapAddOrErr(cc->localenv,var->lname->data,var)) {
                        cctrlRaiseException(cc,"variable %s already declared",
                            astLValueToString(var,0));
                }
            }
       }

        if (cc->tmp_locals) {
            listAppend(cc->tmp_locals, var);
        }

        vecPush(params, var);

        if (tokenPunctIs(tok,terminator)) {
            return params;
        }

        if (!tokenPunctIs(tok,',')) {
            cctrlRaiseException(cc,"Comma expected, got %s",
                    lexemeToString(tok));
        }
    }
}

/* Does not parse pointer types, only the base type */
AstType *parseBaseDeclSpec(Cctrl *cc) {
    Lexeme *tok = cctrlTokenGet(cc);
    AstType *type;

    if (!tok) {
        return NULL;
    }

    if (tok->tk_type == TK_KEYWORD && tok->i64 == KW_CONST) {
        cctrlRaiseException(cc,"const is only allowed on declarations");
    }

    /* `(fn(...) R)`: the fn type itself, so a `*` after the `)`
     * makes a pointer to the function pointer */
    if (tokenPunctIs(tok,'(') && tokenIsFnIdent(cctrlTokenPeek(cc))) {
        AstType *fn_type = parseFnType(cc);
        cctrlTokenExpect(cc,')');
        return fn_type;
    }

    if (tokenIsFnIdent(tok)) {
        Lexeme *after_fn = cctrlTokenPeek(cc);
        if (tokenPunctIs(after_fn,'(')) {
            cctrlTokenRewind(cc);
            return parseFnType(cc);
        }
        cctrlRaiseException(cc,"Expected `(` after `fn`, got `%s`",
                after_fn ? lexemeAsWritten(after_fn) : "end of input");
    }

    if (tok->tk_type == TK_KEYWORD || tok->tk_type == TK_IDENT) {
        if ((type = parseGetType(cc, tok)) == NULL) {
            cctrlRewindUntilStrMatch(cc,tok->start,tok->len,NULL);
            cctrlRaiseException(cc,"Type `%.*s` has not been declared, expected type declaration",tok->len,tok->start);
        }
        return type;
    }
    cctrlRewindUntilStrMatch(cc,tok->start,tok->len,NULL);
    cctrlRaiseException(cc,"Declaration or specfier must be an identifier got %s `%s`",
            lexemeTypeToString(tok->tk_type),
            lexemeAsWritten(tok));
}

AstType *parseDeclSpec(Cctrl *cc) {
    AstType *type = parseBaseDeclSpec(cc);
    /* NULL means end-of-input (parseBaseDeclSpec's guard) - no caller
     * can make progress with a missing type, and several deref it. */
    if (type == NULL) {
        cctrlRaiseException(cc,
                "Unexpected end of input, expected a type");
    }
    type = parsePointerType(cc,type);
    return type;
}

static AstType *parseArrayDimensionsInternal(Cctrl *cc, AstType *base_type) {
    Ast *size;
    AstType *type = base_type;
    Lexeme *tok, *next_tok;
    int dimension;

    tok = cctrlTokenGet(cc);
    dimension = -1;

    if (!tokenPunctIs(tok, '[')) {
        /* At the end of input nothing was consumed; rewinding would
         * step back over the caller's token (the declaration's name). */
        if (tok) cctrlTokenRewind(cc);
        return NULL;
    }

    next_tok = cctrlTokenPeek(cc);

    if (!tokenPunctIs(next_tok, ']')) {
        size = parseExpr(cc,16);
        if (!size) {
            /* `I64 a[;`: no size and no `]`, report the token we got */
            cctrlTokenExpect(cc,']');
            return NULL;
        }
        int ok = 1;
        dimension = evalIntConstExprOrErr(size, &ok);

        if (!ok && (cc->flags & CCTRL_PASTE_DEFINES)) {
            if (size->kind == AST_LVAR) {
                dimension = -3;
                type->clsname = size->lname;
            } else if (astIsArithmetic(size,0)) {
                /* Relent... otherwise this will get far too complicated for a
                 * feature that likely will never be used */
                Ast *left = size->left;
                Ast *right = size->right;
                AoStr *lname = NULL;
                int literal;

                if (left->kind == AST_LVAR && right->kind == AST_LITERAL) {
                    literal = right->i64;
                    lname = left->lname;
                } else if (right->kind == AST_LVAR && left->kind == AST_LITERAL) {
                    literal = left->i64;
                    lname = right->lname;
                } else {
                    goto invalid_subscript;
                }
                
                Lexeme *le = mapGet(cc->macro_defs,lname->data);
                if (le->tk_type != TK_I64) {
                    goto invalid_subscript;
                }

                dimension = literal + le->i64;
            } else {
                goto invalid_subscript;
            }
        } else if (!ok) {
            goto invalid_subscript;
        }
    }

    cctrlTokenExpect(cc,']');
    AstType *sub_type = parseArrayDimensionsInternal(cc,type);
    if (sub_type) {
        if (sub_type->len == -1 && dimension == -1) {
            cctrlRaiseException(cc,"Array size not specified");
        }
        type = sub_type;
    }

    return astMakeArrayType(type,dimension);

invalid_subscript:
    cctrlRewindUntilPunctMatch(cc, '[',NULL);
    cctrlRaiseExceptionFromTo(cc,"Expected constant integer expression",
            '[',']',"Invalid array subscript");
    return NULL;
}

AstType *parseArrayDimensions(Cctrl *cc, AstType *base_type) {
    AstType *type = parseArrayDimensionsInternal(cc,base_type);
    if (type) {
        return type;
    }
    return base_type;
} 

/* Calls TokenGet internally so ensure you are 1 token behind the type */
AstType *parseFullType(Cctrl *cc) {
    AstType *type = parseDeclSpec(cc);
    return parseArrayDimensions(cc,type);
}

void parseDeclInternal(Cctrl *cc, Lexeme **tok, AstType **type) {
    AstType *_type = parseDeclSpec(cc);
    Lexeme *_tok = cctrlTokenGet(cc);
    if (tokenPunctIs(_tok,';')) {
        cctrlTokenRewind(cc);
        *tok = NULL;
        return;
    }

    if (_tok->tk_type == TK_KEYWORD && _tok->i64 == KW_CONST) {
        cctrlRaiseException(cc,"const is only allowed on declarations");
    }

    if (_tok->tk_type != TK_IDENT) {
        cctrlTokenRewind(cc);
        *tok = NULL;
    } else {
        *tok = _tok;
    }
    *type = parseArrayDimensions(cc,_type);
}


Ast *findFunctionDecl(Cctrl *cc, char *fname, int len) {
    Ast *decl;
    /* In a module, its own functions first */
    char *qual = cctrlQualifyName(cc, fname, len);
    if (qual && (decl = mapGet(cc->global_env, qual)) != NULL &&
        (decl->kind == AST_FUNC || decl->kind == AST_EXTERN_FUNC ||
         decl->kind == AST_FUN_PROTO))
    {
        return decl;
    }
    if (!cctrlModuleHides(cc, fname, len) &&
        (decl = mapGetLen(cc->global_env,fname,len)) != NULL) {
        cctrlCheckModuleAccess(cc, fname, len);
        if (decl->kind == AST_FUNC ||
            decl->kind == AST_EXTERN_FUNC ||
            decl->kind == AST_FUN_PROTO || decl->kind == AST_ASM_FUNC_BIND) {
            return decl;
        }
        /* Global function pointer `T (*name)(...)` */
        if (decl->kind == AST_GVAR && decl->type->kind == AST_TYPE_FUNC) {
            return decl;
        }
    } else if (cc->localenv && (decl = mapGetLen(cc->localenv,fname,len)) != NULL) {
        if (decl->kind == AST_FUNPTR || decl->kind == AST_LVAR) {
            return decl;
        }
        /* `fn(i64) i64 f = &D`: a function pointer parameter with a
         * default is in scope as its AST_DEFAULT_PARAM */
        if (decl->kind == AST_DEFAULT_PARAM && decl->declvar &&
            decl->declvar->type->kind == AST_TYPE_FUNC) {
            return decl->declvar;
        }
    } else if ((decl = mapGetLen(cc->asm_funcs,fname,len)) != NULL) {
        /* Assembly function */
        cctrlCheckModuleAccess(cc, fname, len);
        return decl;
    } else if (qual && (decl = mapGet(cc->asm_funcs, qual)) != NULL) {
        /* a binding of the module itself (`_extern _x I32 stat(...)`) */
        return decl;
    }
    return NULL;
}

static Vec *parseGetFunctionParams(Ast *def) {
    if (!def) return NULL;

    /* Declarations and function pointers store their params directly on
     * the AST; any other function-typed expression (a class field, a
     * variable, an array element) has them on its type. */
    switch (def->kind) {
        case AST_FUNC: case AST_FUN_PROTO: case AST_EXTERN_FUNC:
        case AST_ASM_FUNC_BIND: case AST_ASM_FUNCDEF: case AST_FUNPTR:
            return def->params;
        default:
            return def->type && def->type->kind == AST_TYPE_FUNC ? def->type->params : NULL;
    }
}

/* Where a call is written, for the argument count errors: from the
 * function's name (or the `(` / first token of the arguments) to the
 * closing `)`. line 0 means unknown. */
typedef struct ParseCallLoc {
    int line, col, len;
    /* Where each argument of argv is, a ParseCallLoc * per entry (NULL
     * for one left out); NULL when not tracked. */
    Vec *args;
} ParseCallLoc;

/* The token parsed last, by stepping back over it */
static Lexeme *parseLastToken(Cctrl *cc) {
    cctrlTokenRewind(cc);
    return cctrlTokenGet(cc);
}

/* Call before the arguments are parsed, with the `(` (or the token before
 * the arguments) just consumed. Starts at the name when it is right
 * before that token on the same line. */
static ParseCallLoc parseCallLocStart(Cctrl *cc, char *fname, int len) {
    ParseCallLoc loc = {0, 0, 0, NULL};
    Lexeme *tok = parseLastToken(cc);
    if (!tok) return loc;
    loc.line = tok->line;
    loc.col = tok->col;
    loc.len = tok->len;
    if (cc->lexer_ && tokenPunctIs(tok,'(')) {
        char *line_buffer = lexerReportLineIn(cctrlCurFile(cc), tok->line);
        int end = tok->col - 1; /* index of the `(` */
        if (end <= (int)strlen(line_buffer)) {
            while (end > 0 && isspace((unsigned char)line_buffer[end - 1])) end--;
            int name_idx = end - len;
            if (name_idx >= 0 && !strncmp(line_buffer + name_idx, fname, len)) {
                loc.col = name_idx + 1;
                loc.len = tok->col + tok->len - loc.col;
            }
        }
    }
    return loc;
}

/* Call after the arguments are parsed: extends `loc` to the last token
 * (the closing `)`) when it is on the same line. */
static void parseCallLocEnd(Cctrl *cc, ParseCallLoc *loc) {
    Lexeme *tok = parseLastToken(cc);
    if (!tok || !loc->line || tok->line != loc->line || tok->col < loc->col) {
        return;
    }
    loc->len = tok->col + tok->len - loc->col;
}

/* An argument count error at `loc`, or around the `(`...`)` on the
 * current line when the call's position isn't known. */
static void parseRaiseCallError(Cctrl *cc, ParseCallLoc *loc, char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    char *msg = mprintVa(fmt, ap, NULL);
    va_end(ap);
    if (!loc || !loc->line) {
        cctrlRaiseExceptionFromTo(cc, NULL, '(', ')', "%s", msg);
    }
    AoStr *bold = aoStrNew();
    aoStrCatColoured(bold, ESC_BOLD, msg);
    AoStr *buf = cctrlCreateErrorLineAt(cc, loc->line, loc->col, loc->len,
                                        bold->data, CCTRL_ERROR, NULL);
    aoStrRelease(bold);
    CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, NULL);
    d->line = d->end_line = loc->line;
    d->col = loc->col;
    d->end_col = loc->col + loc->len;
    cctrlDiagPush(cc, d);
    cctrlTerminate(cc);
}

/* We use this to check function calls against their definitions reusably */
/* How a call argument converts to its parameter's type, as in C:
 * numbers convert to each other silently, 0 and null to any pointer,
 * void * to and from any pointer; other pointer pairs and pointer/integer
 * mixes warn (C's -Wincompatible-pointer-types / -Wint-conversion); a
 * struct where another struct or a scalar is expected, or a scalar where
 * a struct is, is an error. */
typedef enum {
    ARG_OK,
    ARG_WARN_POINTER,     /* pointers to incompatible types */
    ARG_WARN_PTR_TO_INT,  /* a pointer where an integer is expected */
    ARG_WARN_INT_TO_PTR,  /* an integer where a pointer is expected */
    ARG_ERROR,
    ARG_FN_MISMATCH,      /* function pointers with different signatures */
} ParseArgCompat;

static int parseIsAggregateType(AstType *t) {
    return t && (t->kind == AST_TYPE_CLASS || t->kind == AST_TYPE_UNION) &&
           !t->is_intrinsic;
}

static int parseIsNumberType(AstType *t) {
    return astIsIntType(t) || astIsFloatType(t) || astIsIntrinsicClass(t);
}

static int parseSameAggregate(AstType *a, AstType *b) {
    if (a == b) return 1;
    if (a->kind != b->kind) return 0;
    if (a->clsname && b->clsname) return aoStrCmp(a->clsname, b->clsname);
    return 0;
}

/* Do pointers to e and to a point at compatible types? Integers of the
 * same size match whatever their sign (C would only warn with
 * -Wpointer-sign), function types match each other, and a byte pointer
 * (U8 *, I8 *, Bool *) matches any number pointer, as in assignments
 * (code walks raw buffers through them). */
static int parsePointeeCompatible(AstType *e, AstType *a) {
    if (!e || !a || e == a) return 1;
    if (e->kind == AST_TYPE_VOID || a->kind == AST_TYPE_VOID) return 1;
    if (e->kind == AST_TYPE_ARRAY) e = e->ptr;
    if (a->kind == AST_TYPE_ARRAY) a = a->ptr;
    if (parseIsNumberType(e) && parseIsNumberType(a) &&
        (e->size == 1 || a->size == 1)) {
        return 1;
    }
    if (astIsIntrinsicClass(e) && astIsIntType(a)) return e->size == a->size;
    if (astIsIntrinsicClass(a) && astIsIntType(e)) return e->size == a->size;
    if (astIsIntType(e) && astIsIntType(a)) return e->size == a->size;
    if (astIsFloatType(e) && astIsFloatType(a)) return e->size == a->size;
    if (e->kind == AST_TYPE_POINTER && a->kind == AST_TYPE_POINTER) {
        /* void ** and T ** are not compatible in C, but code passes
         * U0 ** for "any pointer's address" */
        if (e->ptr && a->ptr &&
            (e->ptr->kind == AST_TYPE_VOID || a->ptr->kind == AST_TYPE_VOID)) {
            return 1;
        }
        return parsePointeeCompatible(e->ptr, a->ptr);
    }
    if (e->kind == AST_TYPE_FUNC && a->kind == AST_TYPE_FUNC) return 1;
    if (parseIsAggregateType(e) && parseIsAggregateType(a)) {
        /* a derived class starts with its base: `Derived *` is a
         * `Base *`, for any base up the chain */
        for (AstType *cls = a; cls; cls = cls->base_cls) {
            if (parseSameAggregate(e, cls)) return 1;
        }
        return 0;
    }
    if (astIsIntrinsicClass(e) && astIsIntrinsicClass(a)) {
        return e->size == a->size;
    }
    return 0;
}

static int parseIsNullConstant(Ast *arg) {
    return arg && arg->kind == AST_LITERAL && astIsIntType(arg->type) &&
           arg->i64 == 0;
}

static ParseArgCompat parseArgCompat(AstType *param, Ast *arg) {
    AstType *p = param, *a = arg ? arg->type : NULL;
    if (!p || !a) return ARG_OK;
    if (p->kind == AST_TYPE_ARRAY) p = astConvertArray(p);
    if (a->kind == AST_TYPE_ARRAY) a = astConvertArray(a);

    /* A scoped enum parameter only takes the SAME enum without a cast:
     * `2` and a different enum's member are both errors, reusing this
     * same check (test 413's). */
    if (p->kind == AST_TYPE_CLASS && p->is_enum) {
        if (a->kind == AST_TYPE_CLASS && a->is_enum && p->clsname &&
            a->clsname && aoStrCmp(p->clsname, a->clsname)) {
            return ARG_OK;
        }
        return ARG_ERROR;
    }

    if (parseIsAggregateType(p) || parseIsAggregateType(a)) {
        if (parseIsAggregateType(p) && parseIsAggregateType(a) &&
            parseSameAggregate(p, a)) {
            return ARG_OK;
        }
        return ARG_ERROR;
    }

    int p_ptr = p->kind == AST_TYPE_POINTER || p->kind == AST_TYPE_FUNC;
    int a_ptr = a->kind == AST_TYPE_POINTER || a->kind == AST_TYPE_FUNC;

    if (p_ptr) {
        if (a_ptr) {
            int p_void = p->kind == AST_TYPE_POINTER && p->ptr &&
                         p->ptr->kind == AST_TYPE_VOID;
            int a_void = a->kind == AST_TYPE_POINTER && a->ptr &&
                         a->ptr->kind == AST_TYPE_VOID;
            if (p_void || a_void) return ARG_OK;
            if (astFnPtrMismatch(p, a)) return ARG_FN_MISMATCH;
            if (p->kind == AST_TYPE_FUNC || a->kind == AST_TYPE_FUNC) {
                return p->kind == a->kind ? ARG_OK : ARG_WARN_POINTER;
            }
            return parsePointeeCompatible(p->ptr, a->ptr) ? ARG_OK
                                                          : ARG_WARN_POINTER;
        }
        if (parseIsNullConstant(arg)) return ARG_OK;
        if (astIsIntType(a) || astIsIntrinsicClass(a)) return ARG_WARN_INT_TO_PTR;
        if (astIsFloatType(a)) return ARG_ERROR;
        return ARG_OK;
    }

    if (parseIsNumberType(p)) {
        if (parseIsNumberType(a)) return ARG_OK;
        if (a_ptr) {
            /* Bool takes a pointer as C's _Bool does: 0 or 1, no cast */
            if (p->kind == AST_TYPE_CHAR && p->is_bool) return ARG_OK;
            if (astIsFloatType(p)) return ARG_ERROR;
            return ARG_WARN_PTR_TO_INT;
        }
    }
    return ARG_OK;
}

/* Warn about (or reject) argument i of a call, per parseArgCompat */
static void parseCheckArgType(Cctrl *cc, AstType *type, Ast *arg, int i,
                              char *fname, int len, ParseCallLoc *loc)
{
    ParseArgCompat compat = parseArgCompat(type, arg);
    if (compat == ARG_OK) return;

    char *expected = astTypeToColorString(type);
    char *got = astTypeToColorString(arg->type);
    char *why = "";
    switch (compat) {
        case ARG_WARN_PTR_TO_INT: why = ": makes an integer from a pointer without a cast"; break;
        case ARG_WARN_INT_TO_PTR: why = ": makes a pointer from an integer without a cast"; break;
        default: break;
    }
    ParseCallLoc *at = loc && loc->args && vecInBounds(loc->args, (unsigned long)i)
                       ? loc->args->entries[i] : NULL;
    if (compat == ARG_FN_MISMATCH) {
        char *msg_expected = astTypeToString(type);
        char *msg_actual = astTypeToString(arg->type);
        if (at) {
            cctrlRaiseExceptionAt(cc,at->line,at->col,at->len,
                    "cannot convert %s to %s", msg_actual, msg_expected);
        } else {
            parseRaiseFnConvert(cc, type, arg->type);
        }
        return;
    }
    if (compat == ARG_ERROR) {
        if (astIsEnumType(type)) {
            char *actual = astTypeToString(arg->type);
            if (at) {
                cctrlRaiseExceptionAt(cc,at->line,at->col,at->len,
                        "Cannot convert `%s` to enum `%s` without a cast",
                        actual, type->clsname->data);
            } else {
                cctrlRaiseException(cc,
                        "Cannot convert `%s` to enum `%s` without a cast",
                        actual, type->clsname->data);
            }
            return;
        }
        if (at) {
            cctrlRaiseExceptionAt(cc,at->line,at->col,at->len,
                    "Incompatible function argument, expected '%s' got '%s' in function '%.*s'",
                    expected,got,len,fname);
        } else {
            cctrlRaiseException(cc,"Incompatible function argument, expected '%s' got '%s' in function '%.*s'",
                    expected,got,len,fname);
        }
        return;
    }
    if (at) {
        cctrlWarningAt(cc,at->line,at->col,at->len,
                "Incompatible function argument, expected '%s' got '%s' in function '%.*s'%s",
                expected,got,len,fname,why);
    } else {
        cctrlWarning(cc,"Incompatible function argument, expected '%s' got '%s' in function '%.*s'%s",
                expected,got,len,fname,why);
    }
}

static void parseFunctionArgumentCheck(Cctrl *cc, Ast *def, Vec *argv, char *fname, int len,
                                       ParseCallLoc *loc)
{
    Vec *params = parseGetFunctionParams(def);
    int param_count = params ? (int)params->size : 0;
    int arg_count = argv ? (int)argv->size : 0;
    int default_count = 0, required_count = 0, fixed_param_count = param_count;

    /* A `T ...name` callee: its hidden (count, data) parameters always got
     * arguments (parseVarArgsPack), so only the fixed ones can be missing;
     * report the arguments as written */
    if (param_count >= 2) {
        Ast *last = params->entries[param_count - 1];
        if (last && last->kind == AST_LVAR && (last->flags & AST_FLAG_VARARGS)) {
            int fixed = param_count - 2, written = 0;
            for (int i = 0; i < fixed && i < arg_count; ++i) {
                Ast *arg = argv->entries[i];
                if (arg && arg->kind != AST_PLACEHOLDER) written = i + 1;
            }
            if (arg_count == param_count) {
                Ast *data = argv->entries[param_count - 1];
                if (data && data->kind == AST_VA_PACK) written = fixed + (int)data->va_items->size;
                else if (data && data->kind == AST_LVAR) written = fixed + 1;
            }
            for (int i = 0; i < fixed; ++i) {
                Ast *param = params->entries[i];
                Ast *arg = i < arg_count ? argv->entries[i] : NULL;
                if (param->kind != AST_DEFAULT_PARAM) required_count++;
                if ((!arg || arg->kind == AST_PLACEHOLDER) &&
                    param->kind != AST_DEFAULT_PARAM)
                {
                    parseRaiseCallError(cc, loc,
                        "Unexpected number of arguments %d in call to %.*s(), argument %d is required",
                        written, len, fname, i + 1);
                }
                AstType *type = param->kind == AST_DEFAULT_PARAM ? param->declvar->type : param->type;
                if (type && arg && arg->kind != AST_PLACEHOLDER) {
                    parseCheckArgType(cc,type,arg,i,fname,len,loc);
                }
            }
            return;
        }
    }

    /* count fixed/default/required params and find the varargs point if it exists */
    for (int i = 0; i < param_count; ++i) {
        Ast *param = params->entries[i];
        if (param->kind == AST_VAR_ARGS) {
            fixed_param_count = i;
            break;
        }

        if (param->kind == AST_DEFAULT_PARAM) default_count++;
        else required_count++;
    }

    /* validate each fixed parameter against its call argument */
    for (int i = 0; i < fixed_param_count; ++i) {
        Ast *param = params->entries[i];
        Ast *arg = i < arg_count ? argv->entries[i] : NULL;

        if (!arg || arg->kind == AST_PLACEHOLDER) {
            if (param->kind == AST_DEFAULT_PARAM) continue;

            if (default_count) {
                parseRaiseCallError(cc, loc,
                        "Unexpected number of arguments %d in call to %.*s(), argument %d is required",
                        arg_count, len, fname, i + 1);
            }

            parseRaiseCallError(cc, loc,
                    "Unexpected number of arguments %d in call to %.*s(), expected %d args",
                    arg_count, len, fname, fixed_param_count);
        }

        AstType *type = param->kind == AST_DEFAULT_PARAM ? param->declvar->type : param->type;
        if (type) parseCheckArgType(cc,type,arg,i,fname,len,loc);
    }

    /* vararg calls allow extras, but still require fixed args */
    if (fixed_param_count != param_count) {
        if (arg_count < required_count) {
            parseRaiseCallError(cc, loc,
                    "Unexpected number of arguments %d in call to %.*s(), expected at least %d args",
                    arg_count, len, fname, required_count);
        }
        return;
    }

    /* non-vararg calls cannot use more than the fixed parameter count */
    if (arg_count > fixed_param_count) {
        if (default_count) {
            parseRaiseCallError(cc, loc,
                    "Unexpected number of arguments %d in call to %.*s(), expected args in range %d to %d",
                    arg_count, len, fname, required_count, fixed_param_count);
        }

        parseRaiseCallError(cc, loc,
                "Unexpected number of arguments %d in call to %.*s(), expected %d args",
                arg_count, len, fname, fixed_param_count);
    }
}

/* Materialize default values into `argv` so every fixed-parameter position
 * holds a real expression. Mutates in place. Run after
 * parseFunctionArgumentCheck has validated counts/types, so any still-missing
 * position past this point is one the check let through (e.g. inline-only
 * mismatches) and we silently leave alone rather than splice in NULL. */
static void parseFlattenDefaultArgs(Ast *def, Vec *argv) {
    if (!def || !argv) {
        return;
    }
    Vec *params = parseGetFunctionParams(def);
    if (!params)
        return;

    u64 param_count = params->size;
    for (u64 i = 0; i < param_count; ++i) {
        Ast *param = (Ast *)params->entries[i];
        if (!param)
            continue;
        if (param->kind == AST_VAR_ARGS)
            break;

        Ast *def_ast = NULL;
        if (param->kind == AST_DEFAULT_PARAM) {
            def_ast = param->declinit;
        } else if (param->kind == AST_FUNPTR && param->default_fn) {
            def_ast = param->default_fn->declinit;
        }

        int in_bounds = i < argv->size;
        Ast *cur = in_bounds ? (Ast *)argv->entries[i] : NULL;
        if (in_bounds && cur && cur->kind != AST_PLACEHOLDER)
            continue;
        if (!def_ast)
            continue;

        if (in_bounds) argv->entries[i] = def_ast;
        else           vecPush(argv, def_ast);
    }
}

/* A ktm `...` callee always receives the implicit argc. parseArgv only
 * adds it when extra arguments were written, so a call that passes none
 * (`Foo(fmt)`) left the callee reading argc from whatever was in the
 * register or stack slot. Runs after parseFlattenDefaultArgs so argc
 * lands after any filled-in default arguments. */
static void parseAddEmptyVarArgCount(Ast *def, Vec *argv) {
    Vec *params = parseGetFunctionParams(def);
    if (!params || !argv || def->kind == AST_EXTERN_FUNC)
        return;

    for (u64 i = 0; i < params->size; ++i) {
        Ast *param = (Ast *)params->entries[i];
        if (param && param->kind == AST_VAR_ARGS) {
            if (argv->size == i) vecPush(argv, astI64Type(0));
            return;
        }
    }
}

/* Wrap any fixed-parameter argument that crosses the int/float boundary
 * in a cast, so codegen converts the value (sitofp/fptosi) instead of
 * reinterpreting the raw bits. C does this implicitly for prototyped
 * params - e.g. `Twice(3)` where `Twice` takes an F64 must pass 3.0.
 * Runs after parseFlattenDefaultArgs so filled-in default values get
 * coerced too. Variadic extras are left alone (no declared type). */
/* Promote F32 arguments in variadic positions (index >= from_idx) to
 * F64. C's default argument promotions widen a float to a double when
 * passed through `...`, and both the C-extern and ktm printf read the
 * slot as 8 bytes - without this an F32 arg prints as garbage. */
static void parsePromoteVarargFloats(Vec *argv, u64 from_idx) {
    if (!argv)
        return;
    for (u64 i = from_idx; i < argv->size; ++i) {
        Ast *arg = (Ast *)argv->entries[i];
        if (!arg || arg->kind == AST_PLACEHOLDER || !arg->type)
            continue;
        if (astIsFloatType(arg->type) && arg->type->size < 8) {
            argv->entries[i] = astCast(arg, ast_float_type);
        }
    }
}

static void parseCoerceArgs(Ast *def, Vec *argv) {
    Vec *params = parseGetFunctionParams(def);
    if (!params || !argv)
        return;

    u64 param_count = params->size;
    for (u64 i = 0; i < param_count && i < argv->size; ++i) {
        Ast *param = (Ast *)params->entries[i];
        if (!param)
            continue;
        if (param->kind == AST_VAR_ARGS) {
            /* Everything from here on is variadic: widen F32 -> F64. */
            parsePromoteVarargFloats(argv, i);
            break;
        }

        Ast *arg = (Ast *)argv->entries[i];
        if (!arg || arg->kind == AST_PLACEHOLDER || !arg->type)
            continue;

        AstType *type = param->kind == AST_DEFAULT_PARAM
                      ? param->declvar->type : param->type;
        if (!type)
            continue;

        /* Bool: 0 or 1 (arg != 0), not the low byte of a wider value */
        if (type->is_bool && !arg->type->is_bool) {
            argv->entries[i] = astCast(arg, type);
            continue;
        }

        int want_float = astIsFloatType(type);
        int want_int   = astIsIntOrIntrinsic(type);
        int got_float  = astIsFloatType(arg->type);
        int got_int    = astIsIntOrIntrinsic(arg->type);
        if ((want_float && got_int) || (want_int && got_float)) {
            argv->entries[i] = astCast(arg, type);
        } else if (want_float && got_float &&
                   type->size != arg->type->size) {
            /* float <-> float of a different width (e.g. passing an
             * F32 where an F64 param is declared): convert, don't
             * reinterpret the bits. */
            argv->entries[i] = astCast(arg, type);
        }
    }
}

/* Where the argument just parsed is: from `start`, its first token, to
 * the last token consumed, or just `start` when it spans lines. */
static ParseCallLoc *parseArgSpan(Cctrl *cc, Lexeme *start) {
    ParseCallLoc *span = (ParseCallLoc *)calloc(1, sizeof(ParseCallLoc));
    Lexeme *end = parseLastToken(cc);
    span->line = start->line;
    span->col = start->col;
    span->len = start->len;
    if (end && end->line == start->line && end->col >= start->col) {
        span->len = end->col + end->len - start->col;
        /* A string's or character constant's len leaves out its quotes */
        if (end->tk_type == TK_STR || end->tk_type == TK_CHAR_CONST) {
            span->len += 2;
        }
    } else if (start->tk_type == TK_STR || start->tk_type == TK_CHAR_CONST) {
        span->len += 2;
    }
    return span;
}

/* Free the argument spans of `loc` */
static void parseCallLocFreeArgs(ParseCallLoc *loc) {
    if (!loc->args) return;
    for (u64 i = 0; i < loc->args->size; ++i) free(loc->args->entries[i]);
    vecRelease(loc->args);
    loc->args = NULL;
}

/* `spans`, when not NULL, gets where each entry of the returned vector
 * is (see ParseCallLoc.args). */
/* The (count, data) arguments for a `T ...name` parameter from the extras
 * of a call: a spread `...other` passes other's count and pointer on;
 * otherwise each extra is checked as an argument of type T (any mismatch
 * is an error) and converted, and the call passes how many there are and
 * the address of a hidden array in the caller's frame holding them (one
 * per call site, so it lives as long as the call needs it). No extras: 0
 * and a null pointer. Untyped extras go into 64-bit slots as the old
 * argv's: floats as f64 bits, narrower integers widened. */
static void parseVarArgsPack(Cctrl *cc, Vec *argv_vec, Vec *spans,
                             AstType *elem, Vec *items, Vec *item_spans,
                             Ast *spread, int spread_idx,
                             int first_arg, char *fname, int len)
{
    static int va_array_id = 0;
    int untyped = elem->has_var_args;
    AstType *data_type = astMakePointerType(elem);
    Ast *count, *data;

    if (spread) {
        ParseCallLoc *at = vecGet(ParseCallLoc *, item_spans, spread_idx);
        if (items->size > 1) {
            cctrlRaiseExceptionAt(cc, at->line, at->col, at->len,
                "`...%s` must be the only extra argument in a call to `%.*s`",
                spread->lname->data, len, fname);
        }
        AstType *from = spread->type->ptr;
        int same = untyped ? from->has_var_args
                           : !from->has_var_args &&
                             !strcmp(astTypeToString(from), astTypeToString(elem));
        if (!same) {
            cctrlRaiseExceptionAt(cc, at->line, at->col, at->len,
                "cannot spread `...%s` (%s) into `%.*s`, whose extra arguments are %s",
                spread->lname->data,
                from->has_var_args ? "untyped" : astTypeToString(from),
                len, fname, untyped ? "untyped" : astTypeToString(elem));
        }
        AoStr *len_name = aoStrPrintf("%s#len", spread->lname->data);
        count = cctrlGetVar(cc, len_name->data, len_name->len);
        data = spread;
    } else if (items->size == 0) {
        count = astI64Type(0);
        data = astCast(astI64Type(0), data_type);
    } else {
        Vec *converted = astVecNew();
        for (u64 i = 0; i < items->size; ++i) {
            Ast *arg = vecGet(Ast *, items, i);
            ParseCallLoc *at = vecGet(ParseCallLoc *, item_spans, i);
            AstType *at_type = arg->type;
            if (untyped) {
                if (at_type && (parseIsAggregateType(at_type))) {
                    cctrlRaiseExceptionAt(cc, at->line, at->col, at->len,
                        "argument %d of `%.*s`: a %s can't be passed as an untyped extra argument",
                        first_arg + (int)i + 1, len, fname, astTypeToString(at_type));
                }
                if (at_type && astIsFloatType(at_type) && at_type->size < 8) {
                    arg = astCast(arg, ast_float_type);
                } else if (at_type && (astIsIntType(at_type) || at_type->is_bool ||
                                       astIsEnumType(at_type)) &&
                           at_type->size < 8) {
                    arg = astCast(arg, ast_int_type);
                }
            } else {
                if (parseArgCompat(elem, arg) != ARG_OK) {
                    cctrlRaiseExceptionAt(cc, at->line, at->col, at->len,
                        "argument %d of `%.*s`: expected %s, got %s",
                        first_arg + (int)i + 1, len, fname,
                        astTypeToString(elem), astTypeToString(at_type));
                }
                arg = astCast(arg, elem);
            }
            vecPush(converted, arg);
        }
        AoStr *arr_name = aoStrPrintf("#va%d", va_array_id++);
        AstType *arr_type = astMakeArrayType(elem, (int)items->size);
        Ast *array;
        if (cc->tmp_fname) {
            array = astLVar(arr_type, arr_name->data, arr_name->len);
            if (cc->tmp_locals) {
                listAppend(cc->tmp_locals, array);
            }
        } else {
            /* A call in file-scope code, which runs once at startup:
             * a static array */
            array = astGVar(arr_type, arr_name->data, arr_name->len, 1);
            listAppend(cc->ast_list, astDecl(array, NULL));
        }
        count = astI64Type((s64)items->size);
        data = astVaPack(array, converted, untyped);
    }
    vecPush(argv_vec, count);
    vecPush(argv_vec, data);
    if (spans) {
        vecPush(spans, NULL);
        vecPush(spans, NULL);
    }
}

Vec *parseArgv(Cctrl *cc, Ast *decl, s64 terminator, char *fname, int len,
               Vec *spans) {
    List *var_args = NULL;
    Ast *ast, *param = NULL;
    Lexeme *tok;
    Vec *params = NULL;
    int param_idx = 0;

    if (decl) params = parseGetFunctionParams(decl);

    /* A `T ...name` callee: its last two parameters are the count and the
     * data pointer, every argument from the count's position on is an
     * extra (parseVarArgsPack) */
    int va_at = -1;
    AstType *va_elem = NULL;
    Vec *va_items = NULL, *va_spans = NULL;
    Ast *va_spread = NULL;
    int va_spread_idx = -1;
    if (params && params->size >= 2) {
        Ast *last = params->entries[params->size - 1];
        if (last && last->kind == AST_LVAR && (last->flags & AST_FLAG_VARARGS)) {
            va_at = (int)params->size - 2;
            va_elem = last->type->ptr;
            va_items = astVecNew();
            va_spans = vecNew(&vec_unsigned_long_type);
        }
    }

    Vec *argv_vec = astVecNew();
    int saw_args = 0;

    tok = cctrlTokenPeek(cc);

    while (tok && !tokenPunctIs(tok, terminator)) {
        saw_args = 1;
        if (va_at >= 0 && (int)argv_vec->size >= va_at) {
            Lexeme start = *tok;
            Ast *extra;
            int is_spread = tokenPunctIs(tok, TK_ELLIPSIS);
            if (is_spread) cctrlTokenGet(cc);
            extra = parseExpr(cc,16);
            if (!extra) {
                cctrlRaiseException(cc, "Expected an argument in the call to `%.*s`",
                                    len, fname);
            }
            ParseCallLoc *span = parseArgSpan(cc, &start);
            if (is_spread) {
                if (extra->kind != AST_LVAR || !(extra->flags & AST_FLAG_VARARGS)) {
                    cctrlRaiseExceptionAt(cc, span->line, span->col, span->len,
                        "only a variadic parameter can be spread, as in `...nums`");
                }
                va_spread = extra;
                va_spread_idx = (int)va_items->size;
            }
            vecPush(va_items, extra);
            vecPush(va_spans, span);
            tok = cctrlTokenGet(cc);
            if (tokenPunctIs(tok, terminator)) break;
            if (!tokenPunctIs(tok, ',')) {
                cctrlTokenRewind(cc);
                cctrlRaiseException(cc, "Expected `,` or `)` in the call to `%.*s`, got `%s`",
                                    len, fname, lexemeAsWritten(tok));
            }
            tok = cctrlTokenPeek(cc);
            continue;
        }
        if (tokenPunctIs(tok, TK_ELLIPSIS)) {
            cctrlRaiseException(cc,
                "`...` spreads into a `T ...name` parameter, which `%.*s` doesn't have here",
                len, fname);
        }

        /* For a function with varadic arguments this will never be in bounds */
        int is_in_bounds = params && vecInBounds(params, (unsigned long)param_idx);
        if (is_in_bounds) {
            param = params->entries[param_idx++];
        }

        if (tokenPunctIs(tok, ',')) {
            ast = NULL;
            if (param && param->kind == AST_DEFAULT_PARAM) {
                ast = param->declinit;
            } else if (param && param->kind == AST_FUNPTR) {
                if (param->default_fn != NULL) {
                    ast = param->default_fn->declinit;
                }
            }
            if (ast == NULL) {
                ast = astMakePlaceHolder();
            }

            vecPush(argv_vec, ast);
            if (spans) vecPush(spans, NULL);
            cctrlTokenGet(cc);
            tok = cctrlTokenPeek(cc);
            continue;
        }

        Lexeme start = *tok;
        ast = parseExpr(cc,16);
        ParseCallLoc *span = spans && ast ? parseArgSpan(cc, &start) : NULL;
        if (ast == NULL) {
            if (param && param->kind == AST_DEFAULT_PARAM) {
                ast = param->declinit;
            }
        }

        if (param != NULL && param->kind == AST_VAR_ARGS) {
            if (decl && decl->kind == AST_EXTERN_FUNC) {
                vecPush(argv_vec,ast);
                if (spans) vecPush(spans, span);
            } else {
                /* Will merge this to the end of the arguments list */
                if (var_args == NULL) {
                    var_args = listNew();
                }
                listAppend(var_args,ast);
            }
        } else {
            vecPush(argv_vec,ast);
            if (spans) vecPush(spans, span);
        }

        tok = cctrlTokenGet(cc);

        if (tokenPunctIs(tok, terminator)) {
            break;
        }

        if (tok == NULL) {
            cctrlRaiseException(cc,
                    "Unexpected end of input in call to `%.*s` "
                    "(unterminated `( ... )`?)", len, fname);
        }
        if (!tokenPunctIs(tok,',')) {
            /* Point at `tok`. Matching its i64 as a punctuator would
             * rewind up to 6 tokens when it is an identifier, a string
             * or a number, out of this statement */
            cctrlTokenRewind(cc);
            /* We could have a malformed string as a function argument */
            int is_string = tok->tk_type == TK_STR;
            char terminator = is_string ? '"' : ')';
            char *suggestion = is_string ?
                                "terminate with `\"`" :
                                "terminate with `)`";
            cctrlInfo(cc, "Function call `%.*s` not terminated correctly", len,fname);
            cctrlRaiseSuggestion(cc,suggestion,
                    "Invalid %s `%s` while parsing function call, perhaps you meant to terminate the arguments with `%c` or keep going with `,`?",
                    lexemeTypeToString(tok->tk_type),
                   lexemeAsWritten(tok), terminator);
        }

        tok = cctrlTokenPeek(cc);
    }

    /* Ran out of input right after the `(` or a `,` */
    if (tok == NULL) {
        cctrlRaiseException(cc,
                "Unexpected end of input in call to `%.*s` "
                "(unterminated `( ... )`?)", len, fname);
    }

    if (va_at >= 0) {
        while ((int)argv_vec->size < va_at) {
            vecPush(argv_vec, astMakePlaceHolder());
            if (spans) vecPush(spans, NULL);
        }
        parseVarArgsPack(cc, argv_vec, spans, va_elem, va_items, va_spans,
                         va_spread, va_spread_idx, va_at, fname, len);
        /* With no argument at all the `)` is still there */
        if (!saw_args) {
            cctrlTokenGet(cc);
        }
        return argv_vec;
    }

    if (var_args) {
        /* set the argument count and merge lists */
        vecPush(argv_vec,astI64Type(listCount(var_args)));
        listForEach(var_args) {
            vecPush(argv_vec,(Ast*)it->value);
        }
    }

    if (vecEmpty(argv_vec)) {
        cctrlTokenGet(cc);
    }

    return argv_vec;
}

Ast *parseInlineFunctionCall(Cctrl *cc, Ast *fn, Vec *argv) {
    List *stmts = listNew();
    List *fn_body = listCopy(fn->body->stms);
    if (fn->params->size == argv->size) {
        int size = fn->params->size;
        for (int i = 0; i < size; ++i) {
            Ast *param = fn->params->entries[i];
            Ast *arg = argv->entries[i];
            Ast *var_decl = astDecl(param,arg);
            listAppend(cc->tmp_func->locals, param);
            listAppend(stmts, var_decl);
        }
    } else {
        cctrlRaiseException(cc,"Cannot presently inline a function with default arguments or where the number of parameters does not match the number of arguments");
    }
    listMergeAppend(stmts, fn_body);
    if (!listEmpty(fn->locals)) {
        List *fn_locals = listCopy(fn->locals);
        listMergeAppend(cc->tmp_func->locals, fn_locals);
    }
    Ast *inlined = astCompountStatement(stmts);
    inlined->inline_ret = fn->inline_ret;
    inlined->type = fn->type->rettype;
    return inlined;
}

/* Count printf-style conversion specifiers in a format-string literal. Skips
 * `%%`; each `%...c` (where c is a conversion char) counts as one expected
 * argument, plus one extra for each `*` used as a width or precision. Returns
 * -1 if a trailing/malformed spec is encountered so the caller can skip the
 *  warning rather than report a confusing count. This is C's printf. */
static int parseCountPrintfFormatSpecs(const char *fmt, int len) {
    int n = 0;
    int i = 0;
    while (i < len) {
        if (fmt[i] != '%') {
            i++;
            continue;
        }
        if (i + 1 < len && fmt[i+1] == '%') {
            i += 2;
            continue;
        }
        i++; /* past `%` */
        while (i < len) {
            char ch = fmt[i];
            if (ch == '*') n++;
            if (ch == 'd' || ch == 'i' || ch == 'u' || ch == 'o' ||
                ch == 'x' || ch == 'X' || ch == 'e' || ch == 'E' ||
                ch == 'f' || ch == 'F' || ch == 'g' || ch == 'G' ||
                ch == 'c' || ch == 's' || ch == 'p' || ch == 'n' ||
                ch == 'a' || ch == 'A')
            {
                n++;
                i++;
                goto next_spec;
            }
            i++;
        }
        return -1; /* `%` with no terminating conversion */
next_spec: ;
    }
    return n;
}

/* %D %T and %F left the format engine (dates and files are not str's):
 * the first of them a format uses, raw source text (`raw`, with escapes)
 * or the literal's value, and 0 if none */
static char parseFormatRemovedConv(const char *s, int len, int raw) {
    int i = 0;
    while (i < len) {
        if (raw && s[i] == '\\' && i + 1 < len) {
            i += 2;
            continue;
        }
        if (s[i] != '%') {
            i++;
            continue;
        }
        i++;
        while (i < len && strchr("-0+ #", s[i])) i++;
        while (i < len && (strchr("*.", s[i]) || (s[i] >= '0' && s[i] <= '9'))) i++;
        while (i < len && strchr("lhzjtLq", s[i])) i++;
        if (i < len && strchr("DTF", s[i])) return s[i];
        i++; /* the conversion, `%` of `%%` included */
    }
    return 0;
}

/* The error for a removed conversion, naming what replaces it */
static void parseFormatRemovedError(Cctrl *cc, char conv, int line, int col,
                                    int len)
{
    char *msg = conv == 'F'
        ? "`%%F` is no longer a format conversion: read the file with fs.readAll"
        : "`%%%c` is no longer a format conversion: use date.format(...)";
    if (line > 0) {
        cctrlRaiseExceptionAt(cc, line, col, len, msg, conv);
    }
    cctrlRaiseException(cc, msg, conv);
}

/* The same for the ktm library's StrPrint (StrPrintJoin): a spec is `%`,
 * flags `-0+ #`, a width (digits or `*`), a precision (`.` then digits or
 * `*`), length modifiers (l ll h hh z j t L q, which change nothing) and
 * one of the conversions below. Each `*` takes an argument, as does each
 * conversion but `%`. A conversion StrPrint doesn't know takes none (it
 * prints "Invalid format char"); the first such one is returned in
 * `*_unknown` (0 if none). -1 for a `%` at the end of the format. */
static int parseCountktmFormatSpecs(const char *fmt, int len,
                                      char *_unknown)
{
    int n = 0;
    int i = 0;
    *_unknown = 0;
    while (i < len) {
        if (fmt[i] != '%') {
            i++;
            continue;
        }
        i++; /* past `%` */
        while (i < len && strchr("-0+ #", fmt[i])) i++;
        if (i < len && fmt[i] == '*') {
            n++;
            i++;
        } else {
            while (i < len && fmt[i] >= '0' && fmt[i] <= '9') i++;
        }
        if (i < len && fmt[i] == '.') {
            i++;
            if (i < len && fmt[i] == '*') {
                n++;
                i++;
            } else {
                while (i < len && fmt[i] >= '0' && fmt[i] <= '9') i++;
            }
        }
        while (i < len && strchr("lhzjtLq", fmt[i])) i++;
        if (i >= len) {
            return -1;
        }
        char ch = fmt[i++];
        if (ch == '%') {
            continue;
        }
        if (strchr("diuoxXcsfegpQC", ch)) {
            n++;
        } else if (!*_unknown) {
            *_unknown = ch;
        }
    }
    return n;
}

/* Whether a format-checked function is C's printf family rather than the
 * ktm library's */
static int parseIsCFormatFunction(char *fname, int len) {
    return (len == 6 && !strncmp(fname, str_lit("printf"))) ||
           (len == 7 && !strncmp(fname, str_lit("sprintf"))) ||
           (len == 8 && !strncmp(fname, str_lit("snprintf")));
}

/* Position of the format string within a known printf-style call's argument
 * list. -1 means this isn't one of the format-checked functions. Single
 * source of truth shared by the pre-parse position snapshot and the
 * post-parse arg-count check. */
static int parseFormatArgIdx(char *fname, int len) {
    /* Feels like we may want to occasionally add to this list? */
    if      (len == 6 && !strncmp(fname, str_lit("printf"))) return 0;
    else if (len == 7 && !strncmp(fname, str_lit("sprintf"))) return 0;
    else if (len == 8 && !strncmp(fname, str_lit("snprintf"))) return 0;
    /* May as well type-check ktm's library too which is even more
     * prone to erroring. */
    else if (len == 10 && !strncmp(fname, str_lit("str.format"))) return 0;
    else if (len == 14 && !strncmp(fname, str_lit("rt.__ktmPrintf"))) return 0;
    else if (len == 12 && !strncmp(fname, str_lit("str.formatTo"))) return 1;
    else if (len == 16 && !strncmp(fname, str_lit("str.appendFormat"))) return 1;
    else if (len == 19 && !strncmp(fname, str_lit("str.appendFormatLen"))) return 2;
    else if (len == 16 && !strncmp(fname, str_lit("str.formatToSize"))) return 2;
    /* `throw(code, fmt, ...)`, checked as a call to it */
    else if (len == 16 && !strncmp(fname, str_lit("rt.__ktmThrowFmt"))) return 1;
    return -1;
}

/* Raise an error when a printf-style call's format-string literal disagrees
 * with the supplied argument count. Very easy footgun. `"%d\n";`
 * Only fires when argv[0] is a real string literal so we stay silent on
 * `printf(fmt, ...)` where the `fmt` is dynamic. */
static void parsePrintfFormatCheck(Cctrl *cc,
                                   Ast *maybe_fn,
                                   Vec *argv,
                                   char *fname,
                                   int len,
                                   ParseCallLoc *loc)
{
    if (!argv || argv->size == 0)
        return;

    int fmt_arg_idx = parseFormatArgIdx(fname, len);
    if (fmt_arg_idx == -1) return;

    Ast *fmt_ast = argv->entries[fmt_arg_idx];
    if (!fmt_ast || fmt_ast->kind != AST_STRING || !fmt_ast->sval)
        return;

    /* Where the format literal is, from parseArgv */
    ParseCallLoc *at = loc && loc->args &&
                       vecInBounds(loc->args, (unsigned long)fmt_arg_idx)
                       ? loc->args->entries[fmt_arg_idx] : NULL;

    int expected;
    if (parseIsCFormatFunction(fname, len)) {
        expected = parseCountPrintfFormatSpecs(fmt_ast->sval->data,
                fmt_ast->sval->len);
    } else {
        char removed = parseFormatRemovedConv(fmt_ast->sval->data,
                fmt_ast->sval->len, 0);
        if (removed) {
            parseFormatRemovedError(cc, removed, at ? at->line : 0,
                                    at ? at->col : 0, at ? at->len : 0);
        }
        char unknown = 0;
        expected = parseCountktmFormatSpecs(fmt_ast->sval->data,
                fmt_ast->sval->len, &unknown);
        if (unknown) {
            char *msg = "Unknown format conversion `%%%c` (%.*s prints it as "
                        "\"Invalid format char\" and takes no argument for it)";
            if (at) {
                cctrlWarningAt(cc, at->line, at->col, at->len, msg,
                               unknown, len, fname);
            } else {
                cctrlWarning(cc, msg, unknown, len, fname);
            }
        }
    }
    if (expected < 0)
        return;

    /* Number of arguments the user actually wrote after the format
     * string. parseArgv stashes everything from the fixed-param slot
     * onwards behind a synthetic count prefix for ktm-native
     * varargs (printf-the-extern is C-style and has no prefix), so
     * we subtract one extra slot in that case to land on the
     * caller-visible count. */
    int supplied = (int)argv->size - fmt_arg_idx - 1;
    int ktm_vararg = maybe_fn && maybe_fn->type &&
                       maybe_fn->type->has_var_args &&
                       maybe_fn->kind != AST_EXTERN_FUNC;
    if (ktm_vararg && supplied > 0) supplied -= 1;
    /* `...name`: the extras are passed as their count and an array, a
     * spread `...other` with a count only known at run time */
    Vec *params = maybe_fn ? parseGetFunctionParams(maybe_fn) : NULL;
    if (params && params->size >= 2 && argv->size == params->size) {
        Ast *last = params->entries[params->size - 1];
        if (last && last->kind == AST_LVAR && (last->flags & AST_FLAG_VARARGS)) {
            Ast *count = argv->entries[argv->size - 2];
            if (!count || count->kind != AST_LITERAL) return;
            supplied = (int)count->i64;
        }
    }
    if (supplied == expected)
        return;

    char *raw = mprintf(
        "printf format expects %d argument%s but %d %s supplied",
        expected, expected == 1 ? "" : "s",
        supplied, supplied == 1 ? "was" : "were");
    if (!at) {
        cctrlRaiseException(cc, "%s", raw);
    }
    AoStr *bold = aoStrNew();
    aoStrCatColoured(bold, ESC_BOLD, raw);
    AoStr *buf = cctrlCreateErrorLineAt(cc, at->line, at->col, at->len,
                                        bold->data, CCTRL_ERROR, NULL);
    aoStrRelease(bold);
    CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, NULL);
    d->line = d->end_line = at->line;
    d->col = at->col;
    d->end_col = at->col + at->len;
    cctrlDiagPush(cc, d);
    cctrlTerminate(cc);
}

/* The stdlib's `struct Error`, which a throw carries */
int parseIsErrorType(AstType *type) {
    return type && type->kind == AST_TYPE_CLASS && type->clsname &&
           type->clsname->len == 5 && !memcmp(type->clsname->data, "Error", 5);
}

/* The arguments of `throw(`...`)`, the `(` just consumed:
 *   throw(code)            *_code = code, NULL returned (irLowerThrow
 *                          calls KTM_ThrowAt with the code, file, line)
 *   throw(code, fmt, ...)  checked as a call to KTM_ThrowFmt (types and
 *                          the format), returned as KTM_ThrowFmtAt(file,
 *                          line, code, fmt, ...)
 *   throw(e)               e an Error: KTM_ThrowError(&e) */
Ast *parseThrowCall(Cctrl *cc, Ast *file, int line, Ast **_code) {
    char *fname = astRuntimeName("__ktmThrowFmt");
    int len = strlen(fname);
    Ast *decl = findFunctionDecl(cc, fname, len);
    *_code = NULL;

    ParseCallLoc loc = parseCallLocStart(cc, fname, len);
    loc.args = vecNew(&vec_unsigned_long_type);
    Vec *argv = parseArgv(cc, decl, ')', fname, len, loc.args);
    parseCallLocEnd(cc, &loc);

    if (argv->size == 0 || !argv->entries[0]) {
        parseCallLocFreeArgs(&loc);
        parseRaiseCallError(cc, &loc, "`throw` needs a code, or an Error to throw again");
    }
    if (argv->size == 1) {
        Ast *arg = argv->entries[0];
        if (!parseIsErrorType(arg->type)) {
            if (arg->type && (arg->type->kind == AST_TYPE_CLASS ||
                              arg->type->kind == AST_TYPE_UNION) &&
                !arg->type->is_intrinsic)
            {
                parseRaiseCallError(cc, &loc,
                    "`throw` needs an integer code (usually an enum member) "
                    "or an Error, got %s", astTypeToString(arg->type));
            }
            parseCallLocFreeArgs(&loc);
            *_code = arg;
            return NULL;
        }
        if (!assertLValue(arg)) {
            parseRaiseCallError(cc, &loc, "`throw` of an Error needs a variable, as in `throw(e)`");
        }
        parseCallLocFreeArgs(&loc);
        Vec *args = astVecNew();
        vecPush(args, astUnaryOperator(astMakePointerType(arg->type),
                                       AST_UN_OP_ADDR_OF, arg));
        char *sym = astRuntimeSymbol("__ktmThrowError");
        return astFunctionCall(ast_void_type, sym, strlen(sym), args);
    }
    if (!decl) {
        parseCallLocFreeArgs(&loc);
        parseRaiseCallError(cc, &loc, "`throw` with a message needs the runtime (rt.__ktmThrowFmt)");
    }
    parseFunctionArgumentCheck(cc, decl, argv, "throw", 5, &loc);
    parseFlattenDefaultArgs(decl, argv);
    parseAddEmptyVarArgCount(decl, argv);
    parseCoerceArgs(decl, argv);
    parsePrintfFormatCheck(cc, decl, argv, fname, len, &loc);
    parseCallLocFreeArgs(&loc);

    Vec *at = astVecNew();
    vecPush(at, file);
    vecPush(at, astI64Type(line));
    for (u64 i = 0; i < argv->size; ++i) {
        vecPush(at, argv->entries[i]);
    }
    char *sym = astRuntimeSymbol("__ktmThrowFmtAt");
    return astFunctionCall(ast_void_type, sym, strlen(sym), at);
}

/* Read function arguments for a function being called */
Ast *parseFunctionArguments(Cctrl *cc, char *fname, int len, s64 terminator) {
    AstType *rettype = NULL;
    Ast *maybe_fn = findFunctionDecl(cc,fname,len);

    ParseCallLoc loc = parseCallLocStart(cc, fname, len);
    loc.args = vecNew(&vec_unsigned_long_type);
    Vec *argv = parseArgv(cc,maybe_fn,terminator,fname,len,loc.args);
    parseCallLocEnd(cc, &loc);

    if (maybe_fn) {
        parseFunctionArgumentCheck(cc,maybe_fn,argv,fname,len,&loc);
        parseFlattenDefaultArgs(maybe_fn, argv);
        parseAddEmptyVarArgCount(maybe_fn, argv);
        parseCoerceArgs(maybe_fn, argv);
        parsePrintfFormatCheck(cc, maybe_fn, argv, fname, len, &loc);
        parseCallLocFreeArgs(&loc);


        rettype = maybe_fn->type->rettype;
        if (maybe_fn->flags & AST_FLAG_INLINE && !(cc->flags & CCTRL_TRANSPILING)) {
            if (maybe_fn->kind == AST_ASM_FUNC_BIND || maybe_fn->kind == AST_ASM_FUNCDEF) {
                Ast *call = astAsmFunctionCall(rettype,
                                               aoStrDup(maybe_fn->asmfname),
                                               astVecNew());
                call->flags |= maybe_fn->flags;
                return call;
            }
            return parseInlineFunctionCall(cc, maybe_fn, argv);
        }
    }

    if (!maybe_fn) {
        parseCallLocFreeArgs(&loc);
        if ((len == 6 && !strncmp(fname,"printf",6))) {
            rettype = ast_int_type;
            /* Implicit C printf(fmt, ...): widen F32 varargs to F64. */
            parsePromoteVarargFloats(argv, 1);
            return astFunctionCall(rettype,fname,len,argv);
        }
        /* Walk back until we find the missing function's name, so the
         * error points at it. Rewind wraps around the token ring, so stop
         * once every slot is in front of us again; if a long argument
         * list pushed the name out of the ring, go back to where we were
         * and report there. */
        TokenRingBuffer *ring = cc->token_buffer;
        int rewinds = 0;
        Lexeme *peek = cctrlTokenPeek(cc);
        while (peek == NULL || peek->len != len ||
               memcmp(fname,peek->start,len) != 0) {
            if (ring->size >= ring->capacity) {
                while (rewinds--) cctrlTokenGet(cc);
                break;
            }
            cctrlTokenRewind(cc);
            peek = cctrlTokenPeek(cc);
            rewinds++;
        }
        cctrlRaiseException(cc,"Function: %.*s() not defined",len,fname);
    }

    switch (maybe_fn->kind) {
        case AST_LVAR:
        case AST_GVAR:
        case AST_FUNPTR:
            return astFunctionPtrCall(rettype,fname,len,argv,maybe_fn);

        case AST_ASM_FUNCDEF:
        case AST_ASM_FUNC_BIND:
            return astAsmFunctionCall(rettype,
                    aoStrDup(maybe_fn->asmfname),argv);

        case AST_EXTERN_FUNC:
        case AST_FUNC:
        case AST_FUN_PROTO:
            /* A module's function is called by its symbol (`a.b.f` or
             * `f` in its module names `_K1a1b1f`) */
            if (cc->cur_module || memchr(fname, '.', len)) {
                return astFunctionCall(rettype, maybe_fn->fname->data,
                                       maybe_fn->fname->len, argv);
            }
            return astFunctionCall(rettype,fname,len,argv);
        default: {
            cctrlRaiseException(cc,"Unknown function: %.*s", len, fname);
        }
    }
}

/* The method `name` of `cls` or of a class it inherits from, the
 * nearest one (a method of a derived class hides its base's), with
 * `*owner` the class that has it; NULL if there is none. A method is
 * the function `Class.name`. */
static Ast *parseShadowedPrefix(Cctrl *cc, Lexeme *tok);
Ast *parseGetClassField(Cctrl *cc, Ast *cls);

static Ast *parseFindMethod(Cctrl *cc, AstType *cls, char *name, int len,
                            AstType **owner) {
    for (AstType *t = cls; t; t = t->base_cls) {
        if (!t->clsname) continue;
        char *key = mprintf("%s.%.*s", t->clsname->data, len, name);
        Ast *fn = mapGet(cc->global_env, key);
        /* inside its own body (a recursive call) only its symbol is
         * registered yet */
        if (!fn) fn = mapGet(cc->global_env, astModuleMangle(key, strlen(key)));
        if (fn && (fn->kind == AST_FUNC || fn->kind == AST_FUN_PROTO)) {
            *owner = t;
            return fn;
        }
    }
    return NULL;
}

/* A method of a module's type, used outside that module: it must be pub */
static void parseCheckMethodAccess(Cctrl *cc, AstType *owner, char *name,
                                   int len) {
    AoStr *cls = owner->clsname;
    int mod_len = -1;
    for (int i = (int)cls->len - 1; i >= 0; --i) {
        if (cls->data[i] == '.') { mod_len = i; break; }
    }
    if (mod_len < 0) return;
    AoStr *here = cctrlModulePath(cc, cc->cur_module);
    if (here && (int)here->len == mod_len && !memcmp(here->data, cls->data, mod_len)) {
        return;
    }
    char *key = mprintf("%s.%.*s", cls->data, len, name);
    if (setHas(cc->pub_names, key)) return;
    cctrlRewindUntilStrMatch(cc, name, len, NULL);
    cctrlRaiseException(cc, "the method `%.*s` of `%s` is private to module %.*s",
                        len, name, cls->data, mod_len, cls->data);
}

/* The arguments of a call to the method `fn`, the `(` just taken. With
 * `recv` (`u.name(...)`, a pointer to the object) the arguments are the
 * method's own and `recv` becomes `self`; without (`User.name(p, ...)`)
 * the first argument is `self`. */
static Ast *parseMethodCall(Cctrl *cc, Ast *fn, AstType *owner, Ast *recv,
                            char *name, int len) {
    Ast *decl = fn;
    if (recv) {
        /* The method as seen by the caller: without `self` */
        Vec *params = astVecNew();
        for (u64 i = 1; i < fn->params->size; ++i) {
            vecPush(params, fn->params->entries[i]);
        }
        decl = (Ast *)malloc(sizeof(Ast));
        *decl = *fn;
        decl->params = params;
        decl->type = astTypeCopy(fn->type);
        decl->type->params = params;
    }
    char *fname = mprintf("%s.%.*s", owner->clsname->data, len, name);
    int flen = strlen(fname);

    ParseCallLoc loc = parseCallLocStart(cc, name, len);
    loc.args = vecNew(&vec_unsigned_long_type);
    Vec *argv = parseArgv(cc, decl, ')', fname, flen, loc.args);
    parseCallLocEnd(cc, &loc);
    parseFunctionArgumentCheck(cc, decl, argv, fname, flen, &loc);
    parseFlattenDefaultArgs(decl, argv);
    parseAddEmptyVarArgCount(decl, argv);
    parseCoerceArgs(decl, argv);
    parsePrintfFormatCheck(cc, decl, argv, fname, flen, &loc);
    parseCallLocFreeArgs(&loc);

    if (recv) {
        AstType *self_type = ((Ast *)fn->params->entries[0])->type;
        if (recv->type->ptr != self_type->ptr) {
            /* a derived class's object, for its base's method */
            recv = astCast(recv, self_type);
        }
        vecInsertAt(argv, recv, 0);
    }
    return astFunctionCall(fn->type->rettype, fn->fname->data,
                           fn->fname->len, argv);
}

/* Rewrite a print statement's format literal so integer conversions with
 * no length modifier read 64 bits: `%d` -> `%lld`, `%-5x` -> `%-5llx`.
 * In ktm `%d` is an I64 but the statement lowers to C's printf, where
 * it is a 32-bit int. Explicit modifiers (`%ld`, `%hd`) and every other
 * conversion are copied untouched. Works on the raw source text, so
 * escape pairs are skipped whole. Returns NULL if nothing changed. */
static AoStr *parsePrintFormatTo64(AoStr *fmt, int *added) {
    AoStr *out = NULL;
    int i = 0;
    int copied = 0; /* source chars already appended to `out` */
    int len = fmt->len;
    char *s = fmt->data;
    *added = 0;

    while (i < len) {
        if (s[i] == '\\' && i + 1 < len) {
            i += 2;
            continue;
        }
        if (s[i] != '%') {
            i++;
            continue;
        }
        if (i + 1 < len && s[i+1] == '%') {
            i += 2;
            continue;
        }
        int j = i + 1;
        while (j < len && strchr("-+ #0123456789.*'", s[j])) j++;
        if (j < len && strchr("diuxXo", s[j])) {
            if (!out) out = aoStrNew();
            aoStrCatLen(out, s + copied, j - copied);
            aoStrCatLen(out, "ll", 2);
            copied = j;
            *added += 2;
        }
        i = j;
    }
    if (out) {
        aoStrCatLen(out, s + copied, len - copied);
    }
    return out;
}

/* Whether a print statement's format literal (raw source text) uses a
 * conversion C's printf doesn't have or reads differently: %Q %C */
static int parsePrintFormatIsktm(char *s, int len) {
    int i = 0;
    while (i < len) {
        if (s[i] == '\\' && i + 1 < len) {
            i += 2;
            continue;
        }
        if (s[i] != '%') {
            i++;
            continue;
        }
        i++;
        while (i < len && strchr("-0+ #", s[i])) i++;
        while (i < len && (strchr("*.", s[i]) || (s[i] >= '0' && s[i] <= '9'))) i++;
        while (i < len && strchr("lhzjtLq", s[i])) i++;
        if (i < len && strchr("QC", s[i])) return 1;
        i++; /* the conversion, `%` of `%%` included */
    }
    return 0;
}

/* A string literal used as a statement, `"fmt", args...;`, is a call to
 * C's printf. When the format is a literal its integer conversions are
 * made 64-bit to match ktm's `%d` (see parsePrintFormatTo64) and the
 * call is flagged so the IR widens narrow integer arguments to match.
 * A non-literal format is passed through as written. A literal that uses
 * a ktm-only conversion (%Q %C) calls the library's Print
 * instead, which formats like StrPrint; plain formats keep the direct
 * printf call. */
/* A print: the arguments of `"fmt", args;` (terminator `;`) or of
 * `printf(fmt, args)` (terminator `)`), with the format still to come.
 * A literal format using ktm's conversions (%Q %C) calls Print;
 * any other calls C's printf with its integer conversions widened to 64
 * bits, and the arguments are checked against the format either way. */
Ast *parsePrintCall(Cctrl *cc, s64 terminator) {
    Lexeme *tok = cctrlTokenPeek(cc);
    if (!(cc->flags & CCTRL_TRANSPILING) && tok && tok->tk_type == TK_STR) {
        char removed = parseFormatRemovedConv(tok->start, tok->len, 1);
        if (removed) {
            parseFormatRemovedError(cc, removed, tok->line, tok->col, tok->len);
        }
    }
    if (!(cc->flags & CCTRL_TRANSPILING) && tok && tok->tk_type == TK_STR &&
        parsePrintFormatIsktm(tok->start, tok->len)) {
        char *print = astRuntimeName("__ktmPrintf");
        return parseFunctionArguments(cc,print,strlen(print),terminator);
    }
    Ast *call = parseFunctionArguments(cc,"printf",6,terminator);
    if (cc->flags & CCTRL_TRANSPILING) return call;
    if (call->kind != AST_FUNCALL || !call->args || call->args->size == 0)
        return call;

    Ast *fmt = call->args->entries[0];
    if (fmt->kind != AST_STRING || !fmt->sval) return call;

    int added = 0;
    AoStr *wide = parsePrintFormatTo64(fmt->sval, &added);
    if (wide) {
        /* String literals are interned and shared, so make a new one
         * rather than editing this one in place. */
        call->args->entries[0] = cctrlGetOrSetString(cc, wide->data,
                wide->len, fmt->real_len + added);
        aoStrRelease(wide);
    }
    call->flags |= AST_FLAG_PRINT_STMT;
    return call;
}

Ast *parsePrintStatement(Cctrl *cc) {
    return parsePrintCall(cc, ';');
}

/* The arguments of a call to `name`, the `(` just read. `printf(...)` is
 * ktm's print (see parsePrintCall), not a bare call to C's printf. */
Ast *parseCallArguments(Cctrl *cc, char *name, int len) {
    if (len == 6 && !memcmp(name, "printf", 6)) {
        return parsePrintCall(cc, ')');
    }
    return parseFunctionArguments(cc, name, len, ')');
}

/* `Name.member`: `member`'s constant value, typed as the enum `Name`.
 * Only reached when `name` names an enum (see below); the `.` is
 * already consumed. */
static Ast *parseEnumMemberRef(Cctrl *cc, AstType *enum_type, char *name,
                               int len)
{
    Lexeme *tok = cctrlTokenGet(cc);
    if (!tok || tok->tk_type != TK_IDENT) {
        cctrlRaiseException(cc,"Expected an enum member name after `%.*s.`",
                len, name);
    }
    Ast *member = mapGetLen(enum_type->enum_members, tok->start, tok->len);
    if (!member) {
        cctrlRewindUntilStrMatch(cc, tok->start, tok->len, NULL);
        cctrlRaiseException(cc,"enum `%.*s` has no member `%.*s`",
                len, name, tok->len, tok->start);
    }
    return member;
}

/* parse either a function call or a variable being used */
static Ast *parseIdentifierOrFunction(Cctrl *cc,
                                      char *name,
                                      int len,
                                      int can_call_function)
{
    Ast *ast = NULL;

    Lexeme *tok = cctrlTokenGet(cc);
    Lexeme *peek = cctrlTokenPeek(cc);
    int is_lparen = tokenPunctIs(tok,'(');

    if (tokenPunctIs(tok,'.')) {
        AstType *enum_type = NULL;
        char *qual = cctrlQualifyName(cc, name, len);
        if (qual) enum_type = mapGet(cc->clsdefs, qual);
        if (!enum_type && !cctrlModuleHides(cc, name, len)) {
            enum_type = mapGetLen(cc->clsdefs, name, len);
            if (enum_type) cctrlCheckModuleAccess(cc, name, len);
        }
        if (enum_type && enum_type->kind == AST_TYPE_CLASS &&
            enum_type->is_enum)
        {
            return parseEnumMemberRef(cc, enum_type, name, len);
        }
        if (!enum_type && !cctrlModuleHides(cc, name, len)) {
            enum_type = mapGetLen(cc->uniondefs, name, len);
        }
        /* `User.name`: a method, called with its `self` as the first
         * argument (`User.describe(self)` from a derived class's method)
         * or its address taken (`&User.describe`) */
        if (enum_type && !enum_type->is_intrinsic &&
            parseIsClassOrUnion(enum_type->kind) &&
            !(cc->localenv && mapGetLen(cc->localenv, name, len)) &&
            !mapGetLen(cc->global_env, name, len))
        {
            Lexeme *mtok = cctrlTokenGetRequired(cc);
            AstType *owner = NULL;
            Ast *method = mtok->tk_type != TK_IDENT ? NULL :
                parseFindMethod(cc, enum_type, mtok->start, mtok->len, &owner);
            if (!method) {
                cctrlRaiseException(cc, "`%.*s` has no method `%s`",
                        len, name, lexemeAsWritten(mtok));
            }
            parseCheckMethodAccess(cc, owner, mtok->start, mtok->len);
            if (tokenPunctIs(cctrlTokenPeek(cc), '(')) {
                cctrlTokenGet(cc);
                return parseMethodCall(cc, method, owner, NULL,
                                       mtok->start, mtok->len);
            }
            if (can_call_function) {
                cctrlRaiseException(cc, "`%s.%.*s` needs its `self`, as in "
                        "`%s.%.*s(p)`", owner->clsname->data, mtok->len,
                        mtok->start, owner->clsname->data, mtok->len,
                        mtok->start);
            }
            return method;
        }
    }

    if ((ast = cctrlGetVar(cc, name, len)) == NULL) {
        cctrlRewindUntilStrMatch(cc, name, len, NULL);
        peek = cctrlTokenPeek(cc);
        if (tok == NULL) {
            /* Identifier at end of input - nothing to suggest from. */
            cctrlRaiseException(cc,
                    "Variable or function `%.*s` has not been defined",
                    len, name);
        }
        if (tok->tk_type == TK_PUNCT) {
            switch (tok->i64) {
                case '(': {
                    char *msg = mprintf("Try defining function `%.*s()`?",len,name);
                    cctrlRaiseSuggestion(cc,msg,"Variable or function `%.*s` has not been defined", len, name);
                    break;
                }
                case '[': {
                    char *msg = mprintf("Try defining array `i64 %.*s[] = {1, 2, 3}`?",len,name);
                    cctrlRaiseSuggestion(cc,msg,"Variable or function `%.*s` has not been defined", len, name);
                    break;
                }
                default:
                    cctrlRaiseException(cc,"Variable or function `%.*s` has not been defined", len, name);
                    break;
            }
        }
    }

    if (!is_lparen) {
        cctrlTokenRewind(cc);
        if (can_call_function) {
            /* A function name without `(` is a call with no arguments
             * ('Function;', 'Function + 1'); only `&Function` takes its
             * address. */
            if (parseIsFunction(ast)) {
                Vec *argv = astVecNew();
                /* Underline the name, the token before the one in hand */
                Lexeme *name_tok = parseLastToken(cc);
                ParseCallLoc loc = {0, 0, 0, NULL};
                if (name_tok) {
                    loc.line = name_tok->line;
                    loc.col = name_tok->col;
                    loc.len = name_tok->len;
                }
                parseFunctionArgumentCheck(cc,ast,argv,ast->fname->data,ast->fname->len,&loc);
                parseFlattenDefaultArgs(ast, argv);
                parseAddEmptyVarArgCount(ast, argv);
                /* Default values convert to their parameter's type here
                 * too (a Bool default of 0x800 is 1), as with parens */
                parseCoerceArgs(ast, argv);
                if (ast->flags & AST_FLAG_INLINE && !(cc->flags & CCTRL_TRANSPILING)) {
                    if (ast->kind == AST_ASM_FUNC_BIND || ast->kind == AST_ASM_FUNCDEF) {
                        Ast *call = astAsmFunctionCall(ast->type->rettype, aoStrDup(ast->asmfname), argv);
                        call->flags |= ast->flags;
                        return call;
                    }
                    return parseInlineFunctionCall(cc,ast,argv);
                }
                if (ast->kind == AST_ASM_FUNC_BIND || ast->kind == AST_ASM_FUNCDEF) {
                    return astAsmFunctionCall(ast->type->rettype,
                            aoStrDup(ast->asmfname),argv);
                } else {
                    return astFunctionCall(ast->type->rettype,
                            ast->fname->data,ast->fname->len,argv);
                }
            }
        }
        return ast;
    }

    /* Is a function call if the next char is '(' and the peek is not a
     * type. peek == NULL (end of input) counts as a call attempt - the
     * argument parser raises the unterminated-`(` diagnostic. A type
     * name is never followed by `.`, so `f(Color.red)` is a call whose
     * first argument is an enum member, not a postfix cast. */
    Lexeme *after_peek = cctrlTokenPeekBy(cc,1);
    int peek_is_fn_type = peek && tokenMayStartFnType(peek) && parseFnStartsType(cc);
    /* `Call((fn(i64) i64)raw)` passes a cast; only something that can't
     * be called takes `((fn(i64) i64) *)` as a postfix cast */
    if (peek_is_fn_type && tokenPunctIs(peek,'(')) {
        Ast *callee = findFunctionDecl(cc,name,len);
        if (callee && callee->type && (callee->type->kind == AST_TYPE_FUNC ||
                                       callee->kind == AST_FUNC ||
                                       callee->kind == AST_FUN_PROTO ||
                                       callee->kind == AST_EXTERN_FUNC)) {
            peek_is_fn_type = 0;
        }
    }
    if (peek == NULL ||
        (!cctrlTokIsKeyword(cc, peek) && !peek_is_fn_type) ||
        tokenPunctIs(after_peek,'.'))
    {
        return parseCallArguments(cc,name,len);
    }

    /* Is a postfix typecast, need to grap the variable and 'ParsePostFixExpr' will do the cast */
    cctrlTokenRewind(cc);
    return ast;
}

/* When transpiling, #defines are kept (CCTRL_PASTE_DEFINES), so a string
 * macro reaches the parser as an identifier */
static int parseIsStringMacro(Cctrl *cc, Lexeme *tok) {
    if (!(cc->flags & CCTRL_PASTE_DEFINES) || !tok ||
        tok->tk_type != TK_IDENT) {
        return 0;
    }
    Lexeme *def = mapGetLen(cc->macro_defs, tok->start, tok->len);
    return def && def->tk_type == TK_STR;
}

/* Adjacent string literals are one string, `"a" "b"`. When transpiling,
 * string macros can be part of it too (`"a" M "b"`, `M "x"`, `M N`): the
 * node then holds that C text as written (AST_FLAG_RAW_C_STRING), as the
 * #define is emitted and C pastes adjacent literals as ktm does.
 * Without this the parser stopped at the macro and the statement was
 * dropped from the C. `first` is the token just read, a string or a
 * string macro followed by one. */
static Ast *parseStringLiterals(Cctrl *cc, Lexeme *first) {
    AoStr *str = aoStrNew();
    AoStr *raw = aoStrNew();
    s64 real_len = 1;
    int macros = 0;
    Lexeme *tok = first;
    while (tok && (tok->tk_type == TK_STR || parseIsStringMacro(cc, tok))) {
        if (raw->len) aoStrPutChar(raw, ' ');
        if (tok->tk_type == TK_STR) {
            aoStrCatFmt(str, "%.*s", tok->len, tok->start);
            aoStrCatFmt(raw, "\"%.*s\"", tok->len, tok->start);
            real_len += tok->i64 - 1;
        } else {
            Lexeme *def = mapGetLen(cc->macro_defs, tok->start, tok->len);
            aoStrCatLen(raw, tok->start, tok->len);
            real_len += def->i64 - 1;
            macros++;
        }
        tok = cctrlTokenGet(cc);
    }
    if (tok) cctrlTokenRewind(cc);

    Ast *ast;
    if (macros) {
        ast = astString(raw->data, raw->len, real_len);
        ast->flags |= AST_FLAG_RAW_C_STRING;
    } else {
        ast = cctrlGetOrSetString(cc, str->data, str->len, real_len);
    }
    aoStrRelease(str);
    aoStrRelease(raw);
    return ast;
}

static Ast *parsePrimary(Cctrl *cc) {
    Ast *ast;
    Lexeme *prev,*tok;
    int can_call_function = 1;
    cctrlTokenRewind(cc);

    prev = cctrlTokenGet(cc);
    tok = cctrlTokenGet(cc);

    if (!tok) {
        return NULL;
    }

    if (tokenPunctIs(prev,'&')) {
        can_call_function = 0;
    }

    switch (tok->tk_type) {
    case TK_IDENT: {
        /* `M "x"`, `M N`: a string macro pasted to strings (transpiling) */
        if (parseIsStringMacro(cc, tok)) {
            Lexeme *next = cctrlTokenPeek(cc);
            if (next && (next->tk_type == TK_STR ||
                         parseIsStringMacro(cc, next))) {
                return parseStringLiterals(cc, tok);
            }
        }
        if ((ast = parseShadowedPrefix(cc, tok)) != NULL) {
            return ast;
        }
        ast = parseIdentifierOrFunction(cc, tok->start, tok->len,
                can_call_function);
        if (tokenPunctIs(prev, '&')) {
            if (ast->flags & AST_FLAG_INLINE) {
                Lexeme *peek = cctrlTokenPeek(cc);
                while (peek->tk_type != TK_PUNCT && peek->i64 != '&') {
                    cctrlTokenRewind(cc);
                    peek = cctrlTokenPeek(cc);
                }
                cctrlRaiseException(cc,"Inline functions cannot be used as function pointers: '%.*s'",tok->len, tok->start);
            }
        }
        return ast;
    }
    case TK_I64: {
        ast = astI64Type(tok->i64);
        if (tok->isu64) ast->type = astTypeCopy(ast_uint_type);
        return ast;
    }
    case TK_F64:
        return astF64Type(tok->f64);
    case TK_CHAR_CONST:
        ast = astCharType(tok->i64);
        if (tok->i64 > UCHAR_MAX) {
            ast->type = ast_uint_type;
        }
        return ast;
    case TK_STR:
        return parseStringLiterals(cc, tok);
    case TK_PUNCT:
        cctrlTokenRewind(cc);
        return NULL;
    case TK_EOF:
        cctrlRaiseException(cc,"Unexpected EOF");
        break;
    default:
        cctrlRaiseException(cc,"Unexpected input %s",astKindToString(tok->tk_type));
    }
}

/* `str.x` where a local or parameter `str` is in scope: as in Go the
 * local hides the module imported as str, so it is the local's member x
 * (the lexer had made the one token `str.x`). Not in the local's own
 * initialiser, which still sees the module. NULL when no local hides the
 * prefix. */
static Ast *parseShadowedPrefix(Cctrl *cc, Lexeme *tok) {
    if (!tok->qual_prefix || !cc->localenv) return NULL;
    Ast *local = mapGetLen(cc->localenv, tok->qual_prefix, tok->qual_prefix_len);
    if (!local || local == cc->decl_in_init) return NULL;
    const char *dot = strrchr(tok->start, '.');
    if (!dot) return NULL;
    Lexeme *member = lexemeCopy(tok);
    member->start = (char *)dot + 1;
    member->len = tok->len - (int)(dot + 1 - tok->start);
    member->col = tok->col + tok->qual_prefix_len + 1;
    member->qual_prefix = NULL;
    member->qual_prefix_len = 0;

    /* a member it doesn't have: say so, and why the module isn't used */
    AstType *type = local->type;
    if (type && type->kind == AST_TYPE_POINTER && type->ptr &&
        parseIsClassOrUnion(type->ptr->kind)) {
        type = type->ptr;
    }
    int has = local->kind == AST_LVAR && (local->flags & AST_FLAG_VARARGS) &&
              member->len == 3 && !memcmp(member->start, "len", 3);
    if (!has && type && parseIsClassOrUnion(type->kind)) {
        AstType *owner = NULL;
        has = (type->fields && mapGetLen(type->fields, member->start, member->len)) ||
              parseFindMethod(cc, type, member->start, member->len, &owner);
    }
    if (!has) {
        cctrlRaiseExceptionAt(cc, member->line, member->col, member->len,
                "%s has no member `%.*s` (note: the local `%.*s` hides the "
                "module %.*s)", astTypeToString(local->type), member->len,
                member->start, tok->qual_prefix_len, tok->qual_prefix,
                (int)(dot - tok->start), tok->start);
    }
    cc->pending_token = member;
    return parseGetClassField(cc, local);
}

static int parseGetPriority(Lexeme *tok) {
    switch (tok->i64) {
    case TK_ARROW: 
    case '.':
    case '(':
        return 1;

    case '[':
        return 2;

    case '!':
    case '~':
    case TK_PLUS_PLUS: 
    case TK_MINUS_MINUS: 
    case TK_PRE_PLUS_PLUS:
    case TK_PRE_MINUS_MINUS:
        return 3;

    case '*': case '/': case '%':
        return 4;

    case '-':
    case '+':
        return 5;

    case TK_SHL:
    case TK_SHR:
        return 6;

    case TK_LESS_EQU: 
    case TK_GREATER_EQU:
    case '<':
    case '>':
        return 7;

    case TK_EQU_EQU:
    case TK_NOT_EQU:
        return 8;

    case '^': 
        return 9;
    case '&':
        return 10;
    case '|':
        return 11;

    case TK_AND_AND:
        return 12;

    case TK_OR_OR:
        return 13;

    case '?':   /* the conditional operator: looser than ||, like assignment */
        return 14;

    case '=':
    case TK_ADD_EQU: 
    case TK_SUB_EQU: 
    case TK_MUL_EQU: 
    case TK_DIV_EQU: 
    case TK_MOD_EQU: 
    case TK_AND_EQU: 
    case TK_OR_EQU:  
    case TK_XOR_EQU: 
    case TK_SHL_EQU: 
    case TK_SHR_EQU:
        return 15;

    default:
        return -1;
    }
}

static Ast *parseCreateBinaryOpAt(Cctrl *cc, AstBinOp operation, Ast *left,
                                  Ast *right, s64 op_tok);

Ast *parseSubscriptExpr(Cctrl *cc, Ast *ast) {
    Ast *subscript = parseExpr(cc,16);
    if (subscript == NULL) {
        cctrlRewindUntilPunctMatch(cc,'[',NULL);
        Lexeme *tok = cctrlTokenPeek(cc);
        cctrlRaiseException(cc,"Failed to parse subscript value last valid %s was `%s`",
                lexemeTypeToString(tok->tk_type),lexemeAsWritten(tok));
    }
    cctrlTokenExpect(cc, ']');
    if (!astIsIntOrIntrinsic(subscript->type)) {
        cctrlRaiseExceptionFromTo(cc, "Subscript expressions must evaluate to an integer type",
            '[', ']',
            "Cannot subscript `%s` with a %s of type `%s`",
            astTypeToString(ast->type),
            astKindToHumanReadable(subscript),
            astTypeToString(subscript->type));
    }

    /* An error points at the `[`: there is no `+` in the source to
     * rewind to, and looking for one lands on an earlier line. */
    Ast *binop = parseCreateBinaryOpAt(cc, AST_BIN_OP_ADD, ast, subscript, '[');

    if (astTypeIsArray(ast->type) && ast->type->len > 0) {
        int ok = 1;
        const s64 idx = evalIntConstExprOrErr(subscript, &ok);

        if (ok && (idx < 0 || idx >= ast->type->len)) {
            cctrlWarningFromTo(cc, "Valid indexes are 0 to the array length minus one",
                '[', ']',
                "Array index %ld is out of bounds for an array of length %d",
                idx, ast->type->len);
        }
    }

    return astUnaryOperator(binop->type->ptr, AST_UN_OP_DEREF, binop);
}

/* `field` (named `name`) is private to another module than this code's */
void parseCheckPrivateField(Cctrl *cc, AstType *field, char *name, int len) {
    if (!field || !field->private_module ||
        field->private_module == cc->cur_module) {
        return;
    }
    cctrlRaiseException(cc, "`%.*s` is a private field of %s", len, name,
                        field->private_of ? field->private_of->data
                                          : "its struct");
}

Ast *parseGetClassField(Cctrl *cc, Ast *cls) {
    AstType *type;
    /* `nums.len` of a `T ...nums` parameter: its hidden count */
    if (cls->kind == AST_LVAR && (cls->flags & AST_FLAG_VARARGS)) {
        Lexeme *field = cctrlTokenGetRequired(cc);
        if (field->tk_type != TK_IDENT || field->len != 3 ||
            memcmp(field->start, "len", 3))
        {
            cctrlRaiseExceptionAt(cc, field->line, field->col, field->len,
                "the variadic parameter `%s` only has `.len`",
                cls->lname->data);
        }
        AoStr *len_name = aoStrPrintf("%s#len", cls->lname->data);
        Ast *count = cctrlGetVar(cc, len_name->data, len_name->len);
        if (!count) {
            cctrlRaiseException(cc, "`%s.len` is not available here",
                                cls->lname->data);
        }
        return count;
    }
    /* `p.x` on a pointer to a class is `p->x` (one level: not `pp.x`) */
    if (cls->type->kind == AST_TYPE_POINTER && cls->type->ptr &&
        parseIsClassOrUnion(cls->type->ptr->kind))
    {
        cls = astUnaryOperator(cls->type->ptr, AST_UN_OP_DEREF, cls);
        cls->deref_symbol = TK_ARROW;
    }
    if (!parseIsClassOrUnion(cls->type->kind)) {
        char *type_str = astTypeToString(cls->type);
        char *var_str = astLValueToString(cls,0);
        cctrlTokenRewind(cc);
        Lexeme *peek = cctrlTokenPeek(cc);
        char *msg = mprintf("Using `%c` is an invalid operand for `%s %s`",peek->i64,
                type_str,var_str);
        cctrlRaiseSuggestion(cc, msg,
                                "Expected as class however got %s `%s %s`",
                                astTypeKindToHumanReadable(cls->type),
                                type_str,
                                var_str);
    }

    type = cls->type;

    Lexeme *tok = cctrlTokenGet(cc);
    if (tok == NULL) {
        cctrlRaiseException(cc,
                "Unexpected end of input after '->' or '.'");
    }
    if (tok->tk_type != TK_IDENT) {
        cctrlRaiseExceptionFromTo(cc,NULL,'-',*tok->start,"Expected class member got %s `%s`",
                            lexemeTypeToString(tok->tk_type),
                            lexemeAsWritten(tok));
    }

    // XXX: This is hacky and only for recusive data types 
    if (type->fields == NULL && astIsDeref(cls)) {
        type = cls->operand->type;
    }
    /* A class that was only forward declared (`class Foo;`) has no
     * field map to look the member up in. */
    if (type->fields == NULL) {
        cctrlRewindUntilStrMatch(cc, tok->start, tok->len, NULL);
        cctrlRaiseException(cc,"Cannot access `%.*s`, %s is incomplete",
                tok->len, tok->start, astTypeToString(cls->type));
    }
    AstType *field = mapGetLen(type->fields, tok->start, tok->len);
    AstType *owner = NULL;
    Ast *method = field ? NULL : parseFindMethod(cc, type, tok->start,
                                                 tok->len, &owner);
    if (method) {
        parseCheckMethodAccess(cc, owner, tok->start, tok->len);
        if (!tokenPunctIs(cctrlTokenPeek(cc), '(')) {
            cctrlRaiseException(cc, "`%s.%.*s` is a method: call it with "
                    "`(...)`, or take its address with `&%s.%.*s`",
                    owner->clsname->data, tok->len, tok->start,
                    owner->clsname->data, tok->len, tok->start);
        }
        cctrlTokenGet(cc);
        Ast *recv;
        if (astIsDeref(cls)) {
            recv = cls->operand;
        } else {
            if (!assertLValue(cls)) {
                cctrlRaiseException(cc, "a method is called on a variable "
                                        "or a pointer, not on a %s",
                                    astKindToHumanReadable(cls));
            }
            recv = astUnaryOperator(astMakePointerType(cls->type),
                                    AST_UN_OP_ADDR_OF, cls);
        }
        return parseMethodCall(cc, method, owner, recv, tok->start, tok->len);
    }
    if (!field) {
        cctrlRewindUntilStrMatch(cc, tok->start, tok->len, NULL);
        cctrlRaiseException(cc,"Property: %.*s does not exist on class", 
                tok->len, tok->start);
    }
    parseCheckPrivateField(cc, field, tok->start, tok->len);

    AoStr *field_name = aoStrDupRaw(tok->start, tok->len);
    Ast *class_ref = astClassRef(field, cls, aoStrMove(field_name));
    return class_ref;
}

static AstType *parseCastTypeName(Cctrl *cc);

/* A function pointer value that `(` after it calls: a class field, an
 * array element, the variable itself after a parenthesised `(*fp)`/`(fp)`,
 * a cast to a function pointer type (`(fv(I64 (*)(I64)))(x)`), the result
 * of a call that returns one (`GetHandler()(x)`) or of an assignment. A
 * function name is not one: `F` alone is already a call. */
static int parseIsCallableFnPtr(Ast *ast) {
    if (!ast || !ast->type || ast->type->kind != AST_TYPE_FUNC) return 0;
    switch (ast->kind) {
        case AST_CLASS_REF:
        case AST_UNOP:
        case AST_FUNPTR:
        case AST_LVAR:
        case AST_GVAR:
        case AST_CAST:
        case AST_FUNCALL:
        case AST_FUNPTR_CALL:
        case AST_ASM_FUNCALL:
        case AST_BINOP:
        case AST_TERNARY:
        case AST_DEFAULT_PARAM: /* `(*f)(x)`, f a parameter with a default */
            return 1;
        default:
            return 0;
    }
}

/* Call a function pointer that is a class field or an array element
 * (`fns[i](x)`), the '(' has been consumed */
static Ast *parseClassFnPtrCall(Cctrl *cc, Ast *class_ref) {
    char *name = "<function pointer>";
    if (class_ref->kind == AST_CLASS_REF) {
        name = class_ref->field;
    } else if (class_ref->kind == AST_UNOP && class_ref->operand) {
        /* `fns[i]` is `*(fns + i)`: name the array */
        Ast *base = class_ref->operand;
        if (base->kind == AST_BINOP) base = base->left;
        if (base->kind == AST_LVAR) name = base->lname->data;
        else if (base->kind == AST_GVAR) name = base->gname->data;
        else if (base->kind == AST_CLASS_REF) name = base->field;
    } else if (class_ref->kind == AST_FUNPTR && class_ref->fname) {
        name = class_ref->fname->data;
    } else if (class_ref->kind == AST_LVAR) {
        name = class_ref->lname->data;
    } else if (class_ref->kind == AST_GVAR) {
        name = class_ref->gname->data;
    } else if ((class_ref->kind == AST_FUNCALL ||
                class_ref->kind == AST_FUNPTR_CALL ||
                class_ref->kind == AST_ASM_FUNCALL) && class_ref->fname) {
        name = class_ref->fname->data;
    }
    int len = strlen(name);
    ParseCallLoc loc = parseCallLocStart(cc, name, len);
    loc.args = vecNew(&vec_unsigned_long_type);
    Vec *argv = parseArgv(cc,class_ref,')',name,len,loc.args);
    parseCallLocEnd(cc, &loc);
    parseFunctionArgumentCheck(cc,class_ref,argv,name,len,&loc);
    parseCallLocFreeArgs(&loc);
    parseFlattenDefaultArgs(class_ref, argv);
    parseAddEmptyVarArgCount(class_ref, argv);
    parseCoerceArgs(class_ref, argv);
    return astFunctionPtrCall(
            class_ref->type->rettype,
            name,
            len,
            argv,
            class_ref);
}

static int parseCompoundAssign(Lexeme *tok, AstBinOp *_op) {
    if (tok->tk_type != TK_PUNCT) {
        return 0;
    }

    switch (tok->i64) {
        case TK_ADD_EQU: *_op = AST_BIN_OP_ADD; return 1;
        case TK_SUB_EQU: *_op = AST_BIN_OP_SUB; return 1;
        case TK_MUL_EQU: *_op = AST_BIN_OP_MUL; return 1;
        case TK_DIV_EQU: *_op = AST_BIN_OP_DIV; return 1;
        case TK_MOD_EQU: *_op = AST_BIN_OP_MOD; return 1;
        case TK_AND_EQU: *_op = AST_BIN_OP_BIT_AND; return 1;
        case TK_OR_EQU:  *_op = AST_BIN_OP_BIT_OR; return 1;
        case TK_XOR_EQU: *_op = AST_BIN_OP_BIT_XOR; return 1;
        case TK_SHL_EQU: *_op = AST_BIN_OP_SHL; return 1;
        case TK_SHR_EQU: *_op = AST_BIN_OP_SHR; return 1;
        default: return 0;
    }

    return 0;
}

/* Inverse of astBinOpFromToken. Returns the source token punct value
 * (e.g. '<', TK_SHL) for an AstBinOp. */
static s64 astBinOpToTokenPunct(AstBinOp op) {
    switch (op) {
        case AST_BIN_OP_ADD:     return '+';
        case AST_BIN_OP_SUB:     return '-';
        case AST_BIN_OP_MUL:     return '*';
        case AST_BIN_OP_DIV:     return '/';
        case AST_BIN_OP_MOD:     return '%';
        case AST_BIN_OP_ASSIGN:  return '=';
        case AST_BIN_OP_LT:      return '<';
        case AST_BIN_OP_GT:      return '>';
        case AST_BIN_OP_LE:      return TK_LESS_EQU;
        case AST_BIN_OP_GE:      return TK_GREATER_EQU;
        case AST_BIN_OP_EQ:      return TK_EQU_EQU;
        case AST_BIN_OP_NE:      return TK_NOT_EQU;
        case AST_BIN_OP_LOG_AND: return TK_AND_AND;
        case AST_BIN_OP_LOG_OR:  return TK_OR_OR;
        case AST_BIN_OP_BIT_XOR: return '^';
        case AST_BIN_OP_BIT_AND: return '&';
        case AST_BIN_OP_BIT_OR:  return '|';
        case AST_BIN_OP_SHL:     return TK_SHL;
        case AST_BIN_OP_SHR:     return TK_SHR;
        default:                 return 0;
    }
}

/* `op_tok` is the operator token as written in the source, which the
 * diagnostic rewinds to. For a de-sugared compound assignment (`y += p`)
 * that is `+=`, not `+`: rewinding for a `+` that is not there would
 * land before the statement and the per-statement recovery would then
 * re-parse it forever. */
/* A function pointer given one with a different signature: an error
 * naming both, wherever the conversion happens (assignment,
 * initialiser, argument, return, conditional) */
void parseRaiseFnConvert(Cctrl *cc, AstType *expected, AstType *actual) {
    cctrlRaiseException(cc, "cannot convert %s to %s",
            astTypeToString(actual), astTypeToString(expected));
}

static Ast *parseCreateBinaryOpAt(Cctrl *cc, AstBinOp operation, Ast *left,
                                  Ast *right, s64 op_tok)
{
    int is_err = 0;
    Ast *binop = astBinaryOp(operation,left,right,&is_err);
    if (!is_err) {
        /* An integer `/` or `%` by a constant 0 always traps when it
         * runs, and the constant folder can't give it a value. */
        if ((operation == AST_BIN_OP_DIV || operation == AST_BIN_OP_MOD) &&
            !(cc->flags & CCTRL_TRANSPILING) &&
            astIsIntType(left->type) && astIsIntType(right->type))
        {
            int ok = 1;
            s64 divisor = evalIntConstExprOrErr(right, &ok);
            if (ok && divisor == 0) {
                if (op_tok) {
                    cctrlRewindUntilPunctMatch(cc, op_tok, NULL);
                }
                cctrlRaiseException(cc, "%s by zero",
                        operation == AST_BIN_OP_DIV ? "Division" : "Modulo");
            }
        }
        return binop;
    } else if (is_err && !(cc->flags & CCTRL_TRANSPILING)) {
        if (op_tok) {
            cctrlRewindUntilPunctMatch(cc, op_tok, NULL);
        }
        /* Enum mismatches get a message naming the enum, not the
         * generic "operator cannot be applied" one. */
        int is_cmp = operation == AST_BIN_OP_EQ || operation == AST_BIN_OP_NE ||
                     operation == AST_BIN_OP_LT || operation == AST_BIN_OP_LE ||
                     operation == AST_BIN_OP_GT || operation == AST_BIN_OP_GE;
        if (is_cmp && astIsDifferentEnum(left->type, right->type)) {
            cctrlRaiseException(cc,"Cannot compare enum `%s` with enum `%s`",
                    left->type->clsname->data, right->type->clsname->data);
        }
        if (operation == AST_BIN_OP_ASSIGN &&
            astFnPtrMismatch(left->type, right->type)) {
            parseRaiseFnConvert(cc, left->type, right->type);
        }
        if (operation == AST_BIN_OP_ASSIGN && astIsEnumType(left->type)) {
            cctrlRaiseException(cc,
                    "Cannot convert `%s` to enum `%s` without a cast",
                    astTypeToString(right->type), left->type->clsname->data);
        }
        const char *opstr = op_tok ? lexemePunctToStringWithFlags(op_tok,0)
                                   : astBinOpKindToString(operation);
        const char *left_kind = astTypeKindToHumanReadable(left->type);
        const char *right_kind = astKindToHumanReadable(right);
        const char *right_type_kind = astTypeKindToHumanReadable(right->type);
        char *msg = mprintf("`%s %s %s` is invalid",
                            astTypeToString(left->type),
                            opstr,
                            astTypeToString(right->type));
        cctrlRaiseSuggestion(cc,msg,"The `%s` operator cannot be applied to a %s with a %s of %s", opstr, left_kind, right_kind, right_type_kind);
    } else if (cc->flags & CCTRL_TRANSPILING) {
        binop->type = ast_int_type;
    }
    return binop;
}

Ast *parseCreateBinaryOp(Cctrl *cc, AstBinOp operation, Ast *left, Ast *right) {
    return parseCreateBinaryOpAt(cc,operation,left,right,
                                 astBinOpToTokenPunct(operation));
}

/* Usual arithmetic conversions between the two branches of a conditional:
 * a pointer branch wins (the other is an integer or null), otherwise the
 * arithmetic result type as for a binary operator. */
static AstType *parseTernaryResultType(Cctrl *cc, Ast *then, Ast *els) {
    /* Arrays and string literals decay to pointers; a function name is
     * a function pointer. Any such branch gives the pointer type (the
     * other branch is a pointer, an array or a null/integer). */
    AstType *a = astConvertArray(then->type);
    AstType *b = astConvertArray(els->type);
    int a_ptr = a->kind == AST_TYPE_POINTER || a->kind == AST_TYPE_FUNC;
    int b_ptr = b->kind == AST_TYPE_POINTER || b->kind == AST_TYPE_FUNC;
    if (astFnPtrMismatch(a, b)) parseRaiseFnConvert(cc, a, b);
    if (a_ptr) return a;
    if (b_ptr) return b;
    /* Both branches the same enum: keep it (as an assignment would),
     * rather than degrading to the arithmetic result (an integer), as
     * mixed enum/integer branches still do below. */
    if (a->kind == AST_TYPE_CLASS && a->is_enum && b->kind == AST_TYPE_CLASS &&
        b->is_enum && a->clsname && b->clsname &&
        aoStrCmp(a->clsname, b->clsname)) {
        return a;
    }
    AstType *r = astGetResultType(AST_BIN_OP_ADD, a, b);
    return r ? r : a;
}

/* `cond ? then : els`. Only the chosen branch runs (the IR lowers it with
 * a branch and a phi); the result has the common type of the branches,
 * which both are cast to so the phi is well typed. Right-associative and
 * not an lvalue (assertLValue has no AST_TERNARY case), as in C. */
static Ast *parseTernary(Cctrl *cc, Ast *cond) {
    Ast *then = parseExpr(cc, 16);
    if (!then) {
        cctrlRaiseException(cc, "Expected an expression after `?`");
    }
    cctrlTokenExpect(cc, ':');
    Ast *els = parseExpr(cc, 16);
    if (!els) {
        cctrlRaiseException(cc, "Expected an expression after `:`");
    }
    AstType *rt = parseTernaryResultType(cc, then, els);
    then = astCast(then, rt);
    els = astCast(els, rt);
    return astTernary(rt, cond, then, els);
}

Ast *parseExpr(Cctrl *cc, int prec) {
    Ast *LHS, *RHS;
    Lexeme *tok;
    int prec2, next_prec, compound_assign;

    if ((LHS = parseUnaryExpr(cc)) == NULL) {
        return NULL;
    }

    while (1) {
        /* Can be TK_IDENT, or TK_F64 or TK_I64 or when type casting exists '(' TK_PUNCT */
        if ((tok = cctrlTokenGet(cc)) == NULL) {
            return LHS;
        }

        if (tok->tk_type != TK_PUNCT) {
            cctrlTokenRewind(cc);
            return LHS;
        }

        prec2 = parseGetPriority(tok);
        if (prec2 < 0 || prec <= prec2) {
            cctrlTokenRewind(cc);
            return LHS;
        }

        if (tokenPunctIs(tok,'.')) {
            LHS = parseGetClassField(cc,LHS);
            continue;
        }

        if (tokenPunctIs(tok,'?')) {
            LHS = parseTernary(cc,LHS);
            continue;
        }

        if (tokenPunctIs(tok,'(')) {
            /* `arr[i]->f(x)`, `GetS()->f(x)` etc. reach here rather than
             * parsePostFixExpr, so a function pointer field followed by
             * something that is not a type is a call, not a cast */
            Lexeme *peek = cctrlTokenPeek(cc);
            int peek_is_fn_type = peek && tokenMayStartFnType(peek) &&
                                  parseFnStartsType(cc);
            /* `s.cb((fn(i64) i64)raw)`: a call with a cast argument */
            if (peek_is_fn_type && tokenPunctIs(peek,'(') &&
                parseIsCallableFnPtr(LHS)) {
                peek_is_fn_type = 0;
            }
            if (parseIsCallableFnPtr(LHS) &&
                (peek == NULL || (!cctrlTokIsKeyword(cc, peek) &&
                                   !peek_is_fn_type))) {
                LHS = parseClassFnPtrCall(cc,LHS);
                continue;
            }
            /* `(x)(3)`: not a cast (no type follows) and nothing to call */
            if (peek && !cctrlTokIsKeyword(cc, peek) &&
                !peek_is_fn_type) {
                cctrlTokenRewind(cc);
                cctrlRaiseException(cc,"Cannot call %s `%s`: only functions and function pointers can be called",
                        astTypeKindToHumanReadable(LHS->type),
                        astLValueToString(LHS,0));
            }
            /* XXX: Should this check still be here ? Not sure as 
             * casting _anything_ makes sense */
            // assertLValue(LHS,cc->lineno);
            AstType *type = parseCastTypeName(cc);
            LHS = astCast(LHS,type);
            cctrlTokenExpect(cc,')');
            continue;
        }

        if (tokenPunctIs(tok, TK_PLUS_PLUS) || tokenPunctIs(tok, TK_MINUS_MINUS)) {
            if (!assertLValue(LHS)) {
                parseRaiseNotLValue(cc,LHS);
            }
            assertNotArrayAssign(cc,LHS,tok->line,tok->col,tok->len);
            assertNotEnumArithAssign(cc,LHS,tok->line,tok->col,tok->len);
            assertNotConstAssign(cc,LHS);
            AstUnOp op;
            if (!astUnaryOpFromToken(tok->i64, &op)) {
                /* This should be impossible to hit as we've already pre-validated */
                loggerPanic("Invalid token for unary operator; %c\n", (char)tok->i64);
            }
            LHS = astUnaryOperator(LHS->type, op, LHS);
            continue;
        }

        if (tokenPunctIs(tok,'[')) {
            if (LHS->type->kind != AST_TYPE_POINTER && LHS->type->kind != AST_TYPE_ARRAY) {
                cctrlRewindUntilPunctMatch(cc,'[',NULL);
                cctrlRaiseException(cc,"Cannot subscript a %s, only pointers or arrays can be subscripted with '['",
                                   astTypeKindToHumanReadable(LHS->type));
            }
            LHS = parseSubscriptExpr(cc,LHS);
            continue;
        }

        if (tokenPunctIs(tok, TK_ARROW)) {
            if (LHS->type->kind != AST_TYPE_POINTER) {
                cctrlRaiseException(cc,"Pointer type expected got `%s` `%s`",
                        astTypeToString(LHS->type), astLValueToString(LHS,0));
            }
            LHS = astUnaryOperator(LHS->type->ptr, AST_UN_OP_DEREF, LHS);
            LHS->deref_symbol = TK_ARROW;
            LHS = parseGetClassField(cc, LHS);
            continue;
        }

        AstBinOp deconstructed_compound_op;
        compound_assign = parseCompoundAssign(tok, &deconstructed_compound_op);
        if (tokenPunctIs(tok, '=') || compound_assign) {
            if (!assertLValue(LHS)) {
                parseRaiseNotLValue(cc,LHS);
            }
            assertNotConstAssign(cc,LHS);
        }
        
        next_prec = prec2;
        if (tok->i64 == '=') {
            next_prec++;
        }
        /* Copied: parsing a long right hand side can cycle `tok` out of
         * the token ring before a type check warning needs it. */
        int op_line = tok->line, op_col = tok->col;
        int is_array_assign = (tokenPunctIs(tok, '=') || compound_assign) &&
                assertNotArrayAssign(cc,LHS,op_line,op_col,tok->len);
        int is_enum_arith_assign = compound_assign &&
                assertNotEnumArithAssign(cc,LHS,op_line,op_col,tok->len);

        RHS = parseExpr(cc,next_prec);
        if (!RHS) {
            Lexeme *peek = cctrlTokenPeek(cc);
            cctrlTokenRewind(cc);
            char *err_lvar = astLValueToString(LHS,0);
            char *punct_str = lexemePunctToStringWithFlags(tok->i64,0);
            char *msg = mprintf("`%s %s var2` is the expected usage however got `%s`",
                    err_lvar,
                    punct_str,lexemeAsWritten(peek));
            cctrlRaiseSuggestion(cc,msg,"Second operand missing to `%s %s` got invalid %s `%s`",
                                 err_lvar,
                                 punct_str,
                                 lexemeTypeToString(peek->tk_type),
                                 lexemeAsWritten(peek));
        }

        /* This de-sugars the compound assign which I think is okay */
        if (compound_assign) {
            /* Check the operation first so `y += p` reports the same
             * error as `y = y + p` */
            Ast *value = parseCreateBinaryOpAt(cc, deconstructed_compound_op,
                                               LHS, RHS, tok->i64);
            AstType *ok = astTypeCheck(LHS->type,RHS,compound_assign);
            if (!ok && !is_array_assign && !is_enum_arith_assign) {
                typeCheckWarn(cc,op_line,op_col,LHS,RHS);
            } else if (!is_array_assign && !is_enum_arith_assign && LHS->type &&
                       LHS->type->kind == AST_TYPE_POINTER &&
                       value->type && !astTypeCheck(LHS->type,value,
                                                    AST_BIN_OP_ASSIGN)) {
                /* `q -= q` stores a pointer difference (an integer)
                 * back into the pointer, as `q = q - q` would */
                typeCheckWarn(cc,op_line,op_col,LHS,value);
            }
            /* Already reported above: building `c = (c + 1 as int)` here
             * would error again (int -> enum needs a cast), at a stale
             * position. */
            LHS = is_enum_arith_assign ? value
                                       : parseCreateBinaryOp(cc,AST_BIN_OP_ASSIGN, LHS, value);
        } else {
            AstBinOp binop;
            if (!astBinOpFromToken(tok->i64,&binop)) {
                cctrlRaiseException(cc,"Invalid binary operator %c => %d", (char)tok->i64, tok->i64);
            }
            if (!LHS) {
                lexemePrint(tok);
            }

            /* Build the operation first: when it is invalid (`y = p`
             * with a class `p`) its error is the one diagnostic, not a
             * type warning followed by it. */
            Ast *dst = LHS;
            LHS = parseCreateBinaryOp(cc,binop,LHS,RHS);
            if (tok->i64 == '=') {
                AstType *ok = astTypeCheck(dst->type,RHS,AST_BIN_OP_ASSIGN);
                if (!ok && !is_array_assign) {
                    typeCheckWarn(cc,op_line,op_col,dst,RHS);
                }
            }
        }
    }
}

/* `sizeof()`, `sizeof;`: no operand. parseUnaryExpr gives NULL for a
 * token that can't start an expression, which then crashed. Reported at
 * the token that should have been the operand (the `)` of `()`). */
static void parseRaiseMissingSizeofOperand(Cctrl *cc, char *keyword) {
    cctrlRaiseException(cc, "`%s` needs a type or an expression", keyword);
}

/* sizeof/alignof of a module type that isn't complete yet, while a
 * module's type declarations are parsed from their tokens: fail, so it
 * is tried again once the type is complete (parseModuleTypes) instead of
 * folding to a size of 0 */
static void parseCheckSizeKnown(Cctrl *cc, AstType *type) {
    while (type && type->kind == AST_TYPE_ARRAY) type = type->ptr;
    if (cc->replaying && type && !type->is_intrinsic && !type->fields &&
        (type->kind == AST_TYPE_CLASS || type->kind == AST_TYPE_UNION)) {
        cctrlRaiseException(cc, "the size of %s is not known yet",
                            astTypeToString(type));
    }
}

Ast *parseSizeof(Cctrl *cc) {
    Lexeme *tok = cctrlTokenGet(cc);
    Lexeme *peek = cctrlTokenPeek(cc);
    AstType *type = NULL;
    Ast *ast = NULL;

    /* EOF right after `sizeof`: rewinding and re-parsing would re-read
     * the `sizeof` keyword and recurse here forever (stack overflow). */
    if (tok == NULL) {
        cctrlRaiseException(cc, "Unexpected end of input after `sizeof`");
    }
    if (tokenPunctIs(tok,'(') && tokenPunctIs(peek,')')) {
        cctrlTokenGet(cc);
        parseRaiseMissingSizeofOperand(cc, "sizeof");
    }
    if (tokenPunctIs(tok,'(') && peek &&
        (cctrlTokIsKeyword(cc, peek) ||
         (tokenMayStartFnType(peek) && parseFnStartsType(cc)))) {
        type = parseFullType(cc);
        cctrlTokenExpect(cc,')');
    } else {
        cctrlTokenRewind(cc);
        ast = parseUnaryExpr(cc);
        if (!ast || !ast->type) {
            parseRaiseMissingSizeofOperand(cc, "sizeof");
        }
        type = ast->type;
    }

    parseCheckSizeKnown(cc, type);
    if (cc->flags & CCTRL_PRESERVE_SIZEOF) {
        return astSizeOf(type);
    }

    /* If we have a string we want the _true_ length of it which as the string
     * is escaped needs to be calculated manually. */
    s64 size = 0; 
    if (ast && ast->kind == AST_STRING) {
        size = ast->real_len;
    } else {
        /* Otherwise the size is sufficient */
        size = type->size;
    }

    assert(size >= 0);
    return astI64Type(size);
}

/* `alignof(<type or expr>)` - mirrors parseSizeof but folds to the
 * type's alignment. Always a compile-time constant. */
Ast *parseAlignof(Cctrl *cc) {
    Lexeme *tok = cctrlTokenGet(cc);
    Lexeme *peek = cctrlTokenPeek(cc);
    AstType *type = NULL;

    /* Same EOF-recursion hazard as parseSizeof. */
    if (tok == NULL) {
        cctrlRaiseException(cc, "Unexpected end of input after `alignof`");
    }
    if (tokenPunctIs(tok,'(') && tokenPunctIs(peek,')')) {
        cctrlTokenGet(cc);
        parseRaiseMissingSizeofOperand(cc, "alignof");
    }
    if (tokenPunctIs(tok,'(') && peek &&
        (cctrlTokIsKeyword(cc, peek) ||
         (tokenMayStartFnType(peek) && parseFnStartsType(cc)))) {
        type = parseFullType(cc);
        cctrlTokenExpect(cc,')');
    } else {
        cctrlTokenRewind(cc);
        Ast *ast = parseUnaryExpr(cc);
        if (!ast || !ast->type) {
            parseRaiseMissingSizeofOperand(cc, "alignof");
        }
        type = ast->type;
    }

    parseCheckSizeKnown(cc, type);
    return astI64Type(astTypeAlign(type));
}

/* `typeof(<type or expr>)` - same operand handling as sizeof/alignof
 * but folds to a string literal naming the type: typeof(1+1) == "i64",
 * typeof("s") == "u8[2]", typeof(&f) == "f64 *". The expression is
 * only type-checked, never evaluated. */
Ast *parseTypeof(Cctrl *cc) {
    Lexeme *tok = cctrlTokenGet(cc);
    Lexeme *peek = cctrlTokenPeek(cc);
    AstType *type = NULL;

    if (tok == NULL) {
        cctrlRaiseException(cc, "Unexpected end of input after `typeof`");
    }
    if (tokenPunctIs(tok,'(') && peek &&
        (cctrlTokIsKeyword(cc, peek) ||
         (tokenMayStartFnType(peek) && parseFnStartsType(cc)))) {
        type = parseFullType(cc);
        cctrlTokenExpect(cc,')');
    } else {
        cctrlTokenRewind(cc);
        Ast *ast = parseUnaryExpr(cc);
        type = ast ? ast->type : NULL;
    }

    if (!type) {
        cctrlRaiseException(cc,
                "typeof: could not determine the type of the operand");
    }

    AoStr *name = astTypeToAoStr(type);
    /* real_len is the unescaped byte length + NUL; type names contain
     * no escapes so it's just len + 1. */
    return cctrlGetOrSetString(cc, name->data, name->len, name->len + 1);
}

/* The postfix operators - `.`, `->`, `[]`, a postfix cast, a function
 * pointer call and `x++`/`x--` - applied to an already parsed operand.
 * They bind tighter than any prefix operator, so parseUnaryExpr's operand
 * is a primary or parenthesised expression with these applied: `-a[0] == -6`
 * is `(-(a[0])) == -6`, `&t[i].x` is `&(t[i].x)`, `-f(x)[i]` is `-(f(x)[i])` */
static Ast *parsePostFixOps(Cctrl *cc, Ast *ast) {
    AstType *type;
    Lexeme *tok,*peek;

    while (1) {
        tok = cctrlTokenGet(cc);

        if (tokenPunctIs(tok,'.')) {
            ast = parseGetClassField(cc,ast);
            continue;
        }

        if (tokenPunctIs(tok,'[')) {
            if (ast->type->kind != AST_TYPE_POINTER &&
                ast->type->kind != AST_TYPE_ARRAY) {
                cctrlRewindUntilPunctMatch(cc,'[',NULL);
                cctrlRaiseException(cc,"Cannot subscript a %s, only pointers or arrays can be subscripted with '['",
                                   astTypeKindToHumanReadable(ast->type));
            }
            ast = parseSubscriptExpr(cc,ast);
            continue;
        }

        /* Postfix type cast OR function pointer on a class */
        if (tokenPunctIs(tok,'(')) {
            peek = cctrlTokenPeek(cc);
            if (peek == NULL) {
                cctrlRaiseException(cc,
                        "Unexpected end of input after '('");
            }
            /* `cb((fn(i64) i64)raw)` calls cb with a cast argument */
            int paren_arg = tokenPunctIs(peek,'(') &&
                            (ast->kind == AST_CLASS_REF ||
                             parseIsCallableFnPtr(ast));
            if (cctrlTokIsKeyword(cc, peek) ||
                (!paren_arg && tokenMayStartFnType(peek) &&
                 parseFnStartsType(cc))) {
                type = parseCastTypeName(cc);
                cctrlTokenExpect(cc,')');
                ast = astCast(ast,type);
                continue;
            } else if (ast->kind == AST_CLASS_REF ||
                       parseIsCallableFnPtr(ast)) {
                ast = parseClassFnPtrCall(cc,ast);
                continue;
            }
        }

        if (tokenPunctIs(tok,TK_ARROW)) {
            if (ast->type->kind != AST_TYPE_POINTER) {
                char *type_str = astTypeToString(ast->type);
                char *var_str = astLValueToString(ast,0);
                cctrlRaiseException(cc,"Pointer expected got a %s `%s %s`",
                                        astTypeKindToHumanReadable(ast->type),
                                        type_str, 
                                        var_str);
            }
            ast = astUnaryOperator(ast->type->ptr,AST_UN_OP_DEREF,ast);
            ast->deref_symbol = TK_ARROW;
            ast = parseGetClassField(cc,ast);
            continue;
        }

        if (tokenPunctIs(tok,TK_PLUS_PLUS) || 
            tokenPunctIs(tok,TK_MINUS_MINUS)) {
            if (!assertLValue(ast)) {
                parseRaiseNotLValue(cc,ast);
            }
            assertNotArrayAssign(cc,ast,tok->line,tok->col,tok->len);
            assertNotEnumArithAssign(cc,ast,tok->line,tok->col,tok->len);
            assertNotConstAssign(cc,ast);
            if (tok->i64 == TK_PLUS_PLUS) {
                return astUnaryOperator(ast->type,AST_UN_OP_POST_INC,ast);
            } else {
                return astUnaryOperator(ast->type,AST_UN_OP_POST_DEC,ast);
            }
        }

        cctrlTokenRewind(cc);
        return ast;
    }
}

Ast *parsePostFixExpr(Cctrl *cc) {
    Ast *ast;
    /* parse primary rightly or wrongly, amongst other things, either gets a
     * variable or parses a function call. */
    if ((ast = parsePrimary(cc)) == NULL) {
        return NULL;
    }
    return parsePostFixOps(cc,ast);
}

/* `-x`, `+x` and `~x` need a number: an integer, an intrinsic class (an
 * I64 underneath) or, except for `~`, a float. Negating a string, pointer,
 * array or class compiled to arithmetic on its address. `!x` and `&x` take
 * anything and aren't checked here. */
static void parseCheckArithmeticUnaryOp(Cctrl *cc, AstUnOp op, Ast *operand,
                                        int line, int col, int len)
{
    AstType *ty = operand->type;
    if (cc->flags & CCTRL_TRANSPILING) return;
    if (!ty || astIsIntType(ty) || astIsIntrinsicClass(ty) ||
        ty->kind == AST_TYPE_AUTO) {
        return;
    }
    if (op != AST_UN_OP_BIT_NOT && astIsFloatType(ty)) return;

    const char *opstr = op == AST_UN_OP_MINUS ? "-" :
                        op == AST_UN_OP_PLUS  ? "+" : "~";
    char *msg = mprintf("The `%s` operator cannot be applied to a %s of %s",
                        opstr, astKindToHumanReadable(operand),
                        astTypeKindToHumanReadable(ty));
    char *sug = mprintf("`%s` on `%s` is invalid",
                        opstr, astTypeToString(ty));
    AoStr *bold = aoStrNew();
    aoStrCatColoured(bold, ESC_BOLD, msg);
    AoStr *buf = cctrlCreateErrorLineAt(cc, line, col, len, bold->data,
                                        CCTRL_ERROR, sug);
    aoStrRelease(bold);
    CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, aoStrPrintf("%s", sug));
    d->line = d->end_line = line;
    d->col = col;
    d->end_col = col + len;
    cctrlDiagPush(cc, d);
    cctrlTerminate(cc);
}

/* `*x` needs a pointer or an array: `1 +* 2` is `1 + (*2)` and
 * dereferencing an integer left the expression without a type, which
 * crashed ktm later on. */
static void parseCheckDerefOperand(Cctrl *cc, Ast *operand,
                                   int line, int col, int len)
{
    AstType *ty = operand->type;
    if (cc->flags & CCTRL_TRANSPILING) return;
    if (!ty || ty->ptr || ty->kind == AST_TYPE_AUTO) return;

    char *msg = mprintf("The `*` operator cannot be applied to a %s of %s, "
                        "only pointers can be dereferenced",
                        astKindToHumanReadable(operand),
                        astTypeKindToHumanReadable(ty));
    /* A function pointer never gets here: parseUnaryExpr makes `*fp` the
     * function pointer itself */
    char *sug = mprintf("`*` on `%s` is invalid", astTypeToString(ty));
    AoStr *bold = aoStrNew();
    aoStrCatColoured(bold, ESC_BOLD, msg);
    AoStr *buf = cctrlCreateErrorLineAt(cc, line, col, len, bold->data,
                                        CCTRL_ERROR, sug);
    aoStrRelease(bold);
    CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, aoStrPrintf("%s", sug));
    d->line = d->end_line = line;
    d->col = col;
    d->end_col = col + len;
    cctrlDiagPush(cc, d);
    cctrlTerminate(cc);
}

/* Does `tok` start a type name (a built-in type, a class, a union or a
 * typedef-like name), so that `(` before it opens a prefix cast? */
static int parseIsCastTypeName(Cctrl *cc, Lexeme *tok) {
    /* `((fn(i64) i64) *)p` */
    if (tokenPunctIs(tok,'(')) {
        return parseFnStartsType(cc);
    }
    if (tok == NULL ||
        (tok->tk_type != TK_IDENT && tok->tk_type != TK_KEYWORD)) {
        return 0;
    }
    /* `(const ...)`: not a real cast (`const` isn't a type), but routing
     * it through parseCastTypeName reports the specific "const is only
     * allowed on declarations" rather than a parenthesised-expression
     * error. */
    if (tok->tk_type == TK_KEYWORD && tok->i64 == KW_CONST) {
        return 1;
    }
    /* As in C, a variable in scope hides a type of the same name:
     * `(tm.tv_sec)` reads the variable `tm`, not the class. */
    if (tok->tk_type == TK_IDENT &&
        ((cc->localenv && mapGetLen(cc->localenv, tok->start, tok->len)) ||
         mapGetLen(cc->global_env, tok->start, tok->len))) {
        return 0;
    }
    if (tokenIsFnIdent(tok)) {
        return parseFnStartsType(cc);
    }
    AstType *type = cctrlGetKeyWord(cc, tok->start, tok->len);
    /* `(Color.red ^ 1)`: an enum member, not a cast to the enum */
    if (type && type->kind == AST_TYPE_CLASS && type->is_enum &&
        tokenPunctIs(cctrlTokenPeekBy(cc,1),'.')) {
        return 0;
    }
    return type != NULL && type->kind != AST_TYPE_VIS_MODIFIER &&
           type->kind != AST_TYPE_INLINE && type->kind != AST_TYPE_AUTO;
}

/* The type in a prefix cast: `T`, `T *`, ... or a function pointer
 * `T (*)(params)`, whose parameters may be unnamed. */
static AstType *parseCastTypeName(Cctrl *cc) {
    AstType *type = parseDeclSpec(cc);
    if (tokenPunctIs(cctrlTokenPeek(cc), '(')) {
        int has_var_args = 0;
        cctrlTokenGet(cc);
        cctrlTokenExpect(cc,'*');
        cctrlTokenExpect(cc,')');
        cctrlTokenExpect(cc,'(');
        AstType *fn_type = astMakeFunctionType(type, NULL);
        fn_type->params = parseParams(cc,')',&has_var_args,0);
        fn_type->has_var_args = has_var_args;
        return fn_type;
    }
    return type;
}

Ast *parseUnaryExpr(Cctrl *cc) {
    Lexeme *tok;
    Ast *ast;

    if (cc->pending_unary) {
        ast = cc->pending_unary;
        cc->pending_unary = NULL;
        return ast;
    }

    if ((tok = cctrlTokenGet(cc)) == NULL) {
        cctrlRaiseException(cc,"Unexpected end of input");
    }

    if (tok->tk_type == TK_KEYWORD) {
        switch (tok->i64) {
            case KW_SIZEOF: return parseSizeof(cc);
            case KW_ALIGNOF: return parseAlignof(cc);
            case KW_TYPEOF: return parseTypeof(cc);
            case KW_TRUE:
            case KW_FALSE: {
                ast = astI64Type(tok->i64 == KW_TRUE);
                ast->type = cctrlGetKeyWord(cc, "Bool", 4);
                return ast;
            }
            case KW_NULL:
                return astCast(astI64Type(0), astMakePointerType(ast_void_type));
            case KW_DEFINED: {
                cctrlTokenExpect(cc,'(');
                tok = cctrlTokenGetRequired(cc);

                if (tok->i64 != ')') {
                    ast = astI64Type(1);
                } else {
                    ast = astI64Type(0);
                }
                if (tok->i64 != ')') {
                    cctrlTokenExpect(cc,')');
                }
                return ast;
            }
            default: {
                cctrlTokenRewind(cc);
                cctrlRaiseException(cc,"Unexpected keyword: %.*s while parsing unary expression",
                        tok->len,tok->start);
            }
        }
    }

    if (tokenPunctIs(tok,'(')) {
        /* `(T)x`: a C prefix cast when what follows the `(` names a type.
         * Its operand is a unary expression, so the cast binds tighter
         * than any binary operator and `(T)p->f` casts `p->f`, as in C. */
        if (parseIsCastTypeName(cc, cctrlTokenPeek(cc))) {
            if (tokenPunctIs(cctrlTokenPeek(cc),'(')) {
                /* `((fn(i64) i64) *)pp` is a cast to a pointer to a
                 * function pointer, `((fn(i64) i64)raw)(6)` a cast in
                 * parentheses: what follows the inner `)` tells which
                 * (the tokens can't be looked ahead that far) */
                cctrlTokenGet(cc);
                AstType *fn_type = parseFnType(cc);
                cctrlTokenExpect(cc,')');
                Lexeme *after = cctrlTokenPeek(cc);
                if (!tokenPunctIs(after,'*') && !tokenPunctIs(after,')')) {
                    cc->pending_unary = astCast(parseUnaryExpr(cc), fn_type);
                    ast = parseExpr(cc,16);
                    cctrlTokenExpect(cc,')');
                    return parsePostFixOps(cc,ast);
                }
                AstType *type = parsePointerType(cc,fn_type);
                cctrlTokenExpect(cc,')');
                return astCast(parseUnaryExpr(cc), type);
            }
            AstType *type = parseCastTypeName(cc);
            cctrlTokenExpect(cc,')');
            return astCast(parseUnaryExpr(cc), type);
        }
        ast = parseExpr(cc,16);
        cctrlTokenExpect(cc,')');
        return parsePostFixOps(cc,ast);
    }

    if (tok->tk_type == TK_PUNCT) {
        AstUnOp unary_op;
        if (!astUnaryOpFromToken(tok->i64, &unary_op)) {
            cctrlTokenRewind(cc);
            return parsePostFixExpr(cc);
        }

        if (unary_op == AST_UN_OP_POST_INC) {
            unary_op = AST_UN_OP_PRE_INC;
        } else if (unary_op == AST_UN_OP_POST_DEC) {
            unary_op = AST_UN_OP_PRE_DEC;
        }

        Ast *operand = NULL;
        AstType *type = NULL;
        /* The operator's position, copied: parsing a long operand can
         * cycle `tok` out of the token ring before it's type checked. */
        int op_line = tok->line, op_col = tok->col, op_len = tok->len;
        s64 op_punct = tok->i64;

        /* The operand is everything up to the next binary operator: the
         * postfix operators bind tighter (parsePostFixOps), so `-a[0] == -6`
         * negates `a[0]` and `&t[i].x` is `&(t[i].x)`. */
        operand = parseUnaryExpr(cc);
        /* parsePrimary returns NULL when it sees a punct that
         * can't start an expression. Such as a stray
         * slash left over from a malformed block-comment close.
         * Bail with a real error rather than dereffing `NULL`. */
        if (!operand) {
            cctrlRaiseException(cc,
                "Expected expression after unary '%s'",
                lexemePunctToString(op_punct));
        }

        /* A cast is not an l-value, so `&x(T)` takes the address of `x` and
         * casts that: `&pp[2](U8 *)` is `(&pp[2])(U8 *)`. */
        AstType *addr_cast = NULL;
        if (unary_op == AST_UN_OP_ADDR_OF) {
            while (operand->kind == AST_CAST) {
                if (!addr_cast) addr_cast = operand->type;
                operand = operand->operand;
            }
        }

        if (unary_op == AST_UN_OP_PRE_INC || unary_op == AST_UN_OP_PRE_DEC) {
            assertNotArrayAssign(cc,operand,op_line,op_col,op_len);
            assertNotEnumArithAssign(cc,operand,op_line,op_col,op_len);
            assertNotConstAssign(cc,operand);
        }

        switch (unary_op) {
            case AST_UN_OP_ADDR_OF: {
                if (parseIsFunction(operand)) {
                    /* TODO;
                     * Making this a pointer type effects the return type of 
                     * the function. It's hard in this parser to say we want
                     * a pointer to a function not mutate the return value to 
                     * a pointer. This does work however and the "hack" does not
                     * leak. So perhaps we can delete this comment at a later
                     * date */
                    type = operand->type;
                } else {
                    type = astMakePointerType(operand->type);
                }
                break;
            }
            case AST_UN_OP_DEREF:
                /* As in C, `*fp` of a function (pointer) is the function
                 * again: `(*fp)()`, `(**fp)()` and `(*s.cb)(x)` call it */
                if (operand->type && operand->type->kind == AST_TYPE_FUNC) {
                    return operand;
                }
                parseCheckDerefOperand(cc, operand, op_line, op_col, op_len);
                type = operand->type ? operand->type->ptr : NULL;
                /* The transpiler doesn't report type errors (as for a
                 * binary op): `**pp(I64 *)` dereferences the I64 that
                 * `*pp(I64 *)` gives. Without a type the node crashed
                 * it, so write it as an I64 and let the C compiler
                 * reject it. */
                if (!type && (cc->flags & CCTRL_TRANSPILING)) {
                    type = ast_int_type;
                }
                break;
            case AST_UN_OP_BIT_NOT:
                parseCheckArithmeticUnaryOp(cc, unary_op, operand,
                                            op_line, op_col, op_len);
                type = ast_int_type;
                break;
            case AST_UN_OP_PLUS:
            case AST_UN_OP_MINUS:
                parseCheckArithmeticUnaryOp(cc, unary_op, operand,
                                            op_line, op_col, op_len);
                /* U8/U16/U32/I8.. promote to I64 like in a binary op:
                 * typed as the operand `-c` on a U8 5 read back as 251. */
                if (astIsIntType(operand->type) && operand->type->size < 8) {
                    type = ast_int_type;
                } else {
                    type = operand->type;
                }
                break;
            /* 0/1 whatever the operand: typed as the operand, `!f` on
             * an F64 was converted back from a float and read as 0. */
            case AST_UN_OP_LOG_NOT: type = ast_int_type; break;
            default:                type = operand->type; break;
        }

        ast = astUnaryOperator(type, unary_op, operand);
        return addr_cast ? astCast(ast, addr_cast) : ast;
    }
    
    cctrlTokenRewind(cc);
    return parsePostFixExpr(cc);
}
