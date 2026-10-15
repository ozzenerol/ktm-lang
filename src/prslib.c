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
#include "parser.h"
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

/* The `*`s and `[]`s after a type: `u8 *`, `u8[]` (a slice), `u8*[][]` */
AstType *parsePointerType(Cctrl *cc, AstType *type) {
    Lexeme *tok;
    while (1) {
        tok = cctrlTokenGet(cc);
        if (tokenPunctIs(tok, '[') && tokenPunctIs(cctrlTokenPeek(cc), ']')) {
            cctrlTokenGet(cc);
            type = astMakeSliceType(type);
            continue;
        }
        if (!tokenPunctIs(tok, '*')) {
            cctrlTokenRewind(cc);
            return type;
        }
        type = astMakePointerType(type);
    }
    return type;
}

Ast *parseHiddenSliceVar(Cctrl *cc, AstType *type);
static void parseSliceParts(Cctrl *cc, Ast *expr, Ast **_len, Ast **_ptr,
                            int ptr_first);

/* A byte pointer type (u8 *, i8 *), which a String converts to */
static int parseIsBytePtr(AstType *t) {
    return t && t->kind == AST_TYPE_POINTER && t->ptr && t->ptr->size == 1 &&
           !t->ptr->is_bool &&
           (t->ptr->kind == AST_TYPE_CHAR || t->ptr->kind == AST_TYPE_INT);
}

/* `expr` where a `want` is expected: a String (or a String *) is a view
 * of its bytes where a u8[] is, and a String variable or field its C
 * string (s.cstr()) where a u8 * is; an array of want's element type becomes a slice of
 * it (its address and length, in a hidden variable), anything else is
 * returned as is for the type checks */
Ast *parseSliceFrom(Cctrl *cc, AstType *want, Ast *expr) {
    /* only a String that lives in a variable or a field: a call's String
     * would have nobody to free it */
    if (parseIsBytePtr(want) && expr && astTypeIsString(expr->type)) {
        Ast *root = expr;
        while (root->kind == AST_CLASS_REF) root = root->cls;
        if (astIsUnOpKind(root, AST_UN_OP_DEREF)) root = root->operand;
        if (root->kind == AST_LVAR || root->kind == AST_GVAR) {
            return parseStringCPtr(cc, expr);
        }
    }
    if (astTypeIsSlice(want) && expr && expr->type && want->elem->size == 1 &&
        (want->elem->kind == AST_TYPE_CHAR || want->elem->kind == AST_TYPE_INT)) {
        AstType *t = expr->type;
        if (t->kind == AST_TYPE_POINTER && astTypeIsString(t->ptr)) {
            expr = astUnaryOperator(t->ptr, AST_UN_OP_DEREF, expr);
            t = expr->type;
        }
        if (astTypeIsString(t)) {
            Ast *len, *ptr;
            parseSliceParts(cc, expr, &len, &ptr, 1);
            return astSliceMake(parseHiddenSliceVar(cc, want), ptr, len);
        }
    }
    if (!astTypeIsSlice(want) || !expr || !expr->type ||
        expr->type->kind != AST_TYPE_ARRAY || expr->type->len < 0) {
        return expr;
    }
    /* a string literal: its bytes, not its NUL, where they are (no copy) */
    if (expr->kind == AST_STRING && want->elem->kind == AST_TYPE_CHAR &&
        want->elem->size == 1) {
        return astSliceMake(parseHiddenSliceVar(cc, want), expr,
                            astI64Type(expr->real_len - 1));
    }
    AoStr *want_elem = astTypeToAoStr(want->elem);
    AoStr *got_elem = astTypeToAoStr(expr->type->ptr);
    int same = aoStrCmp(want_elem, got_elem);
    aoStrRelease(want_elem);
    aoStrRelease(got_elem);
    if (!same) return expr;

    return astSliceMake(parseHiddenSliceVar(cc, want), expr,
                        astI64Type(expr->type->len));
}

/* The hidden variable a made slice is written into: a local, or a static
 * in file-scope code */
Ast *parseHiddenSliceVar(Cctrl *cc, AstType *type) {
    static int slice_id = 0;
    AoStr *name = aoStrPrintf("#slice%d", slice_id++);
    Ast *var;
    if (cc->tmp_fname) {
        var = astLVar(type, name->data, name->len);
        if (cc->tmp_locals) listAppend(cc->tmp_locals, var);
    } else {
        var = astGVar(type, name->data, name->len, 1);
        listAppend(cc->ast_list, astDecl(var, NULL));
    }
    return var;
}

/* A String `expr` as a C string: its ptr, which is NUL-terminated, or ""
 * for an empty String (whose ptr is null), as s.cstr() gives. `expr` is
 * evaluated once: a variable or a field of one is read as is, anything
 * else is assigned to a hidden variable first. */
Ast *parseStringCPtr(Cctrl *cc, Ast *expr) {
    AstType *ptr_t = mapGetLen(expr->type->fields, str_lit("ptr"));
    AstType *u8_ptr = astMakePointerType(ast_u8_type);
    Ast *root = expr;
    while (root->kind == AST_CLASS_REF) root = root->cls;
    if (astIsUnOpKind(root, AST_UN_OP_DEREF) && root->operand->kind == AST_LVAR) {
        root = root->operand;
    }
    Ast *first, *again;
    if (root->kind == AST_LVAR || root->kind == AST_GVAR) {
        first = astClassRef(ptr_t, expr, "ptr");
        again = astClassRef(ptr_t, expr, "ptr");
    } else {
        Ast *var = parseHiddenSliceVar(cc, expr->type);
        int is_err = 0;
        Ast *assign = astBinaryOp(AST_BIN_OP_ASSIGN, var, expr, &is_err);
        first = astClassRef(ptr_t, assign, "ptr");
        again = astClassRef(ptr_t, var, "ptr");
    }
    Ast *empty = astCast(cctrlGetOrSetString(cc, "", 0, 0), u8_ptr);
    return astTernary(u8_ptr, first, astCast(again, u8_ptr), empty);
}

/* Words that are keywords though the lexer gives them as identifiers
 * (they mean something only where the parser looks for them): none of
 * them can name a variable, parameter or declaration */
int parseIsReservedName(char *s, int len) {
    static const char *words[] = {"fn", "self", "module", "import", "fallthrough"};
    for (u64 i = 0; i < sizeof(words) / sizeof(words[0]); ++i) {
        if ((int)strlen(words[i]) == len && !memcmp(words[i], s, len)) return 1;
    }
    return 0;
}

/* `tok`, about to name something, isn't one of those keywords: 1 (and
 * reported) when it is */
int parseCheckNotReserved(Cctrl *cc, Lexeme *tok) {
    if (tok && tok->tk_type == TK_IDENT && parseIsReservedName(tok->start, tok->len)) {
        cctrlErrorAt(cc, tok->line, tok->col, tok->len,
                     "`%.*s` is a keyword: it can't be a name", tok->len, tok->start);
        return 1;
    }
    return 0;
}

/* `fn`, a keyword the lexer gives as an identifier: is `tok` it? */
int tokenIsFnIdent(Lexeme *tok) {
    return tok && tok->tk_type == TK_IDENT && tok->len == 2 &&
           tok->start[0] == 'f' && tok->start[1] == 'n';
}

/* Can `tok` start an fn type: `fn`, or the `(` of the parenthesised
 * `(fn(...) R)` (to which a `*` after it applies)? */
int tokenMayStartFnType(Lexeme *tok) {
    return tokenIsFnIdent(tok) || tokenPunctIs(tok,'(');
}

/* Does the current, unconsumed token start an fn type: `fn`, or the `(`
 * of `(fn(...) R)`? `fn` is a keyword, so it always does. Never
 * consumes. */
int parseFnStartsType(Cctrl *cc) {
    Lexeme *tok = cctrlTokenPeek(cc);
    if (tokenIsFnIdent(tok)) return 1;
    if (!tokenPunctIs(tok, '(')) return 0;
    cctrlTokenGet(cc);
    int result = tokenIsFnIdent(cctrlTokenPeek(cc));
    cctrlTokenRewind(cc);
    return result;
}

/* At a declaration/expression-statement fork, `fn` goes to the
 * declaration side (a malformed one gets parseBaseDeclSpec's error) */
int parseFnLooksLikeType(Cctrl *cc) {
    return parseFnStartsType(cc);
}

static int parseIsVarArgElemType(AstType *t);
AstType *parseReflectValueType(Cctrl *cc);
Ast *parseReflectTypeAddr(Cctrl *cc, AstType *t);
Ast *parseDefaultFunctionParam(Cctrl *cc, Ast *var);

/* The two hidden parameters of `T ...name` (elem NULL: `...name`, 64-bit
 * slots): the count `name#len` and `name`, a pointer to the caller's
 * array of the extras, flagged AST_FLAG_VARARGS. */
static void parseVarArgsPair(Vec *params, AstType *elem, char *name, int len,
                             Ast **_count, Ast **_data)
{
    if (!elem) {
        elem = astTypeCopy(ast_uint_type);
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
    Lexeme *star = cctrlTokenPeek(cc);
    s64 star_line = star ? star->line : 0, star_col = star ? star->col : 0;
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
    fn_type->has_var_args = has_var_args;
    /* the old C declarator `T (*name)(params)` (a nameless one has had
     * its error: one per declarator) */
    if (*fnptr_name_len > 0) {
        cctrlErrorAt(cc, star_line, star_col - 1, 2,
            "a C function-pointer declarator: write `%s %.*s`",
            astTypeToString(type), *fnptr_name_len, *fnptr_name);
    }
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
    /* `u8[] chars = " \t"`: the default is evaluated at each call, in the
     * caller, so its slice goes into a hidden static, not a local */
    if (init && astTypeIsSlice(var->type) && init->type &&
        init->type->kind == AST_TYPE_ARRAY) {
        AstType *want = var->type;
        Ast *converted = parseSliceFrom(cc, want, init);
        if (converted != init && converted->kind == AST_SLICE_MAKE &&
            converted->slice_var->kind == AST_LVAR) {
            static int default_id = 0;
            AoStr *name = aoStrPrintf("#slicedefault%d", default_id++);
            Ast *gvar = astGVar(want, name->data, name->len, 1);
            listAppend(cc->ast_list, astDecl(gvar, NULL));
            /* the local parseSliceFrom made isn't one: drop it */
            if (cc->tmp_locals && cc->tmp_locals->prev->value == converted->slice_var) {
                List *last = cc->tmp_locals->prev;
                last->prev->next = cc->tmp_locals;
                cc->tmp_locals->prev = last->prev;
            }
            converted->slice_var = gvar;
        }
        init = converted;
    }
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
                              Lexeme *ellipsis, s64 terminator, int store,
                              int any)
{
    int line = ellipsis->line, col = ellipsis->col;
    Lexeme *name = cctrlTokenGet(cc);
    if (!name || name->tk_type != TK_IDENT) {
        cctrlRaiseExceptionAt(cc, line, col, 3,
            "a variadic parameter needs a name, as in `i64 ...nums` or `...args`");
    }
    if (elem && !any && !parseIsVarArgElemType(elem)) {
        cctrlRaiseExceptionAt(cc, name->line, name->col, name->len,
            "the elements of `...%.*s` must be numbers or pointers (or `any ...%.*s`), got %s",
            name->len, name->start,
            name->len, name->start, astTypeToString(elem));
    }
    Ast *count, *data;
    parseVarArgsPair(params, elem, name->start, name->len, &count, &data);
    if (any) data->flags |= AST_FLAG_VARARGS_ANY;
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

/* `@caller("file")`, `@caller("line")` or `@caller("func")` before a
 * parameter, the `@` next: its AST_FLAG_CALLER_* bit. The type is checked
 * once the parameter is parsed (parseCheckCallerParam). */
static u64 parseCallerAttr(Cctrl *cc, Lexeme **at_tok) {
    Lexeme *at = cctrlTokenGet(cc);
    *at_tok = at;
    Lexeme *name = cctrlTokenGet(cc);
    if (!name || name->tk_type != TK_IDENT || name->len != 6 ||
        strncmp(name->start, "caller", 6))
    {
        cctrlRaiseExceptionAt(cc, at->line, at->col, 1,
            "a parameter takes one attribute, @caller(\"file\"), "
            "@caller(\"line\") or @caller(\"func\")");
    }
    Lexeme *open = cctrlTokenGet(cc);
    Lexeme *arg = open && tokenPunctIs(open, '(') ? cctrlTokenGet(cc) : NULL;
    Lexeme *close = arg ? cctrlTokenGet(cc) : NULL;
    u64 flag = 0;
    if (arg && arg->tk_type == TK_STR && close && tokenPunctIs(close, ')')) {
        if (arg->len == 4 && !strncmp(arg->start, "file", 4)) flag = AST_FLAG_CALLER_FILE;
        else if (arg->len == 4 && !strncmp(arg->start, "line", 4)) flag = AST_FLAG_CALLER_LINE;
        else if (arg->len == 4 && !strncmp(arg->start, "func", 4)) flag = AST_FLAG_CALLER_FUNC;
    }
    if (!flag) {
        cctrlRaiseExceptionAt(cc, at->line, at->col, 7,
            "`@caller` takes \"file\", \"line\" or \"func\": @caller(\"line\") i64 line");
    }
    return flag;
}

/* A @caller parameter: "file" and "func" are a u8 *, "line" an integer,
 * and none has a default (the call always fills it) */
static void parseCheckCallerParam(Cctrl *cc, Ast *var, u64 flag, Lexeme *at) {
    if (var->kind != AST_LVAR) {
        cctrlRaiseExceptionAt(cc, at->line, at->col, 7,
            "a `@caller` parameter has no default: every call fills it in");
    }
    AstType *t = var->type;
    int ok = flag == AST_FLAG_CALLER_LINE
           ? astIsIntType(t)
           : t->kind == AST_TYPE_POINTER && t->ptr && t->ptr->kind == AST_TYPE_CHAR &&
             t->ptr->size == 1;
    if (!ok) {
        cctrlRaiseExceptionAt(cc, at->line, at->col, 7,
            "`@caller(\"%s\")` goes on a%s parameter, not a %s",
            flag == AST_FLAG_CALLER_FILE ? "file" : flag == AST_FLAG_CALLER_LINE ? "line" : "func",
            flag == AST_FLAG_CALLER_LINE ? "n integer" : " u8 *", astTypeToString(t));
    }
    var->flags |= flag;
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
                parseNamedVarArgs(cc, params, NULL, &ellipsis, terminator, store, 0);
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

        u64 caller_flag = 0;
        Lexeme *caller_at = NULL;
        if (tokenPunctIs(pname, '@')) {
            caller_flag = parseCallerAttr(cc, &caller_at);
        }

        int param_is_const = 0;
        Lexeme *const_tok = cctrlTokenPeek(cc);
        if (const_tok && const_tok->tk_type == TK_KEYWORD &&
            const_tok->i64 == KW_CONST)
        {
            cctrlTokenGet(cc);
            param_is_const = 1;
        }
        /* `any ...name`: extras of any type, each a reflect.Value */
        Lexeme *any_tok = cctrlTokenPeek(cc);
        if (!param_is_const && !caller_flag && any_tok && any_tok->tk_type == TK_IDENT &&
            any_tok->len == 3 && !memcmp(any_tok->start, "any", 3)) {
            cctrlTokenGet(cc);
            Lexeme *after = cctrlTokenPeek(cc);
            if (tokenPunctIs(after, TK_ELLIPSIS)) {
                Lexeme ellipsis = *cctrlTokenGet(cc);
                AstType *value = parseReflectValueType(cc);
                if (!value) {
                    cctrlRaiseExceptionAt(cc, any_tok->line, any_tok->col, 3,
                        "`any ...` needs `import reflect;`: each extra is a reflect.Value");
                }
                parseNamedVarArgs(cc, params, value, &ellipsis, terminator, store, 1);
                return params;
            }
            cctrlTokenRewind(cc);
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
        if (caller_flag && (tokenPunctIs(pname, TK_ELLIPSIS) || tokenPunctIs(pname, '('))) {
            cctrlRaiseExceptionAt(cc, caller_at->line, caller_at->col, 7,
                "`@caller` goes on a u8 * or integer parameter, not a %s",
                tokenPunctIs(pname, '(') ? "function pointer" : "variadic one");
        }
        if (tokenPunctIs(pname, TK_ELLIPSIS)) {
            Lexeme ellipsis = *pname;
            parseNamedVarArgs(cc, params, type, &ellipsis, terminator, store, 0);
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
                if (caller_flag) {
                    parseCheckCallerParam(cc, var, caller_flag, caller_at);
                }
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
        /* (a keyword as the name: reported, and the parameter nameless,
         * so a method's `self` isn't "declared twice" as well) */
        var = parseCheckNotReserved(cc, pname)
            ? astLVar(type,"_unknown",7)
            : astLVar(type,pname->start,pname->len);
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

        if (caller_flag) {
            parseCheckCallerParam(cc, var, caller_flag, caller_at);
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
static AstType *parseBaseDeclSpecName(Cctrl *cc);

/* A declaration's base type: its name, and the `[]`s right after it,
 * which are every declarator's (`u8[] a, b` are two slices); a `*` is
 * one declarator's, as in C */
AstType *parseBaseDeclSpec(Cctrl *cc) {
    AstType *type = parseBaseDeclSpecName(cc);
    while (type && tokenPunctIs(cctrlTokenPeek(cc), '[') &&
           tokenPunctIs(cctrlTokenPeekBy(cc, 1), ']')) {
        cctrlTokenGet(cc);
        cctrlTokenGet(cc);
        type = astMakeSliceType(type);
    }
    return type;
}

static AstType *parseBaseDeclSpecName(Cctrl *cc) {
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
        /* a generic struct: `Vec<i64>` names an instance */
        if (type->generic && !type->generic_args) {
            if (!tokenPunctIs(cctrlTokenPeek(cc), '<')) {
                cctrlRaiseException(cc, "`%s` is generic: write its type arguments, as in `%s<i64>`",
                                    type->clsname->data, type->clsname->data);
            }
            Vec *args = parseGenericArgs(cc);
            type = parseGenericStructInstance(cc, type, args, tok);
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

        if (!ok) {
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
    /* and its global function pointers (`op(x)` of `fn(i64) i64 op`),
     * unless a local of that name hides it */
    if (qual && (decl = mapGet(cc->global_env, qual)) != NULL &&
        decl->kind == AST_GVAR && decl->type->kind == AST_TYPE_FUNC &&
        !(cc->localenv && mapGetLen(cc->localenv, fname, len)))
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
                    "cannot convert %s to %s%s", msg_actual, msg_expected,
                    parseFnConvertHint(type, arg->type));
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
    /* @caller parameters are filled in, not written: the messages count
     * the arguments as written */
    int hidden = 0;
    for (int i = 0; i < param_count; ++i) {
        Ast *param = params->entries[i];
        if (param && (param->flags & AST_FLAG_CALLER)) hidden++;
    }

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
                        written - hidden, len, fname, i + 1 - hidden);
                }
                AstType *type = param->kind == AST_DEFAULT_PARAM ? param->declvar->type : param->type;
                if (type && arg && arg->kind != AST_PLACEHOLDER) {
                    parseResolveOverloadRef(cc, type, arg);
                    argv->entries[i] = arg = parseSliceFrom(cc, type, arg);
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
                        arg_count - hidden, len, fname, i + 1 - hidden);
            }

            parseRaiseCallError(cc, loc,
                    "Unexpected number of arguments %d in call to %.*s(), expected %d args",
                    arg_count - hidden, len, fname, fixed_param_count - hidden);
        }

        AstType *type = param->kind == AST_DEFAULT_PARAM ? param->declvar->type : param->type;
        if (type) {
            parseResolveOverloadRef(cc, type, arg);
            argv->entries[i] = arg = parseSliceFrom(cc, type, arg);
            parseCheckArgType(cc,type,arg,i,fname,len,loc);
        }
    }

    /* vararg calls allow extras, but still require fixed args */
    if (fixed_param_count != param_count) {
        if (arg_count < required_count) {
            parseRaiseCallError(cc, loc,
                    "Unexpected number of arguments %d in call to %.*s(), expected at least %d args",
                    arg_count - hidden, len, fname, required_count - hidden);
        }
        return;
    }

    /* non-vararg calls cannot use more than the fixed parameter count */
    if (arg_count > fixed_param_count) {
        if (default_count) {
            parseRaiseCallError(cc, loc,
                    "Unexpected number of arguments %d in call to %.*s(), expected args in range %d to %d",
                    arg_count - hidden, len, fname, required_count - hidden, fixed_param_count - hidden);
        }

        parseRaiseCallError(cc, loc,
                "Unexpected number of arguments %d in call to %.*s(), expected %d args",
                arg_count - hidden, len, fname, fixed_param_count - hidden);
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
static void parseSliceLenPtr(Cctrl *cc, Ast *expr, Ast **_len, Ast **_ptr);

/* The extras of an `any ...name` call: each copied into a hidden variable
 * (an array as a slice of it), and the array holds a reflect.Value per
 * extra, its two words (the copy's address, the reflect.Type) stored as
 * 64-bit slots. */
static Ast *parseVarArgsAny(Cctrl *cc, Vec *items, Vec *item_spans,
                            int first_arg, char *fname, int len)
{
    static int va_any_id = 0;
    Vec *converted = astVecNew();
    for (u64 i = 0; i < items->size; ++i) {
        Ast *arg = vecGet(Ast *, items, i);
        ParseCallLoc *at = vecGet(ParseCallLoc *, item_spans, i);
        AstType *t = arg->type;
        if (t && t->kind == AST_TYPE_ARRAY) {
            arg = parseSliceFrom(cc, astMakeSliceType(t->ptr), arg);
            t = arg->type;
        }
        if (!t || t->kind == AST_TYPE_VOID) {
            cctrlRaiseExceptionAt(cc, at->line, at->col, at->len,
                "argument %d of `%.*s` has no value",
                first_arg + (int)i + 1, len, fname);
        }
        if (astTypeIsString(t) && (arg->kind == AST_FUNCALL || arg->kind == AST_FUNPTR_CALL ||
                                   arg->kind == AST_ASM_FUNCALL)) {
            cctrlRaiseExceptionAt(cc, at->line, at->col, at->len,
                "argument %d of `%.*s`: a String from a call would never be freed; "
                "put it in a variable first",
                first_arg + (int)i + 1, len, fname);
        }
        Ast *copy = parseHiddenSliceVar(cc, t);
        AstType *u64_t = ast_uint_type;
        vecPush(converted, astCast(astTempAddr(copy, arg), u64_t));
        vecPush(converted, astCast(parseReflectTypeAddr(cc, t), u64_t));
    }
    AoStr *arr_name = aoStrPrintf("#vany%d", va_any_id++);
    AstType *arr_type = astMakeArrayType(ast_uint_type, (int)converted->size);
    Ast *array;
    if (cc->tmp_fname) {
        array = astLVar(arr_type, arr_name->data, arr_name->len);
        if (cc->tmp_locals) listAppend(cc->tmp_locals, array);
    } else {
        array = astGVar(arr_type, arr_name->data, arr_name->len, 1);
        listAppend(cc->ast_list, astDecl(array, NULL));
    }
    return astVaPack(array, converted, 1);
}

static void parseVarArgsPack(Cctrl *cc, Vec *argv_vec, Vec *spans,
                             AstType *elem, Vec *items, Vec *item_spans,
                             Ast *spread, int spread_idx,
                             int first_arg, char *fname, int len, int any)
{
    static int va_array_id = 0;
    int untyped = elem->has_var_args;
    AstType *data_type = astMakePointerType(elem);
    Ast *count, *data;

    if (spread) {
        ParseCallLoc *at = vecGet(ParseCallLoc *, item_spans, spread_idx);
        if (items->size > 1) {
            cctrlRaiseExceptionAt(cc, at->line, at->col, at->len,
                "a spread `...` must be the only extra argument in a call to `%.*s`",
                len, fname);
        }
        /* the same element type (untyped extras are u64 slots) */
        AstType *from = spread->type->elem;
        int same = !strcmp(astTypeToString(from), astTypeToString(elem));
        if (!same) {
            cctrlRaiseExceptionAt(cc, at->line, at->col, at->len,
                "cannot spread a %s into `%.*s`, whose extra arguments are %s",
                astTypeToString(spread->type), len, fname,
                untyped ? "untyped (u64[])" : astTypeToString(elem));
        }
        /* the slice's count and pointer, evaluated once */
        parseSliceLenPtr(cc, spread, &count, &data);
    } else if (items->size == 0) {
        count = astI64Type(0);
        data = astCast(astI64Type(0), data_type);
    } else if (any) {
        data = parseVarArgsAny(cc, items, item_spans, first_arg, fname, len);
        count = astI64Type((s64)items->size);
    } else {
        Vec *converted = astVecNew();
        for (u64 i = 0; i < items->size; ++i) {
            Ast *arg = vecGet(Ast *, items, i);
            ParseCallLoc *at = vecGet(ParseCallLoc *, item_spans, i);
            AstType *at_type = arg->type;
            if (untyped) {
                if (at_type && astTypeIsString(at_type)) {
                    /* a String goes as its C string, as s.cstr() */
                    vecPush(converted, parseStringCPtr(cc, arg));
                    continue;
                }
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
                arg = parseSliceFrom(cc, elem, arg);
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

static int parseFormatArgIdx(char *fname, int len);

/* A byte slice (`u8[]`), which `%s` prints */
static int parseIsByteSlice(AstType *t) {
    return (astTypeIsSlice(t) && t->elem && t->elem->size == 1 &&
            (t->elem->kind == AST_TYPE_CHAR || t->elem->kind == AST_TYPE_INT)) ||
           astTypeIsString(t);
}

/* `expr`, a slice, read twice (its len, then its ptr) without being
 * evaluated twice: a variable or a field of one as is, anything else
 * (a call, a sub-slice) assigned to a hidden variable first. *_len reads
 * it (and does the assignment), *_ptr reads the variable after. */
static void parseSliceParts(Cctrl *cc, Ast *expr, Ast **_len, Ast **_ptr,
                            int ptr_first);
static void parseSliceLenPtr(Cctrl *cc, Ast *expr, Ast **_len, Ast **_ptr) {
    parseSliceParts(cc, expr, _len, _ptr, 0);
}

/* As parseSliceLenPtr, the hidden assignment (if any) in *_ptr when
 * ptr_first: the one read first */
static void parseSliceParts(Cctrl *cc, Ast *expr, Ast **_len, Ast **_ptr,
                            int ptr_first) {
    Map *fields = expr->type->fields;
    AstType *len_t = mapGetLen(fields, str_lit("len"));
    AstType *ptr_t = mapGetLen(fields, str_lit("ptr"));
    Ast *root = expr;
    while (root->kind == AST_CLASS_REF) root = root->cls;
    if (astIsUnOpKind(root, AST_UN_OP_DEREF) && root->operand->kind == AST_LVAR) {
        root = root->operand;
    }
    if (root->kind == AST_LVAR || root->kind == AST_GVAR) {
        *_len = astClassRef(len_t, expr, "len");
        *_ptr = astClassRef(ptr_t, expr, "ptr");
        return;
    }
    Ast *var = parseHiddenSliceVar(cc, expr->type);
    int is_err = 0;
    Ast *assign = astBinaryOp(AST_BIN_OP_ASSIGN, var, expr, &is_err);
    *_len = astClassRef(len_t, ptr_first ? var : assign, "len");
    *_ptr = astClassRef(ptr_t, ptr_first ? assign : var, "ptr");
}

/* A call to a format function (printf, str.format, throw, ...): each
 * `u8[]` argument of a `%s` is passed as its length and pointer, the
 * `%s` becoming `%.*s`, since a slice has no NUL to stop at. The extras
 * are args[first..]; spans, when set, runs alongside them. Only a literal
 * format can be rewritten: with another one a slice is an error, as is a
 * slice that no `%s` takes. */
static void parseLowerSliceFormat(Cctrl *cc, Ast **fmt_slot, Vec *args,
                                  int first, Vec *spans, char *fname, int len)
{
    int has_slice = 0;
    for (u64 i = first; i < args->size; ++i) {
        Ast *arg = args->entries[i];
        if (arg && arg->type && (astTypeIsSlice(arg->type) || astTypeIsString(arg->type))) {
            has_slice = 1;
        }
    }
    if (!has_slice) return;
    Ast *fmt = *fmt_slot;
    if (!fmt || fmt->kind != AST_STRING || !fmt->sval) {
        cctrlRaiseException(cc, "a slice or a String passed to `%.*s` needs a literal "
                            "format, where its `%%s` takes its length too", len, fname);
    }
    char *text = fmt->sval->data;
    int n = (int)fmt->sval->len, argi = first, added = 0;
    AoStr *out = aoStrNew();
    for (int i = 0; i < n; ++i) {
        if (text[i] != '%') { aoStrPutChar(out, text[i]); continue; }
        aoStrPutChar(out, '%');
        if (++i >= n) break;
        if (text[i] == '%') { aoStrPutChar(out, '%'); continue; }
        while (i < n && strchr("-+ #0'", text[i])) aoStrPutChar(out, text[i++]);
        if (i < n && text[i] == '*') { aoStrPutChar(out, text[i++]); argi++; }
        while (i < n && isdigit((unsigned char)text[i])) aoStrPutChar(out, text[i++]);
        int has_prec = 0;
        if (i < n && text[i] == '.') {
            has_prec = 1;
            aoStrPutChar(out, text[i++]);
            if (i < n && text[i] == '*') { aoStrPutChar(out, text[i++]); argi++; }
            while (i < n && isdigit((unsigned char)text[i])) aoStrPutChar(out, text[i++]);
        }
        while (i < n && strchr("hlLqjzt", text[i])) aoStrPutChar(out, text[i++]);
        if (i >= n) break;
        char conv = text[i];
        Ast *arg = argi < (int)args->size ? args->entries[argi] : NULL;
        if (conv == 's' && arg && arg->type && parseIsByteSlice(arg->type)) {
            if (has_prec) {
                cctrlRaiseException(cc, "`%%s` of a slice in `%.*s` can't have a "
                                    "precision: it is the slice's length", len, fname);
            }
            aoStrCatLen(out, str_lit(".*s"));
            Ast *len_arg, *ptr_arg;
            parseSliceLenPtr(cc, arg, &len_arg, &ptr_arg);
            args->entries[argi] = len_arg;
            vecInsertAt(args, ptr_arg, argi + 1);
            /* the spans own their entries: the pointer's has none */
            if (spans && spans->size > (u64)argi) vecInsertAt(spans, NULL, argi + 1);
            argi += 2;
            added += 2;
            continue;
        }
        aoStrPutChar(out, conv);
        argi++;
    }
    for (u64 i = first; i < args->size; ++i) {
        Ast *arg = args->entries[i];
        if (arg && arg->type && (astTypeIsSlice(arg->type) || astTypeIsString(arg->type))) {
            cctrlRaiseException(cc, "a %s passed to `%.*s` is printed by a `%%s` "
                                "(a u8[] or a String only)", astTypeToString(arg->type), len, fname);
        }
    }
    *fmt_slot = cctrlGetOrSetString(cc, out->data, out->len, fmt->real_len + added);
    cc->fmt_lowered = added / 2;
}

/* The argument a @caller parameter gets at a call on `line`: the file
 * the call is in, its line, or the function it is in ("" at the top
 * level) */
static Ast *parseCallerArg(Cctrl *cc, Ast *param, int line) {
    if (param->flags & AST_FLAG_CALLER_LINE) return astI64Type(line);
    const char *text = "";
    if (param->flags & AST_FLAG_CALLER_FILE) {
        LexFile *file = cc->lexer_ ? cctrlCurFile(cc) : NULL;
        if (file && file->filename) text = file->filename->data;
    } else if (cc->tmp_func && cc->tmp_func->fname) {
        text = astSymbolDisplayName(cc->tmp_func->fname->data);
        if (!strcmp(text, "Main")) text = "main";
    }
    int n = (int)strlen(text);
    return cctrlGetOrSetString(cc, (char *)text, n, n);
}

/* Push the arguments of the @caller parameters from params[idx] on;
 * returns the index of the first other parameter */
static int parseFillCallerArgs(Cctrl *cc, Vec *params, int idx, Vec *argv,
                               Vec *spans, int line) {
    while (params && idx < (int)params->size) {
        Ast *param = params->entries[idx];
        if (!param || !(param->flags & AST_FLAG_CALLER)) break;
        vecPush(argv, parseCallerArg(cc, param, line));
        if (spans) vecPush(spans, NULL);
        idx++;
    }
    return idx;
}

/* An overloaded callee's @caller parameters, which every overload starts
 * with (parseOverloadDecl): set just before parseArgv, which fills them in
 * as for a single function. A method called as `Type.name(obj, ...)` has
 * its self first. */
static Vec *parse_overload_callers;

static void parseSetOverloadCallers(Vec *overloads, int is_method, int with_self) {
    Ast *fn = overloads->entries[0];
    Vec *params = fn ? fn->params : NULL;
    Vec *out = NULL;
    int from = is_method ? 1 : 0;
    for (int i = from; params && i < (int)params->size; ++i) {
        Ast *p = params->entries[i];
        if (!p || !(p->flags & AST_FLAG_CALLER)) break;
        if (!out) {
            out = astVecNew();
            if (with_self) vecPush(out, params->entries[0]);
        }
        vecPush(out, p);
    }
    parse_overload_callers = out;
}

Vec *parseArgv(Cctrl *cc, Ast *decl, s64 terminator, char *fname, int len,
               Vec *spans) {
    List *var_args = NULL;
    Ast *ast, *param = NULL;
    Lexeme *tok;
    Vec *params = NULL;
    int param_idx = 0;

    if (decl) params = parseGetFunctionParams(decl);
    else params = parse_overload_callers;
    parse_overload_callers = NULL;

    /* A `T ...name` callee: its last two parameters are the count and the
     * data pointer, every argument from the count's position on is an
     * extra (parseVarArgsPack) */
    int va_at = -1, va_any = 0;
    AstType *va_elem = NULL;
    Vec *va_items = NULL, *va_spans = NULL;
    Ast *va_spread = NULL;
    int va_spread_idx = -1;
    if (params && params->size >= 2) {
        Ast *last = params->entries[params->size - 1];
        if (last && last->kind == AST_LVAR && (last->flags & AST_FLAG_VARARGS)) {
            va_at = (int)params->size - 2;
            va_elem = last->type->ptr;
            va_any = (last->flags & AST_FLAG_VARARGS_ANY) != 0;
            va_items = astVecNew();
            va_spans = vecNew(&vec_unsigned_long_type);
        }
    }

    Vec *argv_vec = astVecNew();
    int saw_args = 0;
    int call_line = cc->lineno;

    tok = cctrlTokenPeek(cc);

    while (tok && !tokenPunctIs(tok, terminator)) {
        saw_args = 1;
        param_idx = parseFillCallerArgs(cc, params, param_idx, argv_vec, spans, call_line);
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
                /* any slice (an array: its slice) of the extras' type */
                extra = parseSliceFrom(cc, astMakeSliceType(va_elem), extra);
                if (!extra->type || !astTypeIsSlice(extra->type)) {
                    cctrlRaiseExceptionAt(cc, span->line, span->col, span->len,
                        "only a slice can be spread, as in `...nums` or `...s[1:]`");
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
        if (tokenPunctIs(tok, TK_ELLIPSIS) && !decl &&
            astOverloads(findFunctionDecl(cc, fname, len))) {
            cctrlRaiseException(cc,
                "`...` can't spread into `%.*s`, which is overloaded: pass the "
                "items, or call the overload through a `fn` pointer", len, fname);
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

    /* @caller parameters after the last argument written: the defaults
     * between are left to parseFlattenDefaultArgs */
    if (params && param_idx == (int)argv_vec->size) {
        int last_caller = -1;
        for (int i = param_idx; i < (int)params->size; ++i) {
            Ast *p = params->entries[i];
            if (!p || p->kind == AST_VAR_ARGS || (p->flags & AST_FLAG_VARARGS)) break;
            if (p->flags & AST_FLAG_CALLER) last_caller = i;
        }
        for (int i = param_idx; i <= last_caller; ++i) {
            Ast *p = params->entries[i];
            if (p->flags & AST_FLAG_CALLER) {
                vecPush(argv_vec, parseCallerArg(cc, p, call_line));
            } else {
                vecPush(argv_vec, astMakePlaceHolder());
            }
            if (spans) vecPush(spans, NULL);
        }
    }

    /* a `u8[]` printed by `%s`: its length and pointer (`%.*s`) */
    int fmt_idx = parseFormatArgIdx(fname, len);
    if (va_at >= 0) {
        while ((int)argv_vec->size < va_at) {
            vecPush(argv_vec, astMakePlaceHolder());
            if (spans) vecPush(spans, NULL);
        }
        /* a function of the program's `u8 *fmt, ...args`: printf's format
         * (a spread `...slots` passes its slots on as they are) */
        int own_fmt = 0;
        if (fmt_idx < 0 && va_elem->has_var_args && va_at >= 1 && !va_spread) {
            Ast *p = params->entries[va_at - 1];
            if (p && p->kind == AST_LVAR && p->type && p->type->kind == AST_TYPE_POINTER &&
                p->type->ptr && p->type->ptr->size == 1 &&
                (p->type->ptr->kind == AST_TYPE_CHAR || p->type->ptr->kind == AST_TYPE_INT) &&
                p->lname && p->lname->len == 3 && !memcmp(p->lname->data, "fmt", 3)) {
                fmt_idx = va_at - 1;
                own_fmt = 1;
            }
        }
        if (fmt_idx >= 0 && fmt_idx < (int)argv_vec->size) {
            Ast *fmt = argv_vec->entries[fmt_idx];
            /* with a computed format a String still goes, as its C string */
            int literal = fmt && fmt->kind == AST_STRING && fmt->sval;
            int only_strings = 1;
            for (u64 i = 0; i < va_items->size; ++i) {
                Ast *a = va_items->entries[i];
                if (a && a->type && astTypeIsSlice(a->type)) only_strings = 0;
            }
            if (literal || !only_strings || !own_fmt) {
                parseLowerSliceFormat(cc, (Ast **)&argv_vec->entries[fmt_idx], va_items,
                                      0, va_spans, fname, len);
            }
        }
        parseVarArgsPack(cc, argv_vec, spans, va_elem, va_items, va_spans,
                         va_spread, va_spread_idx, va_at, fname, len, va_any);
        /* With no argument at all the `)` is still there */
        if (!saw_args) {
            cctrlTokenGet(cc);
        }
        return argv_vec;
    }

    if (fmt_idx >= 0 && fmt_idx < (int)argv_vec->size) {
        parseLowerSliceFormat(cc, (Ast **)&argv_vec->entries[fmt_idx], argv_vec,
                              fmt_idx + 1, spans, fname, len);
    }

    if (var_args) {
        /* set the argument count and merge lists */
        vecPush(argv_vec,astI64Type(listCount(var_args)));
        listForEach(var_args) {
            vecPush(argv_vec,(Ast*)it->value);
        }
    }

    /* With no argument written the `)` is still there (the @caller
     * arguments may have been filled in) */
    if (!saw_args || vecEmpty(argv_vec)) {
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
           (len == 8 && !strncmp(fname, str_lit("snprintf"))) ||
           (len == 7 && !strncmp(fname, str_lit("fprintf"))) ||
           (len == 11 && !strncmp(fname, str_lit("libc.printf"))) ||
           (len == 13 && !strncmp(fname, str_lit("libc.snprintf"))) ||
           (len == 12 && !strncmp(fname, str_lit("libc.fprintf")));
}

/* log.info(...) and the others, the default logger's or a Logger's */
static int parseIsLogCall(char *fname, int len) {
    static const char *names[] = {
        "log.debug", "log.info", "log.warn", "log.error",
        "log.Logger.debug", "log.Logger.info", "log.Logger.warn", "log.Logger.error",
    };
    for (u64 i = 0; i < sizeof(names) / sizeof(names[0]); ++i) {
        if ((int)strlen(names[i]) == len && !strncmp(fname, names[i], len)) return 1;
    }
    return 0;
}

/* Position of the format string within a known printf-style call's argument
 * list. -1 means this isn't one of the format-checked functions. Single
 * source of truth shared by the pre-parse position snapshot and the
 * post-parse arg-count check. */
static int parseFormatArgIdx(char *fname, int len) {
    /* Feels like we may want to occasionally add to this list? */
    if      (len == 6 && !strncmp(fname, str_lit("printf"))) return 0;
    else if (len == 7 && !strncmp(fname, str_lit("sprintf"))) return 1;
    else if (len == 8 && !strncmp(fname, str_lit("snprintf"))) return 2;
    else if (len == 7 && !strncmp(fname, str_lit("fprintf"))) return 1;
    else if (len == 11 && !strncmp(fname, str_lit("libc.printf"))) return 0;
    else if (len == 13 && !strncmp(fname, str_lit("libc.snprintf"))) return 2;
    else if (len == 12 && !strncmp(fname, str_lit("libc.fprintf"))) return 1;
    /* May as well type-check ktm's library too which is even more
     * prone to erroring. */
    else if (len == 10 && !strncmp(fname, str_lit("str.format"))) return 0;
    else if (len == 11 && !strncmp(fname, str_lit("cstr.format"))) return 0;
    else if (len == 14 && !strncmp(fname, str_lit("rt.__ktmPrintf"))) return 0;
    else if (len == 13 && !strncmp(fname, str_lit("cstr.formatTo"))) return 1;
    else if (len == 17 && !strncmp(fname, str_lit("cstr.appendFormat"))) return 1;
    else if (len == 20 && !strncmp(fname, str_lit("cstr.appendFormatLen"))) return 2;
    else if (len == 17 && !strncmp(fname, str_lit("cstr.formatToSize"))) return 2;
    /* s.appendFormat(fmt, ...) of a String (its arguments without self) */
    else if (len == 19 && !strncmp(fname, str_lit("String.appendFormat"))) return 0;
    /* the shell commands built from a format */
    else if (len == 11 && !strncmp(fname, str_lit("proc.shellf"))) return 0;
    else if (len == 16 && !strncmp(fname, str_lit("proc.shellOutput"))) return 0;
    /* printf to stderr */
    else if (len == 10 && !strncmp(fname, str_lit("os.eprintf"))) return 0;
    /* log's calls: after the @caller file, line and function */
    else if (parseIsLogCall(fname, len)) return 3;
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
    /* as written: without the lengths of the slices `%s` prints */
    int lowered = cc->fmt_lowered;
    cc->fmt_lowered = 0;
    expected -= lowered;
    supplied -= lowered;
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

    /* rt.__ktmThrowFmt's `...args` pads the call to its fixed parameters
     * and the (count, data) pair: `throw(code)` and `throw()` come back
     * with placeholders after what was written */
    for (u64 i = 0; i < argv->size; ++i) {
        Ast *arg = argv->entries[i];
        if (!arg || arg->kind == AST_PLACEHOLDER) {
            if (i <= 1) argv->size = i;
            break;
        }
    }
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
/* ---- overloads (AST_FLAG_OVERLOAD): picking one for a call ---- */

/* How well an argument fits a parameter type: not at all, int to float,
 * a conversion in the same family (another int width, T * to void *, a
 * String to u8[], an array to its pointer), or exactly */
enum { OVL_NONE = 0, OVL_INT_FLOAT = 1, OVL_CONVERT = 2, OVL_EXACT = 3 };

static AstType *parseParamType(Ast *param) {
    if (!param) return NULL;
    if (param->kind == AST_DEFAULT_PARAM && param->declvar) return param->declvar->type;
    return param->type;
}

/* The overload of `&f`'s set whose type is `want`; NULL if none */
static Ast *parseOverloadRefPick(Ast *ref, AstType *want) {
    if (!ref || !want) return NULL;
    Ast *fn = ref->kind == AST_UNOP ? ref->operand : ref;
    if (want->kind == AST_TYPE_POINTER && want->ptr && want->ptr->kind == AST_TYPE_FUNC) {
        want = want->ptr;
    }
    if (!fn || !fn->overloads || want->kind != AST_TYPE_FUNC) return NULL;
    for (u64 i = 0; i < fn->overloads->size; ++i) {
        Ast *o = fn->overloads->entries[i];
        if (astFnTypesMatch(want, o->type)) return o;
    }
    return NULL;
}

static int parseOverloadArgRank(AstType *p, Ast *arg) {
    if (!p || !arg || !arg->type) return OVL_CONVERT;
    if (arg->flags & AST_FLAG_OVERLOAD_REF) {
        return parseOverloadRefPick(arg, p) ? OVL_EXACT : OVL_NONE;
    }
    AstType *a = arg->type;
    if (astTypeIsSlice(p)) {
        if (a == p) return OVL_EXACT;
        if (astTypeIsSlice(a)) {
            return astFnTypesMatch(p->elem, a->elem) && p->elem->kind == a->elem->kind
                   ? OVL_EXACT : OVL_NONE;
        }
        if (a->kind == AST_TYPE_ARRAY && a->len >= 0 && a->ptr &&
            astFnTypesMatch(p->elem, a->ptr)) {
            return OVL_EXACT;
        }
        if (p->elem->size == 1 && (p->elem->kind == AST_TYPE_CHAR || p->elem->kind == AST_TYPE_INT) &&
            (astTypeIsString(a) || (a->kind == AST_TYPE_POINTER && astTypeIsString(a->ptr)))) {
            return OVL_CONVERT;
        }
        return OVL_NONE;
    }
    /* a String to a u8 *: a worse fit than to a u8[] */
    if (parseIsBytePtr(p) && astTypeIsString(a)) return OVL_INT_FLOAT;
    if (parseArgCompat(p, arg) != ARG_OK) return OVL_NONE;
    if (a->kind == AST_TYPE_ARRAY && p->kind == AST_TYPE_POINTER) return OVL_CONVERT;
    if (p->kind == AST_TYPE_CHAR && a->kind == AST_TYPE_CHAR && p->is_bool != a->is_bool) {
        return OVL_CONVERT;
    }
    if (astIsFloatType(p) != astIsFloatType(a)) {
        return astIsFloatType(p) ? OVL_INT_FLOAT : OVL_NONE;
    }
    if (p->kind == a->kind || (astIsIntType(p) && astIsIntType(a))) {
        if (astFnTypesMatch(p, a)) return OVL_EXACT;
    }
    return OVL_CONVERT;
}

typedef struct OvlCand {
    Ast *fn;     /* the overload */
    Ast *decl;   /* as the call sees it (a method without its self) */
    int *ranks;  /* per argument */
    int extra;   /* 1 when it needs a default or takes `T ...name` */
} OvlCand;

/* Does `decl` take `argv`? Fills c's ranks */
static int parseOverloadViable(Ast *decl, Vec *argv, OvlCand *c) {
    Vec *params = parseGetFunctionParams(decl);
    int n = params ? (int)params->size : 0, argc = argv ? (int)argv->size : 0;
    int fixed = n, va_at = -1, untyped = 0;
    AstType *va_elem = NULL;
    if (n >= 2) {
        Ast *last = params->entries[n - 1];
        if (last && last->kind == AST_LVAR && (last->flags & AST_FLAG_VARARGS)) {
            va_at = fixed = n - 2;
            va_elem = last->type->ptr;
        }
    }
    for (int i = 0; i < n && va_at < 0; ++i) {
        Ast *p = params->entries[i];
        if (p && p->kind == AST_VAR_ARGS) { fixed = i; untyped = 1; break; }
    }
    c->ranks = calloc(argc + 1, sizeof(int));
    c->extra = va_at >= 0 || untyped;
    for (int i = 0; i < fixed; ++i) {
        Ast *p = params->entries[i];
        Ast *arg = i < argc ? argv->entries[i] : NULL;
        if (!arg || arg->kind == AST_PLACEHOLDER) {
            int has_default = p->kind == AST_DEFAULT_PARAM ||
                              (p->kind == AST_FUNPTR && p->default_fn);
            if (!has_default) return 0;
            c->extra = 1;
            if (i < argc) c->ranks[i] = OVL_EXACT;
            continue;
        }
        if (!(c->ranks[i] = parseOverloadArgRank(parseParamType(p), arg))) return 0;
    }
    for (int i = fixed; i < argc; ++i) {
        Ast *arg = argv->entries[i];
        if (va_at >= 0) {
            if (!arg || arg->kind == AST_PLACEHOLDER) return 0;
            if (!(c->ranks[i] = parseOverloadArgRank(va_elem, arg))) return 0;
        } else if (untyped) {
            c->ranks[i] = OVL_CONVERT;
        } else {
            return 0;
        }
    }
    return 1;
}

/* a fits at least as well as b on every argument and better on one, or
 * as well everywhere without needing defaults or extras */
static int parseOverloadBetter(OvlCand *a, OvlCand *b, int argc) {
    int gt = 0;
    for (int i = 0; i < argc; ++i) {
        if (a->ranks[i] < b->ranks[i]) return 0;
        if (a->ranks[i] > b->ranks[i]) gt = 1;
    }
    return gt || a->extra < b->extra;
}

/* An overload as messages show it: `f(i64)`, `geo.area(geo.Rect *)` */
static char *parseOverloadName(Ast *fn) {
    return astModuleDemangle(fn->fname->data, fn->fname->len);
}

/* Its name alone: `f`, `geo.area` */
static char *parseOverloadBaseName(Ast *fn) {
    char *name = parseOverloadName(fn);
    char *paren = strchr(name, '(');
    if (paren) *paren = '\0';
    return name;
}

static char *parseOverloadArgTypes(Vec *argv) {
    AoStr *out = aoStrNew();
    aoStrPutChar(out, '(');
    for (u64 i = 0; argv && i < argv->size; ++i) {
        Ast *arg = argv->entries[i];
        if (i) aoStrCat(out, ", ");
        if (!arg || arg->kind == AST_PLACEHOLDER) aoStrCat(out, "<default>");
        else if (arg->flags & AST_FLAG_OVERLOAD_REF) aoStrCatPrintf(out, "&%s", parseOverloadName(arg->operand));
        else aoStrCat(out, astTypeToString(arg->type));
    }
    aoStrPutChar(out, ')');
    return aoStrMove(out);
}

static char *parseOverloadList(Vec *fns) {
    AoStr *out = aoStrNew();
    for (u64 i = 0; i < fns->size; ++i) {
        if (i) aoStrCat(out, ", ");
        aoStrCatPrintf(out, "`%s`", parseOverloadName(fns->entries[i]));
    }
    return aoStrMove(out);
}

/* The method `fn` as a call through an object sees it: without self */
static Ast *parseMethodWithoutSelf(Ast *fn) {
    Vec *params = astVecNew();
    for (u64 i = 1; i < fn->params->size; ++i) {
        vecPush(params, fn->params->entries[i]);
    }
    Ast *decl = (Ast *)malloc(sizeof(Ast));
    *decl = *fn;
    decl->params = params;
    decl->type = astTypeCopy(fn->type);
    decl->type->params = params;
    return decl;
}

/* The overload of `set` a call with `argv` (parsed without a target)
 * calls: the one that fits every argument at least as well as any other
 * (see OVL_*); an error when none fits or two fit as well. `no_self`: a
 * method called through an object (its self is not in argv). */
static int parse_overload_quiet = 0;

static Ast *parseResolveOverload(Cctrl *cc, Vec *set, Vec *argv, int no_self,
                                 char *fname, int len, ParseCallLoc *loc,
                                 Ast **decl_out) {
    int argc = argv ? (int)argv->size : 0;
    OvlCand *cands = calloc(set->size, sizeof(OvlCand));
    int nv = 0;
    for (u64 i = 0; i < set->size; ++i) {
        Ast *fn = set->entries[i];
        OvlCand c = {fn, no_self ? parseMethodWithoutSelf(fn) : fn, NULL, 0};
        if (parseOverloadViable(c.decl, argv, &c)) cands[nv++] = c;
    }
    char *shown = astModuleDemangle(fname, len);
    char *paren = strchr(shown, '(');
    if (paren) *paren = '\0';
    /* a generic of the same name is tried next (parseFunctionArguments) */
    if (!nv && parse_overload_quiet) return NULL;
    if (!nv) {
        parseRaiseCallError(cc, loc, "no overload of `%s` takes %s; there are %s",
                            shown, parseOverloadArgTypes(argv), parseOverloadList(set));
    }
    OvlCand *best = &cands[0];
    for (int i = 1; i < nv; ++i) {
        if (parseOverloadBetter(&cands[i], best, argc)) best = &cands[i];
    }
    Vec *tied = astVecNew();
    for (int i = 0; i < nv; ++i) {
        if (&cands[i] != best && !parseOverloadBetter(best, &cands[i], argc)) {
            if (!tied->size) vecPush(tied, best->fn);
            vecPush(tied, cands[i].fn);
        }
    }
    if (tied->size) {
        parseRaiseCallError(cc, loc, "the call `%s%s` is ambiguous: %s fit as well; "
                            "cast an argument to pick one",
                            shown, parseOverloadArgTypes(argv), parseOverloadList(tied));
    }
    *decl_out = best->decl;
    return best->fn;
}

/* A module's overload picked from outside it (`a.b.f` written): it must
 * be pub itself, not only another overload of its name */
static void parseCheckOverloadAccess(Cctrl *cc, Ast *fn, char *fname, int len,
                                     ParseCallLoc *loc) {
    int mod_len = astLastTopDot(fname, len);
    if (mod_len < 0 || (fn->flags & AST_FLAG_PUB)) return;
    AoStr *here = cctrlModulePath(cc, cc->cur_module);
    if (here && (int)here->len == mod_len && !memcmp(here->data, fname, mod_len)) return;
    if (cctrlTestSees(cc, fname, mod_len)) return;
    parseRaiseCallError(cc, loc, "`%s` is private to module %.*s",
                        parseOverloadName(fn), mod_len, fname);
}

/* Remember which overload the call at loc picked (the LSP shows it) */
static void parseNoteOverloadCall(Cctrl *cc, ParseCallLoc *loc, int name_len, Ast *fn) {
    if (!loc || !loc->line) return;
    if (!cc->overload_calls) cc->overload_calls = vecNew(&vec_unsigned_long_type);
    CctrlOverloadCall *call = malloc(sizeof(CctrlOverloadCall));
    call->file_id = ast_file_hint;
    call->line = loc->line;
    call->col = loc->col;
    call->len = name_len;
    call->fn = fn;
    vecPush(cc->overload_calls, call);
}

/* After the overload is picked: its `T ...name` extras (parsed as plain
 * arguments, from the count's position on) packed as parseArgv does */
static void parseOverloadPackVarArgs(Cctrl *cc, Ast *decl, Vec *argv, Vec *spans,
                                     char *fname, int len) {
    Vec *params = parseGetFunctionParams(decl);
    if (!params || params->size < 2) return;
    Ast *last = params->entries[params->size - 1];
    if (!last || last->kind != AST_LVAR || !(last->flags & AST_FLAG_VARARGS)) return;
    int va_at = (int)params->size - 2;
    Vec *items = astVecNew();
    Vec *item_spans = vecNew(&vec_unsigned_long_type);
    for (int i = va_at; i < (int)argv->size; ++i) {
        vecPush(items, argv->entries[i]);
        vecPush(item_spans, spans && i < (int)spans->size ? spans->entries[i] : NULL);
    }
    if ((int)argv->size > va_at) argv->size = va_at;
    if (spans && (int)spans->size > va_at) spans->size = va_at;
    while ((int)argv->size < va_at) {
        vecPush(argv, astMakePlaceHolder());
        if (spans) vecPush(spans, NULL);
    }
    parseVarArgsPack(cc, argv, spans, last->type->ptr, items, item_spans,
                     NULL, -1, va_at, fname, len,
                     (last->flags & AST_FLAG_VARARGS_ANY) != 0);
}

/* `&f` of an overloaded f going where a `want` is expected: the overload
 * of that type. Returns expr unchanged when it isn't such an `&f`. */
Ast *parseResolveOverloadRef(Cctrl *cc, AstType *want, Ast *expr) {
    if (!expr || !(expr->flags & AST_FLAG_OVERLOAD_REF) || !want) return expr;
    Ast *pick = parseOverloadRefPick(expr, want);
    if (!pick) {
        AstType *w = want->kind == AST_TYPE_POINTER && want->ptr ? want->ptr : want;
        if (w->kind != AST_TYPE_FUNC) return expr;
        expr->flags &= ~AST_FLAG_OVERLOAD_REF;
        cctrlRaiseException(cc, "no overload of `%s` has the type `%s`; there are %s",
                            parseOverloadBaseName(expr->operand), astTypeToString(want),
                            parseOverloadList(expr->operand->overloads));
    }
    expr->operand = pick;
    expr->type = pick->type;
    expr->flags &= ~AST_FLAG_OVERLOAD_REF;
    return expr;
}

/* Every `&f` of an overloaded f must have met a function type that picks
 * one (parseResolveOverloadRef); run at the end of each function */
void parseCheckOverloadRefs(Cctrl *cc) {
    if (!cc->overload_refs) return;
    List *refs = cc->overload_refs;
    cc->overload_refs = NULL;
    listForEach(refs) {
        Ast *ref = it->value;
        if (!(ref->flags & AST_FLAG_OVERLOAD_REF)) continue;
        char *name = parseOverloadBaseName(ref->operand);
        cctrlRaiseExceptionAt(cc, ref->line, ref->col, 1,
            "`%s` has overloads %s: `&%s` needs the function type to pick one, "
            "as in `%s p = &%s;`",
            name, parseOverloadList(ref->operand->overloads), name,
            astTypeToString(((Ast *)ref->operand->overloads->entries[0])->type), name);
    }
}

/* A call to instance `fn` of a generic function with `argv` parsed
 * without a target: checked, completed and converted as any call */
/* argv was parsed before the generic's instance was known: the arguments
 * of fn's @caller parameters, put in their places; how many */
static int parseGenericFillCallers(Cctrl *cc, Ast *fn, Vec *argv, ParseCallLoc *loc) {
    Vec *params = parseGetFunctionParams(fn);
    int n = 0;
    for (u64 i = 0; params && i < params->size && i <= argv->size; ++i) {
        Ast *p = params->entries[i];
        if (!p || !(p->flags & AST_FLAG_CALLER)) continue;
        vecInsertAt(argv, parseCallerArg(cc, p, loc->line), i);
        if (loc->args) vecInsertAt(loc->args, NULL, i);
        n++;
    }
    return n;
}

static Ast *parseGenericCallWith(Cctrl *cc, Ast *fn, Vec *argv, ParseCallLoc *loc,
                                 char *fname, int len) {
    parseGenericFillCallers(cc, fn, argv, loc);
    parseOverloadPackVarArgs(cc, fn, argv, loc->args, fname, len);
    parseFunctionArgumentCheck(cc, fn, argv, fname, len, loc);
    parseFlattenDefaultArgs(fn, argv);
    parseAddEmptyVarArgCount(fn, argv);
    parseCoerceArgs(fn, argv);
    parseCallLocFreeArgs(loc);
    return astFunctionCall(fn->type->rettype, fn->fname->data, fn->fname->len, argv);
}

/* A generic name with several definitions: an instance of each one the
 * type arguments fit (targs, or inferred from argv when NULL), then the
 * one the arguments fit, as for plain overloads */
static Ast *parseGenericOverloadPick(Cctrl *cc, GenericDef *head, Vec *targs, Vec *argv,
                                     ParseCallLoc *loc, char *fname, int len, Lexeme *at)
{
    Vec *set = astVecNew();
    char *why = NULL;
    for (GenericDef *d = head; d; d = d->next) {
        Vec *a = targs;
        if (!a) {
            char *w = NULL;
            a = parseGenericInfer(cc, d, argv, &w);
            if (!a) { why = w; continue; }
        } else if (a->size != d->params->size) {
            continue;
        }
        Ast *inst = parseGenericFuncInstance(cc, d, a, at);
        int dup = 0;
        for (u64 i = 0; i < set->size; ++i) dup |= set->entries[i] == inst;
        if (!dup) vecPush(set, inst);
    }
    if (!set->size) {
        if (targs) {
            parseRaiseCallError(cc, loc, "no `%.*s` takes %d type argument%s", len, fname,
                                (int)targs->size, targs->size == 1 ? "" : "s");
        }
        parseRaiseCallError(cc, loc, "can't work out the type arguments of `%.*s`: %s; "
                            "write them, as in `%.*s<i64>(...)`", len, fname,
                            why ? why : "no definition fits", len, fname);
    }
    if (set->size == 1) return set->entries[0];
    /* picked with the @caller arguments (overloads start with the same
     * ones), which parseGenericCallWith puts back for the one picked */
    Ast *decl = NULL;
    Vec *params = parseGetFunctionParams(set->entries[0]);
    int filled = parseGenericFillCallers(cc, set->entries[0], argv, loc);
    Ast *pick = parseResolveOverload(cc, set, argv, 0, fname, len, loc, &decl);
    for (u64 i = params ? params->size : 0; filled && i-- > 0;) {
        Ast *p = params->entries[i];
        if (i >= argv->size || !p || !(p->flags & AST_FLAG_CALLER)) continue;
        vecRemoveAt(argv, i);
        if (loc->args) vecRemoveAt(loc->args, i);
    }
    return pick;
}

/* `name<T, U>(args)`, the `<` next: the call of that instance */
Ast *parseGenericExplicitCall(Cctrl *cc, GenericDef *d, char *fname, int len) {
    Lexeme *at = parseLastToken(cc);
    Vec *targs = parseGenericArgs(cc);
    if (d->overloaded) {
        cctrlTokenExpect(cc, '(');
        ParseCallLoc oloc = parseCallLocStart(cc, fname, len);
        oloc.args = vecNew(&vec_unsigned_long_type);
        Vec *oargv = parseArgv(cc, NULL, ')', fname, len, oloc.args);
        parseCallLocEnd(cc, &oloc);
        Ast *pick = parseGenericOverloadPick(cc, d, targs, oargv, &oloc, fname, len, at);
        return parseGenericCallWith(cc, pick, oargv, &oloc, fname, len);
    }
    Ast *inst = parseGenericFuncInstance(cc, d, targs, at);
    cctrlTokenExpect(cc, '(');
    ParseCallLoc loc = parseCallLocStart(cc, fname, len);
    loc.args = vecNew(&vec_unsigned_long_type);
    Vec *argv = parseArgv(cc, inst, ')', fname, len, loc.args);
    parseCallLocEnd(cc, &loc);
    parseFunctionArgumentCheck(cc, inst, argv, fname, len, &loc);
    parseFlattenDefaultArgs(inst, argv);
    parseAddEmptyVarArgCount(inst, argv);
    parseCoerceArgs(inst, argv);
    parseCallLocFreeArgs(&loc);
    return astFunctionCall(inst->type->rettype, inst->fname->data, inst->fname->len, argv);
}

Ast *parseFunctionArguments(Cctrl *cc, char *fname, int len, s64 terminator) {
    AstType *rettype = NULL;
    Ast *maybe_fn = findFunctionDecl(cc,fname,len);
    /* a generic function: its instance for the argument types. Next to
     * plain functions of the name, those that take the arguments win and
     * the generic is the fallback */
    int plain = maybe_fn && (maybe_fn->kind == AST_FUNC || maybe_fn->kind == AST_FUN_PROTO);
    GenericDef *generic = !maybe_fn || plain ? parseFindGenericFunc(cc, fname, len) : NULL;
    if (generic) {
        Lexeme *at = parseLastToken(cc);
        ParseCallLoc gloc = parseCallLocStart(cc, fname, len);
        gloc.args = vecNew(&vec_unsigned_long_type);
        Vec *gargv = parseArgv(cc, NULL, terminator, fname, len, gloc.args);
        parseCallLocEnd(cc, &gloc);
        if (plain) {
            Vec *set = maybe_fn->overloads;
            if (!set) {
                set = astVecNew();
                vecPush(set, maybe_fn);
            }
            Ast *decl = NULL;
            parse_overload_quiet = 1;
            Ast *pick = parseResolveOverload(cc, set, gargv, 0, fname, len, &gloc, &decl);
            parse_overload_quiet = 0;
            if (pick) {
                if (pick->flags & AST_FLAG_OVERLOAD) parseCheckOverloadAccess(cc, pick, fname, len, &gloc);
                return parseGenericCallWith(cc, pick, gargv, &gloc, fname, len);
            }
        }
        if (generic->overloaded) {
            Ast *pick = parseGenericOverloadPick(cc, generic, NULL, gargv, &gloc, fname, len, at);
            return parseGenericCallWith(cc, pick, gargv, &gloc, fname, len);
        }
        char *why = NULL;
        Vec *targs = parseGenericInfer(cc, generic, gargv, &why);
        if (!targs) {
            parseRaiseCallError(cc, &gloc, "can't work out the type arguments of `%.*s`: %s; "
                                "write them, as in `%.*s<i64>(...)`", len, fname, why, len, fname);
        }
        Ast *inst = parseGenericFuncInstance(cc, generic, targs, at);
        return parseGenericCallWith(cc, inst, gargv, &gloc, fname, len);
    }
    Vec *overloads = maybe_fn && (maybe_fn->kind == AST_FUNC || maybe_fn->kind == AST_FUN_PROTO)
                   ? maybe_fn->overloads : NULL;

    ParseCallLoc loc = parseCallLocStart(cc, fname, len);
    loc.args = vecNew(&vec_unsigned_long_type);
    /* an overloaded name: the arguments first, then the one they fit */
    if (overloads) parseSetOverloadCallers(overloads, 0, 0);
    Vec *argv = parseArgv(cc,overloads ? NULL : maybe_fn,terminator,fname,len,loc.args);
    parseCallLocEnd(cc, &loc);
    if (overloads) {
        Ast *decl = NULL;
        maybe_fn = parseResolveOverload(cc, overloads, argv, 0, fname, len, &loc, &decl);
        parseOverloadPackVarArgs(cc, maybe_fn, argv, loc.args, fname, len);
        parseCheckOverloadAccess(cc, maybe_fn, fname, len, &loc);
        parseNoteOverloadCall(cc, &loc, len, maybe_fn);
    }

    if (maybe_fn) {
        parseFunctionArgumentCheck(cc,maybe_fn,argv,fname,len,&loc);
        parseFlattenDefaultArgs(maybe_fn, argv);
        parseAddEmptyVarArgCount(maybe_fn, argv);
        parseCoerceArgs(maybe_fn, argv);
        parsePrintfFormatCheck(cc, maybe_fn, argv, fname, len, &loc);
        parseCallLocFreeArgs(&loc);


        rettype = maybe_fn->type->rettype;
        if (maybe_fn->flags & AST_FLAG_INLINE) {
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
            if (cc->cur_module || memchr(fname, '.', len) ||
                (maybe_fn->flags & AST_FLAG_OVERLOAD)) {
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
        /* a generic instance's method, made when first called */
        if (!fn) fn = parseGenericMethod(cc, t, name, len);
        if (fn && (fn->kind == AST_FUNC || fn->kind == AST_FUN_PROTO)) {
            *owner = t;
            return fn;
        }
    }
    return NULL;
}

static Ast *parseMethodCallWith(Cctrl *cc, Ast *fn, Ast *decl, AstType *owner, Ast *recv,
                                char *name, int len, Vec *argv, ParseCallLoc *lp);
static Ast *parseMethodCall(Cctrl *cc, Ast *fn, AstType *owner, Ast *recv,
                            char *name, int len);

/* The receiver of a method call on `cls`: the address self gets */
static Ast *parseMethodRecv(Cctrl *cc, Ast *cls) {
    Ast *recv;
    if (astIsDeref(cls)) {
        recv = cls->operand;
    } else if ((cls->kind == AST_FUNCALL || cls->kind == AST_FUNPTR_CALL ||
                cls->kind == AST_ASM_FUNCALL) &&
               (cls->type->kind == AST_TYPE_CLASS || cls->type->kind == AST_TYPE_UNION) &&
               !cls->type->is_intrinsic) {
        /* a call's result (`fs.readAll(p).take()`): its value in a
         * hidden variable, for self to point at */
        recv = astTempAddr(parseHiddenSliceVar(cc, cls->type), cls);
    } else {
        if (!assertLValue(cls)) {
            cctrlRaiseException(cc, "a method is called on a variable "
                                    "or a pointer, not on a %s",
                                astKindToHumanReadable(cls));
        }
        recv = astUnaryOperator(astMakePointerType(cls->type),
                                AST_UN_OP_ADDR_OF, cls);
    }
    return recv;
}

/* `x.f(args)` or `x.f<A>(args)` of generic method `d` (the `(` or `<`
 * next): the call of its instance for the type arguments, written or
 * inferred from the arguments as for a generic function */
static Ast *parseGenericMethodCall(Cctrl *cc, GenericDef *d, AstType *owner, Ast *recv,
                                   Lexeme *tok) {
    char *name = tok->start;
    int len = tok->len;
    if (tokenPunctIs(cctrlTokenPeek(cc), '<')) {
        Vec *own = parseGenericArgs(cc);
        Ast *fn = parseGenericOwnMethodInstance(cc, d, owner, own, tok);
        cctrlTokenExpect(cc, '(');
        return parseMethodCall(cc, fn, owner, recv, name, len);
    }
    cctrlTokenExpect(cc, '(');
    char *fname = mprintf("%s.%.*s", owner->clsname->data, len, name);
    int flen = strlen(fname);
    ParseCallLoc loc = parseCallLocStart(cc, name, len);
    loc.args = vecNew(&vec_unsigned_long_type);
    Vec *argv = parseArgv(cc, NULL, ')', fname, flen, loc.args);
    parseCallLocEnd(cc, &loc);
    char *why = NULL;
    Vec *own = parseGenericOwnMethodInfer(cc, d, owner, argv, &why);
    if (!own) {
        parseRaiseCallError(cc, &loc, "can't work out the type arguments of `%s`: %s; "
                            "write them, as in `x.%.*s<i64>(...)`", fname, why, len, name);
    }
    Ast *fn = parseGenericOwnMethodInstance(cc, d, owner, own, tok);
    Ast *decl = parseMethodWithoutSelf(fn);
    parseGenericFillCallers(cc, decl, argv, &loc);
    return parseMethodCallWith(cc, fn, decl, owner, recv, name, len, argv, &loc);
}

/* A generic method of a module's struct, used outside that module: it
 * must be pub */
static void parseCheckGenericMethodAccess(Cctrl *cc, GenericDef *d, AstType *owner, Lexeme *tok) {
    if (d->is_pub) return;
    AoStr *cls = owner->clsname;
    int mod_len = astLastTopDot(cls->data, (int)cls->len);
    if (mod_len < 0) return;
    AoStr *here = cctrlModulePath(cc, cc->cur_module);
    if (here && (int)here->len == mod_len && !memcmp(here->data, cls->data, mod_len)) return;
    cctrlRewindUntilStrMatch(cc, tok->start, tok->len, NULL);
    cctrlRaiseException(cc, "the method `%.*s` of `%s` is private to module %.*s",
                        tok->len, tok->start, cls->data, mod_len, cls->data);
}

/* A method of a module's type, used outside that module: it must be pub */
static void parseCheckMethodAccess(Cctrl *cc, AstType *owner, char *name,
                                   int len) {
    AoStr *cls = owner->clsname;
    int mod_len = astLastTopDot(cls->data, (int)cls->len);
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

/* `p.push(x)` on a parameter the function got by value (`void f(vec.Vec<T>
 * p)`): p is a copy of the caller's Vec, Map, List, Tree, Set or String
 * header, so a method that changes its length or moves its buffer changes
 * the copy only; the caller keeps the old one (and frees what it held
 * twice, or loses what was added). Warn, pointing at the method. */
static void parseWarnByValueChange(Cctrl *cc, AstType *owner, Ast *recv,
                                   char *name, int len, ParseCallLoc *loc) {
    if (!recv || !cc->tmp_params || recv->kind != AST_UNOP ||
        recv->unop != AST_UN_OP_ADDR_OF || !recv->operand ||
        recv->operand->kind != AST_LVAR) {
        return;
    }
    Ast *var = recv->operand;
    int is_param = 0;
    for (u64 i = 0; i < cc->tmp_params->size; ++i) {
        Ast *p = cc->tmp_params->entries[i];
        if (p && p->kind == AST_DEFAULT_PARAM) p = p->declvar;
        if (p && p->kind == AST_LVAR && p->lvar_id == var->lvar_id) {
            is_param = 1;
            break;
        }
    }
    if (!is_param || var->type->kind != AST_TYPE_CLASS) return;

    static const char *vec_m[] = {"push", "pop", "insert", "removeAt", "clear", "reserve", NULL};
    static const char *list_m[] = {"append", "prepend", "insertBefore", "insertAfter", "remove",
                                   "shift", "pop", "clear", "mergeAppend", "mergePrepend", NULL};
    static const char *map_m[] = {"set", "delete", "clear", NULL};
    static const char *set_m[] = {"add", "remove", "delete", "clear", NULL};
    static const char *str_m[] = {"append", "appendByte", "appendFormat", "reserve", "clear",
                                  "take", "truncate", "insert", NULL};
    const char **methods = NULL;
    const char *what = NULL;
    GenericDef *g = owner && owner->generic && owner->generic_args ? owner->generic : NULL;
    if (astTypeIsString(var->type)) {
        methods = str_m;
        what = "String";
    } else if (g && g->name) {
        if (!strcmp(g->name->data, "vec.Vec")) { methods = vec_m; what = "vec.Vec"; }
        else if (!strcmp(g->name->data, "list.List")) { methods = list_m; what = "list.List"; }
        else if (!strcmp(g->name->data, "map.Map") ||
                 !strcmp(g->name->data, "map.Tree")) { methods = map_m; what = g->name->data; }
        else if (!strcmp(g->name->data, "set.Set")) { methods = set_m; what = "set.Set"; }
    }
    if (!methods) return;
    int hit = 0;
    for (int i = 0; methods[i]; ++i) {
        if ((int)strlen(methods[i]) == len && !memcmp(methods[i], name, len)) {
            hit = 1;
            break;
        }
    }
    if (!hit) return;
    AoStr *tname = astTypeToAoStr(var->type);
    cctrlWarningAt(cc, loc->line, loc->col, loc->len,
                   "`%s` is a copy of the caller's %s: `%.*s` changes only the copy "
                   "(the caller still has the old one); take `%s *%s` "
                   "and pass `&%s`",
                   var->lname->data, what, len, name, tname->data,
                   var->lname->data, var->lname->data);
}

/* The arguments of a call to the method `fn`, the `(` just taken. With
 * `recv` (`u.name(...)`, a pointer to the object) the arguments are the
 * method's own and `recv` becomes `self`; without (`User.name(p, ...)`)
 * the first argument is `self`. */
static Ast *parseMethodCall(Cctrl *cc, Ast *fn, AstType *owner, Ast *recv,
                            char *name, int len) {
    Ast *decl = fn;
    if (recv && !fn->overloads) {
        /* The method as seen by the caller: without `self` */
        decl = parseMethodWithoutSelf(fn);
    }
    char *fname = mprintf("%s.%.*s", owner->clsname->data, len, name);
    int flen = strlen(fname);

    ParseCallLoc loc = parseCallLocStart(cc, name, len);
    loc.args = vecNew(&vec_unsigned_long_type);
    if (fn->overloads) parseSetOverloadCallers(fn->overloads, 1, recv == NULL);
    Vec *argv = parseArgv(cc, fn->overloads ? NULL : decl, ')', fname, flen, loc.args);
    parseCallLocEnd(cc, &loc);
    return parseMethodCallWith(cc, fn, decl, owner, recv, name, len, argv, &loc);
}

/* parseMethodCall with the arguments parsed: argv and its locations */
static Ast *parseMethodCallWith(Cctrl *cc, Ast *fn, Ast *decl, AstType *owner, Ast *recv,
                                char *name, int len, Vec *argv, ParseCallLoc *lp) {
    ParseCallLoc loc = *lp;
    char *fname = mprintf("%s.%.*s", owner->clsname->data, len, name);
    int flen = strlen(fname);
    if (fn->overloads) {
        fn = parseResolveOverload(cc, fn->overloads, argv, recv != NULL,
                                  fname, flen, &loc, &decl);
        /* its type's module, `geo` of `geo.User` */
        parseCheckOverloadAccess(cc, fn, owner->clsname->data,
                                 (int)owner->clsname->len, &loc);
        parseNoteOverloadCall(cc, &loc, len, fn);
        parseOverloadPackVarArgs(cc, decl, argv, loc.args, fname, flen);
    }
    parseFunctionArgumentCheck(cc, decl, argv, fname, flen, &loc);
    parseFlattenDefaultArgs(decl, argv);
    parseAddEmptyVarArgCount(decl, argv);
    parseCoerceArgs(decl, argv);
    parsePrintfFormatCheck(cc, decl, argv, fname, flen, &loc);
    parseCallLocFreeArgs(&loc);
    parseWarnByValueChange(cc, owner, recv, name, len, &loc);

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
    if (tok && tok->tk_type == TK_STR) {
        char removed = parseFormatRemovedConv(tok->start, tok->len, 1);
        if (removed) {
            parseFormatRemovedError(cc, removed, tok->line, tok->col, tok->len);
        }
    }
    if (tok && tok->tk_type == TK_STR &&
        parsePrintFormatIsktm(tok->start, tok->len)) {
        char *print = astRuntimeName("__ktmPrintf");
        return parseFunctionArguments(cc,print,strlen(print),terminator);
    }
    Ast *call = parseFunctionArguments(cc,"printf",6,terminator);
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

/* The old print statement, `"fmt", args;`: an error naming printf (the
 * REPL's input aside), parsed on as before */
Ast *parsePrintStatement(Cctrl *cc) {
    Lexeme *tok = cctrlTokenPeek(cc);
    if (tok && !(cc->flags & CCTRL_REPL)) {
        cctrlErrorAt(cc, tok->line, tok->col, tok->len,
                     "a print statement: write `printf(\"...\", ...)`");
    }
    return parsePrintCall(cc, ';');
}

/* The arguments of a call to `name`, the `(` just read. `printf(...)` is
 * ktm's print (see parsePrintCall), not a bare call to C's printf. */
Ast *parseCallArguments(Cctrl *cc, char *name, int len) {
    if (len == 6 && !memcmp(name, "printf", 6)) {
        return parsePrintCall(cc, ')');
    }
    Ast *reflected = parseReflectCall(cc, name, len);
    if (reflected) {
        return reflected;
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

    /* `name<T>(...)`: a generic function with its type arguments;
     * `name(...)`: one whose arguments say what they are */
    if (can_call_function && is_lparen && !cctrlGetVar(cc, name, len) &&
        parseFindGenericFunc(cc, name, len)) {
        return parseFunctionArguments(cc, name, len, ')');
    }
    /* `<` after the name of a generic function (plain functions of the
     * name too, as for json.decode) is its type-argument list, unless a
     * local of that name hides it */
    if (can_call_function && tokenPunctIs(tok, '<') &&
        !(cc->localenv && mapGetLen(cc->localenv, name, len))) {
        GenericDef *gd = parseFindGenericFunc(cc, name, len);
        if (gd) {
            cctrlTokenRewind(cc);
            return parseGenericExplicitCall(cc, gd, name, len);
        }
    }

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
        /* the old implicit argc/argv of every function (always 0 and
         * null outside an old `(...)` one): main's parameters now */
        if (len == 4 && (!memcmp(name, "argc", 4) || !memcmp(name, "argv", 4))) {
            cctrlRaiseSuggestion(cc,
                    "they are main's parameters, `i32 main(i32 argc, u8 **argv)`: "
                    "pass them to the function that needs them",
                    "`%.*s` is not defined here", len, name);
        }
        if (tok == NULL) {
            /* Identifier at end of input - nothing to suggest from. */
            cctrlRaiseException(cc,
                    "Variable or function `%.*s` has not been defined",
                    len, name);
        }
        if (tok->tk_type == TK_PUNCT) {
            if (tok->i64 == '.') parseRaiseIfNotImported(cc, NULL, name, len);
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
        /* A word after it (`foo bar;`, `foo i64 n;`): no expression goes
         * on like that, so it is the name that is unknown. (Falling
         * through rewound past the name, and the caller reported the
         * token before it: a block's `{`.) */
        if (tok->tk_type == TK_IDENT) {
            cctrlRaiseException(cc,"Type `%.*s` has not been declared", len, name);
        }
        cctrlRaiseException(cc,"Variable or function `%.*s` has not been defined", len, name);
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
                /* the old call without `(`: an error, parsed on as one */
                if (name_tok && !(cc->flags & CCTRL_REPL)) {
                    cctrlErrorAt(cc, name_tok->line, name_tok->col, name_tok->len,
                                 "a call needs its `()`: write `%.*s()`",
                                 name_tok->len, name_tok->start);
                }
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
                if (ast->flags & AST_FLAG_INLINE) {
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
    /* reflect.typeOf(T) takes a type: a call, never a postfix cast */
    if ((len == 14 && !memcmp(name, "reflect.typeOf", 14)) ||
        (len == 10 && !memcmp(name, "reflect.of", 10))) {
        return parseCallArguments(cc,name,len);
    }
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

/* Adjacent string literals are one string, `"a" "b"`. `first` is the
 * token just read, a string. */
static Ast *parseStringLiterals(Cctrl *cc, Lexeme *first) {
    AoStr *str = aoStrNew();
    s64 real_len = 1;
    Lexeme *tok = first;
    while (tok && tok->tk_type == TK_STR) {
        aoStrCatFmt(str, "%.*s", tok->len, tok->start);
        real_len += tok->i64 - 1;
        tok = cctrlTokenGet(cc);
    }
    if (tok) cctrlTokenRewind(cc);
    Ast *ast = cctrlGetOrSetString(cc, str->data, str->len, real_len);
    aoStrRelease(str);
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
    int has = 0;
    if (type && parseIsClassOrUnion(type->kind)) {
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

/* `base[lo:hi]` (either may be left out) after the `[` and the `lo` */
static Ast *parseSubSlice(Cctrl *cc, Ast *base, Ast *lo, int line) {
    Ast *hi = NULL;
    if (!tokenPunctIs(cctrlTokenPeek(cc), ']')) {
        hi = parseRequiredExpr(cc, "the end of the slice");
    }
    cctrlTokenExpect(cc, ']');
    Ast *bounds[2] = { lo, hi };
    for (int i = 0; i < 2; ++i) {
        if (bounds[i] && !astIsIntOrIntrinsic(bounds[i]->type)) {
            cctrlRaiseExceptionFromTo(cc, "A slice's bounds must be integers",
                '[', ']', "Cannot slice `%s` with a %s of type `%s`",
                astTypeToString(base->type), astKindToHumanReadable(bounds[i]),
                astTypeToString(bounds[i]->type));
        }
    }
    AstType *elem;
    if (astTypeIsSlice(base->type)) {
        elem = base->type->elem;
    } else if (base->type->kind == AST_TYPE_ARRAY || base->type->kind == AST_TYPE_POINTER) {
        elem = base->type->ptr;
        /* a pointer has no length to default the end to */
        if (base->type->kind == AST_TYPE_POINTER && !hi) {
            cctrlRewindUntilPunctMatch(cc, ']', NULL);
            cctrlRaiseException(cc, "`p[a:]` of a pointer needs its end: `p[a:b]` "
                                "(a pointer has no length)");
        }
    } else {
        cctrlRewindUntilPunctMatch(cc, '[', NULL);
        cctrlRaiseException(cc, "Cannot slice a %s, only slices, arrays or pointers",
                            astTypeKindToHumanReadable(base->type));
    }
    AstType *type = astMakeSliceType(elem);
    return astSubSlice(parseHiddenSliceVar(cc, type), base, lo, hi,
                       parseThrowFile(cc), line);
}

Ast *parseSubscriptExpr(Cctrl *cc, Ast *ast) {
    int line = cc->lineno;
    /* `s[i]`, `s[a:b]` of a String: of its bytes, a u8[] view */
    if (astTypeIsString(ast->type)) {
        ast = parseSliceFrom(cc, astMakeSliceType(ast_u8_type), ast);
    }
    /* `s[:b]` */
    if (tokenPunctIs(cctrlTokenPeek(cc), ':')) {
        cctrlTokenGet(cc);
        return parseSubSlice(cc, ast, NULL, line);
    }
    Ast *subscript = parseExpr(cc,16);
    /* `s[a:b]`, `s[a:]` */
    if (subscript && tokenPunctIs(cctrlTokenPeek(cc), ':')) {
        cctrlTokenGet(cc);
        return parseSubSlice(cc, ast, subscript, line);
    }
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

    /* `s[i]` of a slice: its element, once i is checked against its len */
    if (astTypeIsSlice(ast->type)) {
        Ast *elem = astSliceElem(ast, subscript, parseThrowFile(cc), line);
        parseElideNoteElem(cc, ast, subscript, elem);
        return astUnaryOperator(ast->type->elem, AST_UN_OP_DEREF, elem);
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

/* `ad T name = ...;`: the call that frees `var` at the end of its scope,
 * `name.free()`: a slice's buffer (as `parts.free()`), or the `free()`
 * method of its type or of the type it points to (else an @json struct's
 * `freeDecoded()`). An error naming the type when it has neither. */
Ast *parseAutoDeferFree(Cctrl *cc, Ast *var, Lexeme *at) {
    AstType *t = var->type;
    if (astTypeIsSlice(t) && !astTypeIsString(t)) {
        Vec *args = astVecNew();
        vecPush(args, astClassRef(mapGetLen(t->fields, str_lit("ptr")), var, "ptr"));
        char *sym = astRuntimeSymbol("__ktmSliceFree");
        return astFunctionCall(ast_void_type, sym, strlen(sym), args);
    }
    int is_ptr = t->kind == AST_TYPE_POINTER;
    AstType *cls = is_ptr ? t->ptr : t;
    AstType *owner = NULL;
    Ast *fn = NULL;
    const char *method = "free";
    if (cls && (cls->kind == AST_TYPE_CLASS || cls->kind == AST_TYPE_UNION) &&
        !cls->is_intrinsic) {
        fn = parseFindMethod(cc, cls, "free", 4, &owner);
        if (!fn) {
            /* an @json / @yaml struct's generated freeDecoded() */
            fn = parseFindMethod(cc, cls, "freeDecoded", 11, &owner);
            method = "freeDecoded";
        }
    }
    /* of an overloaded free(), the one that takes nothing */
    if (fn && fn->overloads) {
        Ast *none = NULL;
        for (u64 i = 0; i < fn->overloads->size; ++i) {
            Ast *o = fn->overloads->entries[i];
            if (o->params && o->params->size == 1) none = o;
        }
        fn = none;
    }
    if (!fn || !fn->params || fn->params->size != 1) {
        cctrlRaiseExceptionAt(cc, at->line, at->col, at->len,
            "`ad` frees its variable with `free()`, and `%s` has no `free()` that "
            "takes no arguments: declare it without `ad` and free it yourself",
            astTypeToString(t));
    }
    parseCheckMethodAccess(cc, owner, (char *)method, strlen(method));
    Ast *recv = is_ptr ? var
              : astUnaryOperator(astMakePointerType(t), AST_UN_OP_ADDR_OF, var);
    AstType *self_type = ((Ast *)fn->params->entries[0])->type;
    if (recv->type->ptr != self_type->ptr) recv = astCast(recv, self_type);
    Vec *args = astVecNew();
    vecPush(args, recv);
    return astFunctionCall(fn->type->rettype, fn->fname->data, fn->fname->len, args);
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

/* `x.name(...)` of a slice or a String with no such member or method,
 * the `(` next:
 *   - `parts.free()` of any slice: rt.__ktmSliceFree(parts.ptr)
 *   - a byte slice or a String: str.name(x, ...) when str has a function
 *     of that name taking a u8[] first (`s.find("x")`, `s.split(",")`)
 * NULL when it is neither. */
static Ast *parseSliceMethodCall(Cctrl *cc, Ast *cls, AstType *type, Lexeme *tok) {
    if (astTypeIsSlice(type) && tok->len == 4 && !memcmp(tok->start, "free", 4)) {
        cctrlTokenGet(cc);
        cctrlTokenExpect(cc, ')');
        Vec *args = astVecNew();
        vecPush(args, astClassRef(mapGetLen(type->fields, str_lit("ptr")), cls, "ptr"));
        char *sym = astRuntimeSymbol("__ktmSliceFree");
        return astFunctionCall(ast_void_type, sym, strlen(sym), args);
    }
    if (!parseIsByteSlice(type)) return NULL;
    char *qual = mprintf("str.%.*s", tok->len, tok->start);
    Ast *fn = mapGet(cc->global_env, qual);
    if (!fn || (fn->kind != AST_FUNC && fn->kind != AST_FUN_PROTO) ||
        !fn->params || fn->params->size < 1) {
        return NULL;
    }
    Ast *first = fn->params->entries[0];
    AstType *first_t = first->kind == AST_DEFAULT_PARAM ? first->declvar->type : first->type;
    if (!parseIsByteSlice(first_t) || astTypeIsString(first_t)) return NULL;
    cctrlTokenGet(cc);
    Ast *recv = parseSliceFrom(cc, first_t, cls);
    AstType *owner = astTypeIsString(type) ? type : first_t;
    return parseMethodCall(cc, fn, owner, recv, tok->start, tok->len);
}

Ast *parseGetClassField(Cctrl *cc, Ast *cls) {
    AstType *type;
    /* `"abc".len`, `"a,b".split(",")`: a literal's u8[] view */
    if (cls->kind == AST_STRING) {
        cls = parseSliceFrom(cc, astMakeSliceType(ast_u8_type), cls);
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
    GenericDef *gmethod = NULL;
    if (!field && !method) {
        gmethod = parseFindGenericOwnMethod(cc, type, tok->start, tok->len, &owner);
    }
    if (gmethod) {
        parseCheckGenericMethodAccess(cc, gmethod, owner, tok);
        if (!tokenPunctIs(cctrlTokenPeek(cc), '(') && !tokenPunctIs(cctrlTokenPeek(cc), '<')) {
            cctrlRaiseException(cc, "`%s.%.*s` is a generic method: call it with "
                    "`(...)`; it has no address (wrap the call in a function)",
                    owner->clsname->data, tok->len, tok->start);
        }
        return parseGenericMethodCall(cc, gmethod, owner, parseMethodRecv(cc, cls), tok);
    }
    if (method) {
        parseCheckMethodAccess(cc, owner, tok->start, tok->len);
        if (!tokenPunctIs(cctrlTokenPeek(cc), '(')) {
            cctrlRaiseException(cc, "`%s.%.*s` is a method: call it with "
                    "`(...)`, or take its address with `&%s.%.*s`",
                    owner->clsname->data, tok->len, tok->start,
                    owner->clsname->data, tok->len, tok->start);
        }
        cctrlTokenGet(cc);
        return parseMethodCall(cc, method, owner, parseMethodRecv(cc, cls), tok->start, tok->len);
    }
    if (!field && tokenPunctIs(cctrlTokenPeek(cc), '(')) {
        Ast *call = parseSliceMethodCall(cc, cls, type, tok);
        if (call) return call;
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
/* `name.` where name is a module the file doesn't import: say so, at
 * `at` (NULL: where the parser is). Returns when there is no such module. */
void parseRaiseIfNotImported(Cctrl *cc, Lexeme *at, char *name, int len) {
    if (!cc->lexer_ || !lexModuleExists(cc->lexer_, name, len)) return;
    /* the runtime and the prelude can't be imported */
    if ((len == 2 && !memcmp(name, "rt", 2)) || (len == 7 && !memcmp(name, "prelude", 7))) return;
    if (at) {
        cctrlRaiseExceptionAt(cc, at->line, at->col, at->len,
            "module `%.*s` is not imported: add `import %.*s;`", len, name, len, name);
    }
    cctrlRaiseException(cc, "module `%.*s` is not imported: add `import %.*s;`",
                        len, name, len, name);
}

void parseRaiseFnConvert(Cctrl *cc, AstType *expected, AstType *actual) {
    cctrlRaiseException(cc, "cannot convert %s to %s%s",
            astTypeToString(actual), astTypeToString(expected),
            parseFnConvertHint(expected, actual));
}

/* What follows "cannot convert X to Y": a note when typed pointer
 * parameters would stand for void * ones on the function pointer itself
 * but not through a pointer to it, "" otherwise */
char *parseFnConvertHint(AstType *expected, AstType *actual) {
    if (astFnPtrOnlyDirect(expected, actual)) {
        return " (typed pointer parameters stand for void * ones only on a "
               "function pointer itself, not through a pointer to one)";
    }
    return "";
}

static Ast *parseCreateBinaryOpAt(Cctrl *cc, AstBinOp operation, Ast *left,
                                  Ast *right, s64 op_tok)
{
    if (operation == AST_BIN_OP_ASSIGN && left && right) {
        parseResolveOverloadRef(cc, left->type, right);
    }
    if (operation == AST_BIN_OP_LOG_AND || operation == AST_BIN_OP_LOG_OR) {
        parseCheckCondition(cc, left);
        parseCheckCondition(cc, right);
    }
    if ((operation == AST_BIN_OP_EQ || operation == AST_BIN_OP_NE) &&
        (astTypeIsSlice(left->type) || astTypeIsSlice(right->type))) {
        if (op_tok) cctrlRewindUntilPunctMatch(cc, op_tok, NULL);
        cctrlRaiseSuggestion(cc,
                "compare contents with .equals(), identity with a.ptr == b.ptr",
                "`%s` can't compare slices",
                operation == AST_BIN_OP_EQ ? "==" : "!=");
    }
    /* `s = array`: the array becomes a slice */
    if (operation == AST_BIN_OP_ASSIGN && left->type) {
        right = parseSliceFrom(cc, left->type, right);
    }
    int is_err = 0;
    Ast *binop = astBinaryOp(operation,left,right,&is_err);
    if (!is_err) {
        /* An integer `/` or `%` by a constant 0 always traps when it
         * runs, and the constant folder can't give it a value. */
        if ((operation == AST_BIN_OP_DIV || operation == AST_BIN_OP_MOD) &&
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
    } else if (is_err) {
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
    parseCheckCondition(cc, cond);
    Ast *then = parseExpr(cc, 16);
    if (!then) {
        cctrlRaiseException(cc, "Expected an expression after `?`");
    }
    cctrlTokenExpect(cc, ':');
    Ast *els = parseExpr(cc, 16);
    if (!els) {
        cctrlRaiseException(cc, "Expected an expression after `:`");
    }
    /* a literal or an array beside a slice: that slice */
    if (then->type && els->type) {
        if (astTypeIsSlice(then->type)) els = parseSliceFrom(cc, then->type, els);
        if (astTypeIsSlice(els->type)) then = parseSliceFrom(cc, els->type, then);
    }
    /* a struct (a slice, a String) only with one of its own type */
    AstType *ta = then->type, *tb = els->type;
    int agg_a = ta && (ta->kind == AST_TYPE_CLASS || ta->kind == AST_TYPE_UNION) && !ta->is_intrinsic;
    int agg_b = tb && (tb->kind == AST_TYPE_CLASS || tb->kind == AST_TYPE_UNION) && !tb->is_intrinsic;
    if ((agg_a || agg_b) &&
        !(agg_a && agg_b && ta->clsname && tb->clsname && aoStrCmp(ta->clsname, tb->clsname))) {
        cctrlRaiseException(cc, "the branches of `?:` have different types, %s and %s",
                            astTypeToString(ta), astTypeToString(tb));
    }
    AstType *rt = (agg_a && agg_b) ? ta : parseTernaryResultType(cc, then, els);
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
            assertNotFnIncDec(cc,LHS,tok->line,tok->col,tok->len);
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
            if (LHS->type->kind != AST_TYPE_POINTER && LHS->type->kind != AST_TYPE_ARRAY &&
                !astTypeIsSlice(LHS->type) &&
                !astTypeIsString(LHS->type)) {
                cctrlRewindUntilPunctMatch(cc,'[',NULL);
                cctrlRaiseException(cc,"Cannot subscript a %s, only pointers, arrays or slices can be subscripted with '['",
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

        /* `s = array`: the array becomes a slice */
        if (LHS->type) RHS = parseSliceFrom(cc, LHS->type, RHS);

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
                ast->type->kind != AST_TYPE_ARRAY && !astTypeIsSlice(ast->type) &&
                !astTypeIsString(ast->type)) {
                cctrlRewindUntilPunctMatch(cc,'[',NULL);
                cctrlRaiseException(cc,"Cannot subscript a %s, only pointers, arrays or slices can be subscripted with '['",
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
                s64 open_line = tok->line, open_col = tok->col;
                type = parseCastTypeName(cc);
                cctrlTokenExpect(cc,')');
                /* the old postfix cast `x(T)` */
                cctrlErrorAt(cc, open_line, open_col, 1,
                    "a postfix cast: write `(%s)` before the value",
                    astTypeToString(type));
                ast = astCast(ast,type);
                continue;
            } else if (ast->kind == AST_CLASS_REF ||
                       parseIsCallableFnPtr(ast)) {
                /* `s.len()` with `len` a plain field */
                if (!parseIsCallableFnPtr(ast)) {
                    cctrlTokenRewind(cc);
                    cctrlRaiseException(cc, "`%s` is a field, not a method: "
                            "read it without `()`", ast->field);
                }
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
            assertNotFnIncDec(cc,ast,tok->line,tok->col,tok->len);
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
    if (!ty || astIsIntType(ty) || astIsIntrinsicClass(ty) ||
        ty->kind == AST_TYPE_AUTO) {
        return;
    }
    if (op != AST_UN_OP_BIT_NOT && astIsFloatType(ty)) return;

    const char *opstr = op == AST_UN_OP_MINUS ? "-" :
                        op == AST_UN_OP_PLUS  ? "+" : "~";
    /* a variable of an fn type is a function pointer, as the old
     * declarator's was */
    char *msg = ty->kind == AST_TYPE_FUNC
        ? mprintf("The `%s` operator cannot be applied to a function pointer", opstr)
        : mprintf("The `%s` operator cannot be applied to a %s of %s",
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

/* `&x` of something without an address (`&5`, `&E.a`, `&f()`): an error
 * here; it reached the code generator, which gave up ("&AST_LITERAL not
 * supported in slice"). What has one: a variable, a field, an element
 * (`*p`, `a[i]`) or a function. */
static void parseCheckAddrOperand(Cctrl *cc, Ast *operand,
                                  int line, int col, int len)
{
    switch (operand->kind) {
        case AST_LVAR: case AST_GVAR: case AST_DEFAULT_PARAM:
        case AST_FUNPTR: case AST_FUNC: case AST_FUN_PROTO:
        case AST_EXTERN_FUNC: case AST_ASM_FUNCDEF: case AST_ASM_FUNC_BIND:
        case AST_CLASS_REF:
            return;
        default:
            if (astIsUnOpKind(operand, AST_UN_OP_DEREF)) return;
            break;
    }
    char *msg = operand->kind == AST_LITERAL
        ? mprintf("can't take the address of a constant")
        : mprintf("can't take the address of a %s",
                  astKindToHumanReadable(operand));
    char *sug = mprintf("only a variable, a field, an element or a function "
                        "has an address");
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
            assertNotFnIncDec(cc,operand,op_line,op_col,op_len);
            assertNotConstAssign(cc,operand);
        }

        switch (unary_op) {
            case AST_UN_OP_ADDR_OF: {
                parseCheckAddrOperand(cc, operand, op_line, op_col, op_len);
                parseNoteSliceMoved(operand);
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
            case AST_UN_OP_LOG_NOT:
                parseCheckCondition(cc, operand);
                type = ast_int_type;
                break;
            default:                type = operand->type; break;
        }

        ast = astUnaryOperator(type, unary_op, operand);
        if (unary_op == AST_UN_OP_ADDR_OF &&
            (operand->kind == AST_FUNC || operand->kind == AST_FUN_PROTO) &&
            operand->overloads) {
            /* `&f` of an overloaded f: where it goes picks one */
            ast->flags |= AST_FLAG_OVERLOAD_REF;
            ast->line = op_line;
            ast->col = op_col;
            if (!cc->overload_refs) cc->overload_refs = listNew();
            listAppend(cc->overload_refs, ast);
            if (addr_cast) ast = parseResolveOverloadRef(cc, addr_cast, ast);
        }
        return addr_cast ? astCast(ast, addr_cast) : ast;
    }
    
    cctrlTokenRewind(cc);
    return parsePostFixExpr(cc);
}
