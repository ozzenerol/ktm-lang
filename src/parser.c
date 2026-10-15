#include <ctype.h>
#include <setjmp.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>

#include "aostr.h"
#include "asm.h"
#include "ast.h"
#include "cctrl.h"
#include "lexer.h"
#include "list.h"
#include "parser.h"
#include "pkg.h"
#include "prslib.h"
#include "prsasm.h"
#include "prsutil.h"
#include "util.h"

/* `scoped` in front of a declaration (or `ad`, its old spelling): 1 for
 * `scoped`, 2 for `ad`, 0 for any other token */
static int parseScopedWord(Lexeme *tok) {
    if (!tok || tok->tk_type != TK_IDENT) return 0;
    if (tok->len == 6 && !memcmp(tok->start, "scoped", 6)) return 1;
    if (tok->len == 2 && !memcmp(tok->start, "ad", 2)) return 2;
    return 0;
}

/* `ad` still works, with a warning: not in a package's own files (the
 * module cache), which their authors update */
static void parseWarnAd(Cctrl *cc, Lexeme *tok) {
    LexFile *f = cctrlCurFile(cc);
    char *cache = pkgCacheDir();
    size_t n = strlen(cache);
    int in_cache = f && f->filename && !strncmp(f->filename->data, cache, n) &&
                   (f->filename->data[n] == '/' || f->filename->data[n] == '\0');
    if (!in_cache) {
        cctrlWarningAt(cc, tok->line, tok->col, tok->len,
                "`ad` is now `scoped`: write `scoped` in front of the declaration");
    }
}

#define MAX_ALIGN         16

/* PARSER Prototypes */
Ast *parseStatement(Cctrl *cc);
Ast *parseIfStatement(Cctrl *cc);
Ast *parseForStatement(Cctrl *cc);
Ast *parseVariableInitialiser(Cctrl *cc, Ast *var, s64 terminator_flags);
Ast *parseDecl(Cctrl *cc);
Ast *parseDeclOrStatement(Cctrl *cc);
Ast *parseCompoundStatement(Cctrl *cc);
Ast *parseTryStatement(Cctrl *cc);
Ast *parseThrowStatement(Cctrl *cc);
AstType *parseClassDef(Cctrl *cc, AstType *intrinsic_base);
AstType *parseUnionDef(Cctrl *cc);
AstType *parseEnumDef(Cctrl *cc);
static int parseTypeNameFollowedByDot(Cctrl *cc);

/* `reg <REG>` / `noreg` modifier after a decl type.
 * If the next token is `reg`, consume it plus the following register-
 * name identifier; if `noreg`, consume it. Otherwise leave the token
 * stream alone and report LVAR_AUTO. Shared with parseParams in
 * prslib.c for the parameter-list case. */
void parseRegModifier(Cctrl *cc, int *kind_out, AoStr **reg_out) {
    *kind_out = LVAR_AUTO;
    *reg_out = NULL;
    Lexeme *peek = cctrlTokenPeek(cc);
    if (!peek || peek->tk_type != TK_KEYWORD) return;
    if (peek->i64 == KW_REG) {
        cctrlTokenGet(cc);  /* consume `reg` */
        Lexeme *reg_tok = cctrlTokenGet(cc);
        if (!reg_tok || reg_tok->tk_type != TK_IDENT) {
            cctrlRaiseException(cc,
                "`reg` must be followed by a register name, got `%.*s`",
                reg_tok ? reg_tok->len : 0,
                reg_tok ? reg_tok->start : "");
        }
        *kind_out = LVAR_REG;
        *reg_out = aoStrDupRaw(reg_tok->start, reg_tok->len);
    } else if (peek->i64 == KW_NOREG) {
        cctrlTokenGet(cc);  /* consume `noreg` */
        *kind_out = LVAR_NOREG;
    }
}

static int range_loop_idx = 0;

static AoStr *getRangeLoopIdx(void) {
    return aoStrPrintf("___tmp%d___", ++range_loop_idx);
}


/* Kinda cheating converting it to a string and calling printf */
Ast *parseFloatingCharConst(Cctrl *cc, Lexeme *tok) {
    u64 ch = (unsigned long)tok->i64;
    /* Up to 8 bytes of up to 4 characters each: `\ooo` */
    char str[40];
    Vec *argv = astVecNew();
    int len = 0;
    int real_len = 0;

    /* Escaped as lexString does, as this goes into the assembly */
    while (ch) {
        unsigned int c = ch & 0xFF;
        if (c == '\\' || c == '"') {
            str[len++] = '\\';
            str[len++] = c;
        } else if (c == '%') {
            /* It is printf's format */
            str[len++] = '%';
            str[len++] = '%';
            real_len++;
        } else if (c < ' ' || c >= 0x7F) {
            str[len++] = '\\';
            str[len++] = '0' + (c >> 6);
            str[len++] = '0' + ((c >> 3) & 7);
            str[len++] = '0' + (c & 7);
        } else {
            str[len++] = c;
        }
        real_len++;
        ch = ch >> 8;
    }

    /* real_len counts the NUL too */
    Ast *ast = cctrlGetOrSetString(cc,str,len,real_len + 1);
    vecPush(argv, ast);
    cctrlTokenExpect(cc,';');
    return astFunctionCall(ast_void_type,"printf",6,argv);
}

/* `init`, an item of a class initialiser just parsed, must suit its
 * field. `start` is the item's first token, or NULL: the warning then
 * goes to the current token. */
void parseTypeCheckClassFieldInitaliser(Cctrl *cc, AstType *cls_field_type, Ast *init,
                                        Lexeme *start)
{
    parseResolveOverloadRef(cc, cls_field_type, init);
    if (astFnPtrMismatch(cls_field_type, init->type)) {
        parseRaiseFnConvert(cc, cls_field_type, init->type);
    }
    if (!astTypeCheck(cls_field_type, init, AST_BIN_OP_ASSIGN)) {
        char *cls_field_str = astTypeToColorString(cls_field_type);
        char *init_field = astTypeToColorString(init->type);
        char *var_string = astLValueToString(init,0);
        if (start) {
            /* Underline the item: up to its last token, the one
             * consumed last, when that is on the same line. */
            cctrlTokenRewind(cc);
            Lexeme *end = cctrlTokenGet(cc);
            int len = start->len;
            if (end && end->line == start->line && end->col >= start->col) {
                len = end->col + end->len - start->col;
            }
            cctrlWarningAt(cc,start->line,start->col,len,
                    "Incompatible value being assigned to class field expected '%s' got '%s %s'",
                    cls_field_str,init_field,var_string);
        } else {
            cctrlWarning(cc,"Incompatible value being assigned to class field expected '%s' got '%s %s'",
                    cls_field_str,init_field,var_string);
        }
    }
}

/* Fold a constant aggregate-initialiser element to a literal of the
 * destination element/field type. Global aggregate initialisers are written
 * out as static data, and the data emitters (aarch64DataInternal /
 * jitWriteInit) read each entry's literal value and size it by the entry's
 * own type. Two problems this fixes:
 *   - a bare `7` parses as the default 8-byte integer, so `I32 a[] = {7,8,9}`
 *     would emit `.quad` per element and be read back at the wrong stride;
 *   - an expression element like `-1` or `2+3` is not a literal at all, so
 *     the emitter would read garbage (writing 0 bytes / wrong value).
 * Evaluate the constant expression and rebuild a literal carrying the dest
 * type (masked to width for integers). Non-constant elements are left as-is
 * for their own branches to handle. */
static Ast *parseFoldInitElement(Ast *init, AstType *dst) {
    if (!init || !dst) {
        return init;
    }
    if (init->kind == AST_STRING || init->kind == AST_ARRAY_INIT) {
        return init;
    }
    if (dst->is_bool) {
        /* 0 or 1, as in C: `Bool b[] = {0x100, 0.5}` is {1, 1} */
        int ok = 1;
        s64 v = (init->type && astIsFloatType(init->type))
              ? evalFloatExprOrErr(init, &ok) != 0.0
              : evalIntConstExprOrErr(init, &ok) != 0;
        if (!ok) {
            return init;
        }
        Ast *lit = astI64Type(v);
        lit->type = astTypeCopy(dst);
        return lit;
    }
    if (astIsIntType(dst)) {
        int ok = 1;
        s64 v = evalIntConstExprOrErr(init, &ok);
        if (!ok) {
            return init;
        }
        switch (dst->size) {
            case 1: v = dst->issigned ? (s64)(s8)v  : (s64)(u8)v;  break;
            case 2: v = dst->issigned ? (s64)(s16)v : (s64)(u16)v; break;
            case 4: v = dst->issigned ? (s64)(s32)v : (s64)(u32)v; break;
            default: break;
        }
        Ast *lit = astI64Type(v);
        lit->type = astTypeCopy(dst);
        return lit;
    }
    if (dst->kind == AST_TYPE_FLOAT) {
        int ok = 1;
        double d = evalFloatExprOrErr(init, &ok);
        if (!ok) {
            return init;
        }
        Ast *lit = astF64Type(d);
        lit->type = astTypeCopy(dst);
        return lit;
    }
    return init;
}

/* An initialiser for an inner array (an element of a multi-dimensional
 * array or an array field of a class) must fit it: the extra items would be
 * written over the elements or fields that follow. Fewer items is fine. A
 * string must fit with its NUL, as for a top-level `U8 buf[N] = "..."`.
 * Called right after the list or string has been consumed; `depth` is
 * parseDeclArrayInitList's. */
static void parseCheckInnerArrayInit(Cctrl *cc, AstType *sub_type, Ast *init,
                                     volatile int *depth)
{
    if (sub_type->kind != AST_TYPE_ARRAY || sub_type->len < 0) {
        return;
    }
    s64 count;
    if (init->kind == AST_STRING) {
        count = init->real_len;
    } else if (init->kind == AST_ARRAY_INIT) {
        count = listCount(init->arrayinit);
    } else {
        return;
    }
    if (count > sub_type->len) {
        /* Point at the list's `}` or at the string. A `}` stepped back
         * onto is open again for the recovery's brace count. */
        if (init->kind == AST_ARRAY_INIT) {
            (*depth)++;
        }
        cctrlTokenRewind(cc);
        cctrlRaiseException(cc,
                "Invalid array initializer: expected %d items but got %d",
                sub_type->len, (int)count);
    }
}

static Ast *parseDeclArrayInitList(Cctrl *cc, AstType *type, volatile int *depth);

/* A nested `{ ... }` item of `type`'s list: the next array element or,
 * in a class, field `i`, which must then be an array or a class
 * itself. The `{` is the next token. */
static Ast *parseDeclNestedInit(Cctrl *cc, AstType *type, int is_class,
                                u64 i, volatile int *depth)
{
    AstType *sub_type = type->ptr;
    if (is_class) {
        MapNode *entry = astClassInitFieldAt(type, i);
        if (!entry) {
            cctrlRaiseException(cc,
                    "More initialisers than class fields for class: %s",
                    astTypeToString(type));
        }
        sub_type = entry->value;
        parseCheckPrivateField(cc, sub_type, (char *)entry->key,
                               strlen((char *)entry->key));
        if (sub_type->kind != AST_TYPE_ARRAY &&
            ((sub_type->kind != AST_TYPE_CLASS &&
              sub_type->kind != AST_TYPE_UNION) ||
             sub_type->is_intrinsic))
        {
            cctrlRaiseException(cc,
                    "Cannot use an initialiser list for field %s of type %s",
                    (char *)entry->key, astTypeToString(sub_type));
        }
    } else if (sub_type == NULL) {
        cctrlRaiseException(cc,
                "Cannot use an initialiser list for an element of type %s",
                astTypeToString(type));
    } else if (sub_type->kind != AST_TYPE_ARRAY &&
               ((sub_type->kind != AST_TYPE_CLASS &&
                 sub_type->kind != AST_TYPE_UNION) ||
                sub_type->is_intrinsic))
    {
        /* `I64 a[2] = {1, {2}}`: a scalar element takes a value,
         * a list for it would be stored as its address. */
        cctrlRaiseException(cc,
                "Cannot use an initialiser list for an element of type %s",
                astTypeToString(sub_type));
    }
    Ast *init = parseDeclArrayInitList(cc,sub_type,depth);
    parseCheckInnerArrayInit(cc,sub_type,init,depth);
    return init;
}

/* A value item of `type`'s list, `init`: the next array element or,
 * in a class, field `i`. */
static Ast *parseDeclScalarInit(Cctrl *cc, AstType *type, int is_class,
                                u64 i, volatile int *depth, Ast *init,
                                Lexeme *start)
{
    if (type->ptr) {
        /* an array or a string for a slice element: its view */
        init = parseSliceFrom(cc, type->ptr, init);
        parseResolveOverloadRef(cc, type->ptr, init);
        if (astFnPtrMismatch(type->ptr, init->type)) {
            parseRaiseFnConvert(cc, type->ptr, init->type);
        }
        if ((astGetResultType(AST_BIN_OP_ASSIGN, init->type, type->ptr)) == NULL) {
            cctrlRaiseException(cc,"Incompatiable types: %s %s",
                    astTypeToString(init->type),
                    astTypeToString(type->ptr));
        }
        parseCheckInnerArrayInit(cc,type->ptr,init,depth);
        init = parseFoldInitElement(init, type->ptr);
    } else if (is_class) {
        MapNode *entry = astClassInitFieldAt(type, i);
        if (!entry) {
            cctrlRaiseException(cc,
                    "More initialisers than class fields for class: %s",
                    astTypeToString(type));
        }
        AstType *cls_field_type = entry->value;
        parseCheckPrivateField(cc, cls_field_type, (char *)entry->key,
                               strlen((char *)entry->key));
        init = parseSliceFrom(cc, cls_field_type, init);
        if (init->kind == AST_STRING &&
            cls_field_type->kind == AST_TYPE_ARRAY &&
            cls_field_type->ptr->kind == AST_TYPE_CHAR)
        {
            /* A `U8 name[8]` field takes the string's bytes. */
            parseCheckInnerArrayInit(cc,cls_field_type,init,depth);
        } else {
            parseTypeCheckClassFieldInitaliser(cc,cls_field_type,init,start);
        }
        init = parseFoldInitElement(init, cls_field_type);
    }
    return init;
}

/* How many items an array or class `type` takes without its braces
 * (brace elision, as in C: `L l = {1, 2, 3, 4}` is `{1, {2, 3}, 4}`)
 * when `init` is the first of them; 0 when `init` is a value for the
 * whole of `type` - a string for a char array, a class of its own
 * type - or `type` takes no list: a scalar, a slice, an intrinsic
 * class, an unsized array. */
static s64 parseDeclElidedCount(AstType *type, Ast *init) {
    /* A slice takes one value (a slice, an array or a string, converted),
     * never its pointer and length as two items */
    if (astTypeIsSlice(type)) {
        return 0;
    }
    if (type->kind == AST_TYPE_ARRAY) {
        if (type->len <= 0 || !type->ptr ||
            (init->kind == AST_STRING && type->ptr->kind == AST_TYPE_CHAR)) {
            return 0;
        }
        return type->len;
    }
    if ((type->kind == AST_TYPE_CLASS || type->kind == AST_TYPE_UNION) &&
        !type->is_intrinsic && type->fields)
    {
        AstType *init_type = init->type;
        if (init_type && init_type->kind == type->kind &&
            (init_type->fields == type->fields ||
             (init_type->clsname && type->clsname &&
              aoStrCmp(init_type->clsname, type->clsname)))) {
            return 0;
        }
        s64 count = 0;
        while (astClassInitFieldAt(type, count)) {
            count++;
        }
        return count;
    }
    return 0;
}

static Ast *parseDeclElidedInitList(Cctrl *cc, AstType *type, s64 count,
                                    volatile int *depth, Ast *first,
                                    Lexeme *first_start);

/* Item `i` of `type`'s list, not a braced one: its value, or the
 * items of an array or class element/field written without braces.
 * `init` is its first expression, already parsed. */
static Ast *parseDeclValueInit(Cctrl *cc, AstType *type, int is_class,
                               u64 i, volatile int *depth, Ast *init,
                               Lexeme *start)
{
    AstType *sub_type = is_class ? astClassFieldAt(type, i) : type->ptr;
    s64 count = sub_type ? parseDeclElidedCount(sub_type, init) : 0;
    if (count > 0) {
        return parseDeclElidedInitList(cc,sub_type,count,depth,init,start);
    }
    return parseDeclScalarInit(cc,type,is_class,i,depth,init,start);
}

/* The next item's expression, the item's first token being a value,
 * which is copied to `start` */
static Ast *parseDeclInitExpr(Cctrl *cc, Lexeme *start) {
    *start = *cctrlTokenPeek(cc);
    Ast *init = parseExpr(cc,16);
    if (init == NULL) {
        cctrlRaiseExceptionFromTo(cc,NULL,'{','}',"Array initaliser encountered an unexpected token");
    }
    return init;
}

/* The items of an array or class written without its braces, `first`
 * (parsed) being the first: at most `count`, stopping early at the `}`
 * of the list they are in, which is left for it. Each is braced, a
 * value, or elided again, as in a braced list, and they make the same
 * list. */
static Ast *parseDeclElidedInitList(Cctrl *cc, AstType *type, s64 count,
                                    volatile int *depth, Ast *first,
                                    Lexeme *first_start)
{
    List *initlist = listNew();
    int is_class = type->kind == AST_TYPE_CLASS ||
                   type->kind == AST_TYPE_UNION;
    Lexeme *tok;
    Ast *init;

    for (s64 i = 0; i < count; ++i) {
        if (i == 0) {
            init = parseDeclValueInit(cc,type,is_class,i,depth,first,
                                      first_start);
        } else {
            tok = cctrlTokenGetRequired(cc);
            cctrlTokenRewind(cc);
            if (tokenPunctIs(tok, '}')) {
                break;
            }
            if (tokenPunctIs(tok, '.')) {
                cctrlRaiseException(cc,
                    "Designated initialisers ('.field = value') are not "
                    "supported; use a positional initialiser list");
            }
            if (tokenPunctIs(tok, '{')) {
                init = parseDeclNestedInit(cc,type,is_class,i,depth);
            } else {
                Lexeme start;
                init = parseDeclInitExpr(cc,&start);
                init = parseDeclValueInit(cc,type,is_class,i,depth,init,
                                          &start);
            }
        }
        listAppend(initlist,init);
        tok = cctrlTokenGet(cc);
        if (!tokenPunctIs(tok, ',')) {
            cctrlTokenRewind(cc);
        }
    }
    return astArrayInit(initlist);
}

/* `depth` counts the `{` of the initialiser consumed and not yet closed,
 * for the recovery in parseDeclArrayInitInt. */
static Ast *parseDeclArrayInitList(Cctrl *cc, AstType *type, volatile int *depth) {
    Lexeme *tok = cctrlTokenGetRequired(cc);
    List *initlist;
    Ast *init;

    if (type->ptr && type->ptr->kind == AST_TYPE_CHAR && tok->tk_type == TK_STR) {
        return cctrlGetOrSetString(cc,tok->start,tok->len,tok->i64);
    }

    if (!tokenPunctIs(tok, '{')) {
        /* Back onto the token so the diagnostic points at it */
        cctrlTokenRewind(cc);
        cctrlRaiseException(cc,
                "Expected initializer list starting with '{', got `%s`",
                lexemeAsWritten(tok));
    }

    initlist = listNew();
    u64 i = 0;
    /* A class or union: the items set its fields in order (see
     * astClassInitFieldAt). */
    int is_class = type->kind == AST_TYPE_CLASS ||
                   type->kind == AST_TYPE_UNION;

    /* A class that was only forward declared (`class Foo;`) has no
     * fields to match the initialisers against. Checked through any
     * array dimensions so `Foo a[2] = {{..},{..}}` is reported at the
     * outer `{` and the recovery skips the whole initialiser. */
    AstType *elem_type = type;
    while (elem_type->kind == AST_TYPE_ARRAY && elem_type->ptr) {
        elem_type = elem_type->ptr;
    }
    if ((elem_type->kind == AST_TYPE_CLASS ||
         elem_type->kind == AST_TYPE_UNION) && elem_type->fields == NULL &&
        !elem_type->is_intrinsic) {
        cctrlTokenRewind(cc);
        cctrlRaiseException(cc,"Cannot use an initialiser list, %s is incomplete",
                astTypeToString(elem_type));
    }
    (*depth)++;

    while (1) {
        tok = cctrlTokenGetRequired(cc);
        if (tokenPunctIs(tok, '}')) {
            (*depth)--;
            break;
        }
        /* C99 designated initialisers (`.field = value`) are not part of
         * ktm; reject them with a clear message instead of feeding the
         * leading `.` into parseExpr (which crashes on the dangling
         * member access). */
        if (tokenPunctIs(tok, '.')) {
            cctrlRaiseException(cc,
                "Designated initialisers ('.field = value') are not "
                "supported; use a positional initialiser list");
        }
        cctrlTokenRewind(cc);
        if (tokenPunctIs(tok,'{')) {
            init = parseDeclNestedInit(cc,type,is_class,i,depth);
        } else {
            Lexeme start;
            init = parseDeclInitExpr(cc,&start);
            init = parseDeclValueInit(cc,type,is_class,i,depth,init,&start);
        }
        if (is_class) {
            i++;
        }
        listAppend(initlist,init);

        /* The `,` is optional; anything else is the next item's */
        tok = cctrlTokenGet(cc);
        if (!tokenPunctIs(tok, ',')) {
            cctrlTokenRewind(cc);
        }
    }
    return astArrayInit(initlist);
}

/* Parse a `{ ... }` initialiser (or a string for a char array). An error
 * inside it is reported once: the per-statement / top-level recovery
 * syncs to the next `;` or `}` at its own brace depth, which inside a
 * nested list is the list's `}`, and then reports what follows as a
 * second, bogus error. So skip to the end of the whole initialiser
 * first and hand the error on from there. */
Ast *parseDeclArrayInitInt(Cctrl *cc, AstType *type) {
    jmp_buf init_recovery;
    jmp_buf *outer_recovery = cc->current_recovery;
    volatile int depth = 0;
    Ast *init;

    if (!outer_recovery) {
        return parseDeclArrayInitList(cc,type,&depth);
    }

    cc->current_recovery = &init_recovery;
    if (setjmp(init_recovery) != 0) {
        cc->current_recovery = outer_recovery;
        Lexeme *tok;
        while (depth > 0 && (tok = cctrlTokenGet(cc)) != NULL) {
            if (tokenPunctIs(tok,'{')) {
                depth++;
            } else if (tokenPunctIs(tok,'}')) {
                depth--;
            }
        }
        cctrlTerminate(cc);
    }
    init = parseDeclArrayInitList(cc,type,&depth);
    cc->current_recovery = outer_recovery;
    return init;
}

void parseFlattenAnnonymous(AstType *anon,
                            Map *fields_dict,
                            int offset,
                            int make_copy)
{
    MapIter it;
    mapIterInit(anon->fields, &it);
    while (mapIterNext(&it)) {
        MapNode *n = it.node;
        AstType *base = (AstType *)n->value;
        AstType *type = NULL;
        if (make_copy) {
            type = astTypeCopy(base);
        } else {
            type = base;
        }
        type->offset += offset;

        mapAdd(fields_dict,n->key,type);
    }
}

typedef struct ClsField {
    AstType *type;
    AoStr *field_name;
} ClsField;

ClsField *clsFieldNew(AstType *type, AoStr *field_name) {
    ClsField *f = (ClsField *)malloc(sizeof(ClsField));
    f->type = type;
    f->field_name = field_name;
    return f;
}

int parse_expand_generated = 0;
static void parseJsonGenerate(Cctrl *cc, AstType *cls, Lexeme *at);

/* ---- attributes (v0.14.0) ----
 * `@json @yaml struct User { @key("e-mail") String email; ... };`: a
 * struct's come before its declaration (and before `pub`), a field's
 * before the field. Which ones there are and where they go: */
static const char *parse_struct_attrs[] = {"json", "yaml", NULL};
static const char *parse_field_attrs[] = {"key", "skip", "omitEmpty", "env", "required",
        "min", "max", "email", "url", "oneOf", NULL};

/* Attributes config and validate read: on a field of any struct */
static const char *parse_any_struct_attrs[] = {"key", "skip", "env", "required", "min", "max",
        "email", "url", "oneOf", NULL};

static int parseAttrIn(const char **names, AoStr *name) {
    for (int i = 0; names[i]; ++i) {
        if (!strcmp(names[i], name->data)) return 1;
    }
    return 0;
}

/* The attributes from `at` (the `@`, taken) on: `@name` or
 * `@name("text")`, any number of them. Each is checked for where it is,
 * a struct's (on_field 0) or a field's (1). */
Vec *parseAttrs(Cctrl *cc, Lexeme *at, int on_field) {
    Vec *attrs = vecNew(&vec_unsigned_long_type);
    while (1) {
        Lexeme *name = cctrlTokenGet(cc);
        if (!name || name->tk_type != TK_IDENT) {
            cctrlRaiseExceptionAt(cc, at->line, at->col, 1,
                    "expected an attribute name after `@`, as in @json");
        }
        AstAttr *a = calloc(1, sizeof(AstAttr));
        a->name = aoStrDupRaw(name->start, name->len);
        a->line = at->line;
        a->col = at->col;
        int len = name->len + 1;
        int is_num = !strcmp(a->name->data, "min") || !strcmp(a->name->data, "max");
        int is_list = !strcmp(a->name->data, "oneOf");
        if (tokenPunctIs(cctrlTokenPeek(cc), '(') && is_num) {
            /* @min(n), @max(n): a number, maybe negative */
            cctrlTokenGet(cc);
            Lexeme *arg = cctrlTokenGet(cc);
            int neg = arg && tokenPunctIs(arg, '-');
            if (neg) arg = cctrlTokenGet(cc);
            if (!arg || (arg->tk_type != TK_I64 && arg->tk_type != TK_F64) ||
                !tokenPunctIs(cctrlTokenPeek(cc), ')')) {
                cctrlRaiseExceptionAt(cc, a->line, a->col, len,
                        "`@%s(...)` takes a number: @%s(1)", a->name->data, a->name->data);
            }
            cctrlTokenGet(cc);
            a->num = arg->tk_type == TK_I64 ? (double)arg->i64 : arg->f64;
            if (neg) a->num = -a->num;
            a->arg = aoStrPrintf("%s%.*s", neg ? "-" : "", arg->len, arg->start);
        } else if (tokenPunctIs(cctrlTokenPeek(cc), '(')) {
            /* @name("text"); @oneOf("a", "b", ...) takes several, kept
             * joined by '|' */
            cctrlTokenGet(cc);
            while (1) {
                Lexeme *arg = cctrlTokenGet(cc);
                if (!arg || arg->tk_type != TK_STR ||
                    !(tokenPunctIs(cctrlTokenPeek(cc), ')') ||
                      (is_list && tokenPunctIs(cctrlTokenPeek(cc), ',')))) {
                    if (is_list) {
                        cctrlRaiseExceptionAt(cc, a->line, a->col, len,
                                "`@oneOf(...)` takes strings: @oneOf(\"draft\", \"published\")");
                    }
                    cctrlRaiseExceptionAt(cc, a->line, a->col, len,
                            "`@%s(...)` takes one string: @%s(\"text\")",
                            a->name->data, a->name->data);
                }
                if (is_list && (arg->len == 0 || memchr(arg->start, '|', arg->len))) {
                    cctrlRaiseExceptionAt(cc, a->line, a->col, len,
                            "`@oneOf`: each value is a non-empty string without '|'");
                }
                if (!a->arg) {
                    a->arg = aoStrDupRaw(arg->start, arg->len);
                } else {
                    aoStrPutChar(a->arg, '|');
                    aoStrCatLen(a->arg, arg->start, arg->len);
                }
                if (tokenPunctIs(cctrlTokenGet(cc), ')')) break;
            }
        }

        const char **here = on_field ? parse_field_attrs : parse_struct_attrs;
        const char **there = on_field ? parse_struct_attrs : parse_field_attrs;
        if (!parseAttrIn(here, a->name)) {
            if (parseAttrIn(there, a->name)) {
                cctrlRaiseExceptionAt(cc, a->line, a->col, len,
                        "`@%s` goes on a %s, not a %s", a->name->data,
                        on_field ? "struct" : "field", on_field ? "field" : "struct");
            }
            cctrlRaiseExceptionAt(cc, a->line, a->col, len,
                    "unknown attribute `@%s`: a struct takes @json and @yaml, "
                    "a field @key, @skip, @omitEmpty, @env, @required, @min, @max, "
                    "@email, @url and @oneOf", a->name->data);
        }
        int is_env = !strcmp(a->name->data, "env");
        int wants_arg = !strcmp(a->name->data, "key") || is_env || is_num || is_list;
        if (wants_arg && (!a->arg || a->arg->len == 0)) {
            if (is_num) {
                cctrlRaiseExceptionAt(cc, a->line, a->col, len,
                        "`@%s` takes a number: @%s(1)", a->name->data, a->name->data);
            }
            if (is_list) {
                cctrlRaiseExceptionAt(cc, a->line, a->col, len,
                        "`@oneOf` takes the allowed values: @oneOf(\"draft\", \"published\")");
            }
            if (is_env) {
                cctrlRaiseExceptionAt(cc, a->line, a->col, len,
                        "`@env` takes the variable's name as a string: @env(\"PORT\")");
            }
            cctrlRaiseExceptionAt(cc, a->line, a->col, len,
                    "`@key` takes the field's key as a string: @key(\"e-mail\")");
        }
        if (wants_arg && !is_num && memchr(a->arg->data, '\\', a->arg->len)) {
            cctrlRaiseExceptionAt(cc, a->line, a->col, len,
                    "`@%s`: write the %s without escapes", a->name->data,
                    is_env ? "name" : is_list ? "values" : "key");
        }
        if (!wants_arg && a->arg) {
            cctrlRaiseExceptionAt(cc, a->line, a->col, len,
                    "`@%s` takes no arguments", a->name->data);
        }
        if (astAttrGet(attrs, a->name->data)) {
            cctrlRaiseExceptionAt(cc, a->line, a->col, len,
                    "`@%s` is here twice", a->name->data);
        }
        vecPush(attrs, a);

        if (!tokenPunctIs(cctrlTokenPeek(cc), '@')) break;
        at = cctrlTokenGet(cc);
    }
    if (on_field && astAttrGet(attrs, "skip") && attrs->size > 1) {
        AstAttr *a = astAttrGet(attrs, "skip");
        cctrlRaiseExceptionAt(cc, a->line, a->col, 5,
                "a `@skip` field is never written or read: it takes no other attribute");
    }
    return attrs;
}

/* A field's attribute on a struct without @json or @yaml does nothing
 * (but config's and validate's): an error, at the attribute */
static void parseCheckFieldAttrs(Cctrl *cc, Vec *cls_attrs, AoStr *tag, List *fields) {
    if (cls_attrs && cls_attrs->size) return;
    listForEach(fields) {
        ClsField *f = it->value;
        if (!f->type || !f->type->field_attrs) continue;
        /* config's, validate's and @key/@skip (reflect reads them: ktm-pg's
         * columns) go on any struct; only @omitEmpty needs @json or @yaml */
        for (u64 i = 0; i < f->type->field_attrs->size; ++i) {
            AstAttr *a = f->type->field_attrs->entries[i];
            if (parseAttrIn(parse_any_struct_attrs, a->name)) continue;
            cctrlRaiseExceptionAt(cc, a->line, a->col, a->name->len + 1,
                    "`@%s` on a field of %s, which has neither @json nor @yaml",
                    a->name->data, tag ? tag->data : "an anonymous struct");
        }
    }
}

u32 CalcUnionSize(List *fields);

List *parseClassOrUnionFields(Cctrl *cc, AoStr *name,
        u32 (*computeSize)(List *), u32 *_size)
{
    u32 size;
    char *fnptr_name;
    int fnptr_name_len;
    Lexeme *tok, *tok_name;
    AstType *next_type = NULL, *base_type = NULL, *field_type = NULL;
    AoStr  *field_name = NULL;
    List *fields_list;

    tok = cctrlTokenGetRequired(cc);

    if (!tokenPunctIs(tok,'{')) {
        cctrlTokenRewind(cc);
        return NULL;
    }

    fields_list = listNew();
    /* For the diagnostics; an anonymous class/union has no tag. */
    char *cls_str = name ? name->data : "<anonymous>";
    char *kind_str = computeSize == CalcUnionSize ? "union" : "struct";

    /* Per-field recovery point, like the per-statement one in
     * parseCompoundStatementInternal: a bad field is reported, the
     * tokens are skipped to the next `;` (or the closing `}`) and
     * parsing carries on. The class is then completed with the fields
     * that did parse instead of being left registered without a field
     * map, which crashed the first member access. */
    jmp_buf field_recovery;
    jmp_buf *outer_recovery = cc->current_recovery;
    Map *volatile class_scope = cc->localenv;
    cc->current_recovery = &field_recovery;

    while (1) {
        if (setjmp(field_recovery) != 0) {
            cc->localenv = class_scope;
            cctrlSyncStatement(cc);
        }
        /* Peek not a consume */
        tok_name = cctrlTokenPeek(cc);
        if (tok_name == NULL) {
            cc->current_recovery = outer_recovery;
            cctrlRaiseException(cc,
                    "Unexpected end of input in class/union body");
        }
        /* `@key("x") T name;`: the field's attributes */
        Vec *field_attrs = NULL;
        if (tokenPunctIs(tok_name, '@')) {
            Lexeme *at = cctrlTokenGet(cc);
            if (computeSize == CalcUnionSize) {
                cctrlRaiseExceptionAt(cc, at->line, at->col, 1,
                        "attributes go on the fields of a struct, not a union");
            }
            field_attrs = parseAttrs(cc, at, 1);
            tok_name = cctrlTokenPeek(cc);
            if (tok_name == NULL) {
                cc->current_recovery = outer_recovery;
                cctrlRaiseException(cc,
                        "Unexpected end of input in class/union body");
            }
        }
        /* `private T name;`: only the class's module can use the field */
        int is_private = 0;
        if (tok_name->tk_type == TK_KEYWORD &&
            (tok_name->i64 == KW_PRIVATE || tok_name->i64 == KW_PUBLIC))
        {
            Lexeme *vis = cctrlTokenGet(cc);
            if (vis->i64 == KW_PUBLIC) {
                cctrlRaiseExceptionAt(cc, vis->line, vis->col, vis->len,
                        "a field is public unless it is marked `private`");
            }
            if (!cc->cur_module) {
                cctrlRaiseExceptionAt(cc, vis->line, vis->col, vis->len,
                        "`private` fields are for the structs of a module: "
                        "outside one there is nothing to hide them from");
            }
            is_private = 1;
            tok_name = cctrlTokenPeek(cc);
            if (tok_name == NULL) {
                cc->current_recovery = outer_recovery;
                cctrlRaiseException(cc,
                        "Unexpected end of input in class/union body");
            }
        }
        /* `scoped T name;` frees a local at the end of its scope; a field
         * has no scope of its own to end */
        if (parseScopedWord(tok_name)) {
            Lexeme *next = cctrlTokenPeekBy(cc, 1);
            if (next && next->tk_type != TK_PUNCT && cctrlTokIsKeyword(cc, next)) {
                cctrlRaiseExceptionAt(cc, tok_name->line, tok_name->col, tok_name->len,
                        "`%.*s` goes on a local variable, not a %s field: free "
                        "the field where the %s is freed", tok_name->len, tok_name->start,
                        kind_str, kind_str);
            }
        }
        if (tokenPunctIs(tok_name,'}')) {
            break;
        } else if (cctrlTokIsKeyword(cc, tok_name)) {
            base_type = parseBaseDeclSpec(cc);
        } else if (tokenMayStartFnType(tok_name) && parseFnLooksLikeType(cc)) {
            base_type = parseBaseDeclSpec(cc);
        } else if (name && !memcmp(name->data,tok_name->start,tok_name->len)) {
            base_type = parseBaseDeclSpec(cc);
            if (base_type == NULL) {
                base_type = astClassType(NULL, aoStrDup(name),8,0);
            }
        } else if (tok_name->tk_type == TK_KEYWORD) { 
            switch (tok_name->i64) {
                case KW_UNION:
                case KW_CLASS: {
                    /* A nested class or union, tagged or not. Without a
                     * field name its fields are used as this class's own;
                     * with one (`union U { ... } u;`) it is the type of
                     * the fields that follow. */
                    int is_union = tok_name->i64 == KW_UNION;
                    cctrlTokenGet(cc);
                    AstType *nested = is_union ? parseUnionDef(cc)
                                               : parseClassDef(cc,NULL);
                    if (tokenPunctIs(cctrlTokenPeek(cc),';')) {
                        if (!nested->fields) {
                            cctrlRaiseException(cc,"%s %s has no fields, expected a body or a field name in %s %s",
                                    is_union ? "union" : "struct",
                                    nested->clsname ? nested->clsname->data : "<anonymous>",
                                    kind_str, cls_str);
                        }
                        cctrlTokenGet(cc);
                        listAppend(fields_list, clsFieldNew(nested,NULL));
                        continue;
                    }
                    base_type = nested;
                    break;
                }
                case KW_CONST:
                    cctrlRaiseException(cc,"const is only allowed on declarations");
                default:
                    cctrlRaiseException(cc,"Unexpected keyword: `%.*s` while parsing %s %s",
                            tok_name->len,
                            tok_name->start,
                            kind_str, cls_str);
            }
        } else {
            cctrlRaiseException(cc,"Unexpected type `%.*s` while parsing %s %s",
                    tok_name->len, tok_name->start, kind_str, cls_str);
        }

        while (1) {
            next_type = parsePointerType(cc,base_type);
            tok_name = cctrlTokenGet(cc);
            if (!tok_name) {
                /* Nothing left to recover into; report it once. */
                cc->current_recovery = outer_recovery;
                cctrlRaiseException(cc, "Unexpected end of input while parsing %s %s",
                        kind_str, cls_str);
            }
            if (tok_name->tk_type != TK_IDENT && !tokenPunctIs(tok_name, '(')) {
                /* Back onto the bad token so the diagnostic points at it
                 * and the recovery skips from there. */
                cctrlTokenRewind(cc);
                cctrlRaiseException(cc, "Unexpected character `%.*s` while parsing %s %s",
                        tok_name->len,tok_name->start,kind_str,cls_str);
            } else if (tokenPunctIs(tok_name, '(')) {
                next_type = parseFunctionPointerType(cc,&fnptr_name,&fnptr_name_len,next_type);
                field_name = aoStrDupRaw(fnptr_name,fnptr_name_len);
            } else  {
                /* XXX: this does not work properly for classes which are not 
                 * pointers as we miss one of the strings */
                next_type = parseArrayDimensions(cc,next_type);
                field_name = aoStrDupRaw(tok_name->start,tok_name->len);
            }

            /* A field can hold a class by value only once that class is
             * complete: before, its size isn't known (`class B;` alone,
             * or in a module a type of a later file, see
             * parseModuleTypes). A pointer to it is fine. */
            AstType *by_value = next_type;
            while (by_value && by_value->kind == AST_TYPE_ARRAY) by_value = by_value->ptr;
            if (by_value && !by_value->is_intrinsic &&
                !by_value->fields &&
                (by_value->kind == AST_TYPE_CLASS || by_value->kind == AST_TYPE_UNION))
            {
                cctrlRewindUntilStrMatch(cc, tok_name->start, tok_name->len, NULL);
                cctrlRaiseException(cc, "field `%.*s` of %s holds a %s, which is not "
                        "complete here: use a pointer, or a type that doesn't "
                        "contain %s", (int)field_name->len, field_name->data,
                        cls_str, astTypeToString(by_value), cls_str);
            }

            field_type = astMakeClassField(next_type, 0);
            /* Field provenance for jump-to-definition: the name token
             * is in hand ('(' for fn-pointer fields - same line). */
            field_type->line = tok_name->line;
            field_type->col = tok_name->col;
            field_type->file_id = ast_file_hint;
            if (next_type && next_type->clsname) {
                field_type->clsname = next_type->clsname;
            }
            if (is_private) {
                field_type->private_module = cc->cur_module;
                field_type->private_of = name;
            }
            if (field_attrs) {
                AstAttr *a = field_attrs->entries[0];
                if (is_private) {
                    int cfg = parseAttrIn(parse_any_struct_attrs, a->name);
                    cctrlRaiseExceptionAt(cc, a->line, a->col, a->name->len + 1, cfg
                            ? "`@%s` on a private field: config and validate never read private fields"
                            : "`@%s` on a private field: json and yaml never write "
                              "or read private fields", a->name->data);
                }
                if (astAttrGet(field_attrs, "key") &&
                    tokenPunctIs(cctrlTokenPeek(cc), ','))
                {
                    cctrlRaiseExceptionAt(cc, a->line, a->col, a->name->len + 1,
                            "`@key` names one field: give each field its own declaration");
                }
                field_type->field_attrs = field_attrs;
            }
            /* The list is here to ease calculating the offset of the class 
             * fields as they have been defined by the programmer... Hashing 
             * loses the ordering.*/
            listAppend(fields_list,clsFieldNew(field_type,field_name));

            tok = cctrlTokenGet(cc);
            if (tokenPunctIs(tok, ',')) {
                continue;
            } else if (tokenPunctIs(tok,';')) {
                break;
            } else if (!tok) {
                cc->current_recovery = outer_recovery;
                cctrlRaiseException(cc, "Unexpected end of input while parsing %s %s",
                        kind_str, cls_str);
            } else {
                cctrlTokenRewind(cc);
                cctrlRaiseException(cc, "Unexpected token '%.*s' while parsing %s %s, perhaps you meant to terminate the field with `;`?",
                        tok->len, tok->start, kind_str, cls_str);
           }
        }
    }
    cc->current_recovery = outer_recovery;
    cctrlTokenExpect(cc,'}');
    size = 0;
    size = computeSize(fields_list);

    if (_size) {
        *_size = size;
    }
    return fields_list;
}

u32 CalcUnionSize(List *fields) {
    int max = 0;
    AstType *type;
    listForEach(fields) {
        ClsField *cls_field = (ClsField *)it->value;
        type = cls_field->type;
        if (max < type->size) {
            max = type->size;
        }
    }
    return max;
}

u32 CalcClassSize(List *fields) {
    u32 offset = 0;
    u32 size = 0;
    listForEach(fields) {
        ClsField *cls_field = (ClsField *)it->value;
        AstType *type = cls_field->type;
        if (type->size < MAX_ALIGN) size = type->size;
        else                        size = MAX_ALIGN;

        if (size == 0) continue;
        if (offset % size != 0) {
            offset += size - offset % size;
        }

        type->offset = offset;
        offset += type->size;
    }
    return offset;
}

int CalcPadding(int offset, int size) {
    /* Zero-size fields (e.g. trailing `U0 body;` placeholders used as
     * "rest of bytes" markers) need no padding. Guard before `% size`
     * so we don't depend on platform UB for `% 0` (x86 traps, ARM
     * returns the dividend - which here would roll `offset` back to 0
     * and zero out the whole class size). */
    if (size == 0) return 0;
    return offset % size == 0 ? 0 : size - offset % size;
}

Map *parseClassOffsets(Cctrl *cc,
                       int *aligned_size,
                       int *out_align,
                       List *fields,
                       AstType *base_class,
                       AoStr *clsname,
                       AstType *intrinsic_base)
{
    int offset;
    AstType *field;
    Map *fields_dict = astTypeMapNew();
    int max_align = 1;

    if (base_class) {
        offset = base_class->size;
        int ba = astTypeAlign(base_class);
        if (ba > max_align) max_align = ba;
        parseFlattenAnnonymous(base_class,fields_dict,0,1);
    } else {
        offset = 0;
    }

    /* A derived class with no fields of its own is just the base class,
     * flattened above. Without a base this assumes the class definition
     * will be made later. */
    if (listEmpty(fields)) {
        *out_align = max_align;
        *aligned_size = offset + CalcPadding(offset, max_align);
        return fields_dict;
    }

    /* An intrinsic class (`I64 class CDate { U32 time; I32 date; };`) is
     * a value of its base integer type whose fields overlay its bytes:
     * it has the base type's size and alignment, and the
     * fields are packed from offset 0. The backends move these values in
     * one integer register, so the fields have to fit inside it. */
    if (intrinsic_base) {
        int base_size = intrinsic_base->size;
        *aligned_size = base_size;
        *out_align = base_size;
        listForEach(fields) {
            ClsField *cls_field = (ClsField *)it->value;
            field = cls_field->type;
            field->offset = offset;
            offset+=field->size;
            if (cls_field->field_name) {
                mapAdd(fields_dict,cls_field->field_name->data,field);
            }
            free(cls_field);
        }

        /* Reported without unwinding: the class still gets its field
         * map, so later uses of it don't cascade into "X is incomplete"
         * errors. The error stops the compile once parsing ends. */
        if (offset > base_size) {
            AoStr *msg = cctrlMessagePrintF(cc, CCTRL_ERROR,
                    "Fields of intrinsic class %s take %d bytes, more than "
                    "the %d bytes of its base type %s",
                    clsname ? clsname->data : "<anonymous>",
                    offset, base_size, astTypeToString(intrinsic_base));
            cctrlDiagPush(cc, cctrlMakeDiag(cc, CCTRL_ERROR, msg, NULL));
        }

        return fields_dict;
    }

    listForEach(fields) {
        ClsField *cls_field = (ClsField *)it->value;
        field = cls_field->type;
        AoStr *field_name = cls_field->field_name;
        int fa = astTypeAlign(field);

        if (field_name == NULL && parseIsClassOrUnion(field->kind)) {
            offset += CalcPadding(offset, fa);
            /* A tagged one is also a type of its own: copy its fields
             * rather than moving them. */
            parseFlattenAnnonymous(field,fields_dict,offset,field->clsname != NULL);
            offset += field->size;
        } else {
            if (field->kind == AST_TYPE_POINTER &&
                    (field->ptr->kind == AST_TYPE_CLASS || field->ptr->kind == AST_TYPE_UNION)) {
                if (clsname && field->ptr->clsname) {
                    if (aoStrCmp(field->ptr->clsname, clsname)) {
                        field->fields = fields_dict;
                    }
                }
            }

            offset += CalcPadding(offset, fa);
            field->offset = offset;
            offset += field->size;
        }

        if (fa > max_align) max_align = fa;
        if (field_name) {
            mapAdd(fields_dict,field_name->data,field);
        }
        free(cls_field);
    }

    *out_align = max_align;
    *aligned_size = offset + CalcPadding(offset, max_align);
    return fields_dict;
}

Map *parseUnionOffsets(Cctrl *cc, int *real_size, int *out_align, List *fields) {
    (void)cc;
    int max_size = 0, max_align = 1;
    AstType *field;
    Map *fields_dict = astTypeMapNew();
    int is_first = 1;

    listForEach(fields) {
        ClsField *cls_field = (ClsField *)it->value;
        field = cls_field->type;
        AoStr *field_name = cls_field->field_name;
        int fa = astTypeAlign(field);
        if (fa > max_align) max_align = fa;
        if (max_size < field->size) {
            max_size = field->size;
        }
        if (field_name == NULL && parseIsClassOrUnion(field->kind)) {
            /* A tagged one is also a type of its own: flatten copies of
             * its fields so marking them below doesn't change it. */
            Map *flat = astTypeMapNew();
            parseFlattenAnnonymous(field,flat,0,field->clsname != NULL);
            /* Only the first member takes an initialiser item; for an
             * anonymous class or union that is all of its fields. */
            MapIter mi;
            mapIterInit(flat, &mi);
            while (mapIterNext(&mi)) {
                AstType *flat_field = (AstType *)mi.node->value;
                if (!is_first) flat_field->init_skip = 1;
                mapAdd(fields_dict,mi.node->key,flat_field);
            }
            is_first = 0;
            continue;
        }

        field->offset = 0;
        field->init_skip = !is_first;
        is_first = 0;
        if (field_name) {
            mapAdd(fields_dict,field_name->data,field);
        }
    }
    *out_align = max_align;
    *real_size = max_size + CalcPadding(max_size, max_align);
    return fields_dict;
}

static void parseGenericSkipParams(Cctrl *cc);

AstType *parseClassOrUnion(Cctrl *cc, Map *env,
        int is_class,
        u32 (*computeSize)(List *),
        AstType *intrinsic_base)
{
    int is_intrinsic = intrinsic_base != NULL;
    AoStr *tag = NULL;
    int aligned_size = 0;
    int aligned = 1;
    u32 class_size;
    Lexeme *tok = cctrlTokenGet(cc);
    AstType *prev = NULL, *ref = NULL, *base_class = NULL;
    List *fields = NULL;
    Map *fields_dict;
    /* `@json @yaml` before this declaration are its own; a struct nested
     * in its body has none */
    Vec *attrs = cc->pending_attrs;
    cc->pending_attrs = NULL;
    cc->localenv = cctrlCreateAstMap(cc->localenv);

    if (tok == NULL) {
        cctrlRaiseException(cc,
                "Unexpected end of input in class/union definition");
    }
    /* Provenance of the tag token, for jump-to-definition. */
    int tag_line = 0, tag_col = 0;
    u32 tag_file = 0;
    Lexeme *tag_tok = tok;
    if (tok->tk_type == TK_IDENT) {
        /* A module's type is named `a.b.Name` */
        char *qual = cctrlDeclName(cc, tok->start, tok->len);
        tag = qual ? aoStrDupRaw(qual, strlen(qual))
                   : aoStrDupRaw(tok->start,tok->len);
        /* the generic struct instance being parsed: `Vec<i64>` */
        if (cc->inst && cc->inst->def->kind == GEN_STRUCT &&
            tokenPunctIs(cctrlTokenPeek(cc), '<')) {
            parseGenericSkipParams(cc);
            tag = cc->inst->name;
        }
        tag_line = tok->line;
        tag_col = tok->col;
        tag_file = ast_file_hint;

        tok = cctrlTokenGet(cc);
        if (tokenPunctIs(tok, ':')) { // Class inheritance
            if (!is_class) {
                cctrlRaiseException(cc,"Cannot use inheritance with a union");
            }
            tok = cctrlTokenGetRequired(cc);
            if (tok->tk_type != TK_IDENT) {
                cctrlRaiseException(cc, "Expected Identifier for class inheritance");
            }
            base_class = mapGetLen(cc->clsdefs,tok->start,tok->len);
            if (base_class == NULL) {
                char *qual_base = cctrlQualifyName(cc, tok->start, tok->len);
                if (qual_base) base_class = mapGet(cc->clsdefs, qual_base);
            }
            if (base_class == NULL) {
                cctrlRaiseException(cc,"class %.*s has not been defined\n",
                        tok->len,tok->start);
            }
            /* a generic base: `: Vec<T>` */
            if (base_class->generic && !base_class->generic_args) {
                Lexeme *base_tok = tok;
                Vec *args = parseGenericArgs(cc);
                base_class = parseGenericStructInstance(cc, base_class, args, base_tok);
            }

            if (tokenPunctIs(cctrlTokenPeek(cc),',')) {
                cctrlRaiseException(cc,"Only one base class allowed at this time");
            }
        } else {
            cctrlTokenRewind(cc);
        }
    } else {
        /* No tag: the body (or a field name) comes next. */
        cctrlTokenRewind(cc);
    }

    if (tag) {
        if (memchr(tag->data, '.', tag->len)) {
            if (cc->decl_pub) setAdd(cc->pub_names, tag->data);
        } else if (cc->decl_from_main && !cc->cur_module) {
            setAdd(cc->main_names, tag->data);
        }
        prev = mapGetLen(env, tag->data, tag->len);
        if (prev && prev->is_enum) {
            cctrlRaiseException(cc,"`%s` has already been defined", tag->data);
        }
        /* A module's full pass: the declarations pass defined it */
        if (prev && prev->fields && cc->cur_module && !cc->decls_only &&
            tokenPunctIs(cctrlTokenPeek(cc), '{'))
        {
            int depth = 0;
            Lexeme *t;
            while ((t = cctrlTokenGet(cc)) != NULL) {
                if (tokenPunctIs(t, '{')) depth++;
                else if (tokenPunctIs(t, '}') && --depth == 0) break;
            }
            return prev;
        }
    }

    /* Pre-register the tag with an incomplete placeholder so a self-reference
     * like `class T { T *next; };` resolves to this declaration during body
     * parsing, instead of raising "Type T has not been declared". The
     * fields_dict and size are filled in later via the `prev && fields_dict`. */
    if (tag && !prev) {
        prev = astClassType(NULL, tag, 0, is_intrinsic);
        if (!is_class) prev->kind = AST_TYPE_UNION;
        prev->line = tag_line;
        prev->col = tag_col;
        prev->file_id = tag_file;
        prev->in_std = !cc->cur_module && !cc->decl_from_main;
        mapAdd(env, tag->data, prev);
    }

    int errors_before = cc->quiet_errors;
    fields = parseClassOrUnionFields(cc,tag,computeSize,&class_size);
    /* A field's error is recovered inside the body; parsed from its
     * recorded tokens (parseModuleTypes), the type must then stay
     * incomplete, not be completed without that field */
    if (cc->replaying && cc->quiet_errors != errors_before) {
        cctrlRaiseException(cc, "a field of %s failed", tag ? tag->data : "a class");
    }

    if (attrs) {
        AstAttr *a = attrs->entries[0];
        if (!is_class || is_intrinsic) {
            cctrlRaiseExceptionAt(cc, a->line, a->col, a->name->len + 1,
                    "`@%s` goes on a struct", a->name->data);
        }
        if (!fields) {
            cctrlRaiseExceptionAt(cc, a->line, a->col, a->name->len + 1,
                    "`@%s` goes on the struct's definition (with its body), "
                    "not on `struct %s;`", a->name->data, tag ? tag->data : "");
        }
    }
    if (fields) parseCheckFieldAttrs(cc, attrs, tag, fields);

    if (prev && !fields) {
        return prev;
    }

    if (is_class) {
        fields_dict = parseClassOffsets(cc,&aligned_size,&aligned,fields,base_class,tag,intrinsic_base);
    } else {
        fields_dict = parseUnionOffsets(cc,&aligned_size,&aligned,fields);
    }
    listRelease(fields,NULL);

    if (prev && fields_dict) {
        prev->fields = fields_dict;
        prev->attrs = attrs;
        prev->base_cls = base_class;
        prev->size = aligned_size;
        prev->alignment = (u32)aligned;
        if (intrinsic_base) prev->issigned = intrinsic_base->issigned;
        /* A forward-declared tag now has its full definition HERE -
         * repoint the provenance at it. */
        if (tag_line > 0) {
            prev->line = tag_line;
            prev->col = tag_col;
            prev->file_id = tag_file;
        }
        if (attrs) parseJsonGenerate(cc, prev, tag_tok);
        return prev;
    }

    if (fields_dict) {
        ref = astClassType(fields_dict,tag,aligned_size,is_intrinsic);
        if (base_class) {
            ref->size += base_class->size;
        }
    } else {
        ref = astClassType(NULL,tag,aligned_size,is_intrinsic);
    }
    ref->alignment = (u32)aligned;
    ref->base_cls = base_class;
    ref->attrs = attrs;
    if (intrinsic_base) ref->issigned = intrinsic_base->issigned;
    if (!is_class) ref->kind = AST_TYPE_UNION;
    ref->line = tag_line;
    ref->col = tag_col;
    ref->file_id = tag_file;
    ref->in_std = !cc->cur_module && !cc->decl_from_main;
    if (tag) {
        mapAdd(env,tag->data,ref);
    }
    if (attrs) parseJsonGenerate(cc, ref, tag_tok);
    return ref;
}

AstType *parseClassDef(Cctrl *cc, AstType *intrinsic_base) {
    return parseClassOrUnion(cc,cc->clsdefs,1,CalcClassSize,intrinsic_base);
}

AstType *parseUnionDef(Cctrl *cc) {
    AstType *_union = parseClassOrUnion(cc,cc->uniondefs,0,CalcUnionSize,NULL);
    _union->kind = AST_TYPE_UNION;
    return _union;
}

/* Does `value` fit in `type`'s width and signedness? */
static int parseEnumValueFits(AstType *type, s64 value) {
    int size = type->size > 0 ? type->size : 8;
    if (size >= 8) return 1;
    if (type->issigned) {
        s64 min = -(1LL << (size * 8 - 1));
        s64 max = (1LL << (size * 8 - 1)) - 1;
        return value >= min && value <= max;
    }
    u64 max = (1ULL << (size * 8)) - 1;
    return value >= 0 && (u64)value <= max;
}

/* `enum Name [: IntType] { member [= constexpr], ... };` at file scope.
 * The members are only reachable qualified (`Name.member`, parsed in
 * parseIdentifierOrFunction); unqualified they are bound in a scratch
 * scope just for the body, so `both = read | write` can use `read` and
 * `write` plainly while they are being defined. */
AstType *parseEnumDef(Cctrl *cc) {
    Lexeme *tok = cctrlTokenGetRequired(cc);
    if (tok->tk_type != TK_IDENT) {
        cctrlRaiseException(cc,"Expected a name after `enum`, got `%s`",
                lexemeAsWritten(tok));
    }
    /* A module's enum is named `a.b.Name` */
    char *qual = cctrlDeclName(cc, tok->start, tok->len);
    AoStr *tag = qual ? aoStrDupRaw(qual, strlen(qual))
                      : aoStrDupRaw(tok->start, tok->len);
    if (qual) {
        if (cc->decl_pub) setAdd(cc->pub_names, tag->data);
    } else if (cc->decl_from_main && !cc->cur_module) {
        setAdd(cc->main_names, tag->data);
    }
    AstType *defined = mapGetLen(cc->clsdefs, tag->data, tag->len);
    if (defined && defined->is_enum && cc->cur_module && !cc->decls_only) {
        /* A module's full pass: the declarations pass defined it */
        int depth = 0;
        Lexeme *t;
        while ((t = cctrlTokenGet(cc)) != NULL) {
            if (tokenPunctIs(t, '{')) depth++;
            else if (tokenPunctIs(t, '}') && --depth == 0) break;
        }
        return defined;
    }
    if (defined || mapGetLen(cc->uniondefs, tag->data, tag->len)) {
        cctrlRaiseException(cc,"`%s` has already been defined", tag->data);
    }

    AstType *underlying;
    if (tokenPunctIs(cctrlTokenPeek(cc), ':')) {
        cctrlTokenGet(cc);
        Lexeme *ty_tok = cctrlTokenGetRequired(cc);
        underlying = parseGetType(cc, ty_tok);
        if (!underlying || !astIsIntType(underlying) || underlying->is_bool) {
            cctrlRaiseException(cc,
                    "enum `%s`'s underlying type must be an integer type, got `%s`",
                    tag->data, lexemeAsWritten(ty_tok));
        }
    } else {
        underlying = cctrlGetKeyWord(cc, str_lit("i32"));
    }

    cctrlTokenExpect(cc,'{');

    AstType *enum_type = astClassType(NULL, tag, underlying->size, 1);
    enum_type->is_enum = 1;
    enum_type->alignment = (u32)underlying->size;
    enum_type->issigned = underlying->issigned;
    enum_type->enum_members = astMapNew();
    /* Provenance for the LSP: the enum's name and each member's */
    enum_type->line = tok->line;
    enum_type->col = tok->col;
    enum_type->file_id = ast_file_hint;

    Map *scope = cctrlCreateAstMap(cc->localenv);
    Map *saved_localenv = cc->localenv;
    cc->localenv = scope;

    s64 next_val = 0;
    int member_count = 0;
    for (;;) {
        Lexeme *mtok = cctrlTokenGet(cc);
        if (tokenPunctIs(mtok,'}')) {
            break;
        }
        if (!mtok || mtok->tk_type != TK_IDENT) {
            cc->localenv = saved_localenv;
            cctrlRaiseException(cc,
                    "Expected an enum member name in `%s`, got `%s`",
                    tag->data, lexemeAsWritten(mtok));
        }
        if (mapGetLen(enum_type->enum_members, mtok->start, mtok->len)) {
            cc->localenv = saved_localenv;
            cctrlRaiseException(cc,"enum `%s` already has a member `%.*s`",
                    tag->data, mtok->len, mtok->start);
        }
        AoStr *member_name = aoStrDupRaw(mtok->start, mtok->len);

        s64 value = next_val;
        if (tokenPunctIs(cctrlTokenPeek(cc), '=')) {
            cctrlTokenGet(cc);
            Ast *expr = parseExpr(cc,16);
            int ok = 1;
            value = expr ? evalIntConstExprOrErr(expr, &ok) : 0;
            if (!expr || !ok) {
                cc->localenv = saved_localenv;
                cctrlRaiseException(cc,
                        "enum member `%s.%s` needs a constant integer expression",
                        tag->data, member_name->data);
            }
        }
        if (!parseEnumValueFits(enum_type, value)) {
            cc->localenv = saved_localenv;
            cctrlRaiseException(cc,
                    "enum member `%s.%s` value %lld does not fit in `%s`",
                    tag->data, member_name->data, value,
                    astTypeToString(underlying));
        }
        next_val = value + 1;
        member_count++;

        Ast *unqualified = astI64Type(value);
        unqualified->type = astTypeCopy(underlying);
        mapAdd(scope, member_name->data, unqualified);

        Ast *qualified = astI64Type(value);
        qualified->type = enum_type;
        qualified->line = mtok->line;
        qualified->col = mtok->col;
        qualified->file_id = ast_file_hint;
        mapAdd(enum_type->enum_members, member_name->data, qualified);

        Lexeme *sep = cctrlTokenGetRequired(cc);
        if (tokenPunctIs(sep,'}')) {
            break;
        }
        if (!tokenPunctIs(sep,',')) {
            cc->localenv = saved_localenv;
            cctrlRaiseException(cc,
                    "Expected `,` or `}` after enum member `%s.%s`, got `%s`",
                    tag->data, member_name->data, lexemeAsWritten(sep));
        }
        if (tokenPunctIs(cctrlTokenPeek(cc),'}')) {
            cctrlTokenGet(cc);
            break;
        }
    }

    cc->localenv = saved_localenv;

    if (member_count == 0) {
        cctrlRaiseException(cc,"enum `%s` has no members", tag->data);
    }

    mapAdd(cc->clsdefs, tag->data, enum_type);
    return enum_type;
}

/* No `/` or `%` in `ast` has a divisor that folds to 0 (folding it would
 * divide by zero in the compiler) */
static int parseConstFoldSafe(Ast *ast) {
    if (!ast) return 1;
    switch (ast->kind) {
        case AST_BINOP:
            if (ast->binop == AST_BIN_OP_DIV || ast->binop == AST_BIN_OP_MOD) {
                int ok = 1;
                if (!astIsFloatType(ast->right->type) &&
                    parseConstFoldSafe(ast->right) &&
                    evalIntConstExprOrErr(ast->right, &ok) == 0 && ok)
                {
                    return 0;
                }
            }
            return parseConstFoldSafe(ast->left) &&
                   parseConstFoldSafe(ast->right);
        case AST_UNOP:
        case AST_CAST:
            return parseConstFoldSafe(ast->operand);
        default:
            return 1;
    }
}

/* `e` is a constant address: of a global (`&g`, an array `arr`), of a
 * function (`&Fn`), plus a constant (`&arr[2]`, `&g + 1`), through
 * pointer casts. Gives the global or function and the byte offset. */
static int parseAddrConst(Ast *e, Ast **_target, s64 *_off) {
    if (!e) return 0;
    switch (e->kind) {
        case AST_CAST:
            if (!e->type || e->type->kind != AST_TYPE_POINTER) return 0;
            return parseAddrConst(e->operand, _target, _off);
        case AST_GVAR:
            /* an array is its address */
            if (!e->type || e->type->kind != AST_TYPE_ARRAY) return 0;
            *_target = e;
            *_off = 0;
            return 1;
        case AST_UNOP: {
            Ast *op = e->operand;
            if (e->unop != AST_UN_OP_ADDR_OF || !op) return 0;
            if (op->kind == AST_GVAR || op->kind == AST_FUNC ||
                op->kind == AST_FUN_PROTO || op->kind == AST_EXTERN_FUNC)
            {
                *_target = op;
                *_off = 0;
                return 1;
            }
            /* `&arr[i]` is `&*(arr + i)` */
            if (op->kind == AST_UNOP && op->unop == AST_UN_OP_DEREF) {
                return parseAddrConst(op->operand, _target, _off);
            }
            return 0;
        }
        case AST_BINOP: {
            int is_add = e->binop == AST_BIN_OP_ADD;
            if ((!is_add && e->binop != AST_BIN_OP_SUB) || !e->type ||
                (e->type->kind != AST_TYPE_POINTER &&
                 e->type->kind != AST_TYPE_ARRAY))
            {
                return 0;
            }
            /* scaled as the IR does: by the result's element size */
            s64 scale = e->type->ptr && e->type->ptr->size > 0
                ? e->type->ptr->size : 1;
            Ast *l = e->left, *r = e->right;
            int left_is_ptr = l && l->type &&
                (l->type->kind == AST_TYPE_POINTER ||
                 l->type->kind == AST_TYPE_ARRAY);
            Ast *base = left_is_ptr ? l : r;
            Ast *idx = left_is_ptr ? r : l;
            int ok = 1;
            if (!is_add && !left_is_ptr) return 0;
            if (!idx || !idx->type || !astIsIntType(idx->type) ||
                !parseConstFoldSafe(idx))
            {
                return 0;
            }
            s64 k = evalIntConstExprOrErr(idx, &ok);
            if (!ok || !parseAddrConst(base, _target, _off)) return 0;
            *_off += (is_add ? k : -k) * scale;
            return 1;
        }
        default:
            return 0;
    }
}

/* A scalar global's initialiser that is a constant expression, folded to a
 * literal of the global's type, to be written out as static data like an
 * array's: then it holds its value before anything runs, also in a -lib
 * library (no main), and the generated main doesn't need to store it.
 * NULL when `rhs` isn't constant: it is stored at startup instead. The
 * REPL keeps running the stores, as it runs every top-level statement. */
static Ast *parseConstGlobalInit(Cctrl *cc, Ast *var, Ast *rhs) {
    AstType *t = var->type;
    Ast *lit;
    int ok = 1;

    if ((cc->flags & CCTRL_REPL) || !t || !rhs || !rhs->type ||
        !parseConstFoldSafe(rhs))
    {
        return NULL;
    }
    if (astIsFloatType(t)) {
        if (!astIsFloatType(rhs->type) && !astIsIntType(rhs->type)) {
            return NULL;
        }
        double v = evalFloatExprOrErr(rhs, &ok);
        if (!ok) return NULL;
        if (t->size == 4) v = (double)(float)v;
        lit = astF64Type(v);
    } else if (astIsIntType(t) && !t->is_intrinsic) {
        s64 v;
        if (astIsFloatType(rhs->type)) {
            double f = evalFloatExprOrErr(rhs, &ok);
            v = t->is_bool ? f != 0 : (s64)f;
        } else if (astIsIntType(rhs->type)) {
            v = evalIntConstExprOrErr(rhs, &ok);
        } else {
            return NULL;
        }
        if (!ok) return NULL;
        /* Converted as an assignment converts: a Bool is 0 or 1 (value
         * != 0), `U8 g = 300` is 44 */
        if (t->is_bool) {
            v = v != 0;
        } else if (t->size > 0 && t->size < 8) {
            u64 mask = (1ULL << (t->size * 8)) - 1;
            v &= mask;
            if (t->issigned && (v & (s64)(mask ^ (mask >> 1)))) {
                v |= ~mask;
            }
        }
        lit = astI64Type(v);
    } else if (t->kind == AST_TYPE_POINTER || t->kind == AST_TYPE_FUNC) {
        /* AST_TYPE_FUNC: a function pointer, `fn(i64) i64 fp = &Fn` */
        Ast *target = NULL;
        s64 off = 0;
        /* `null` is `(void *)0`: the integer inside a pointer cast */
        while (rhs->kind == AST_CAST && rhs->operand && rhs->operand->type &&
               (astIsIntType(rhs->operand->type) ||
                rhs->operand->kind == AST_CAST)) {
            rhs = rhs->operand;
        }
        if (astIsIntType(rhs->type)) {
            /* `U8 *p = NULL`, `0x1000` */
            s64 v = evalIntConstExprOrErr(rhs, &ok);
            if (!ok) return NULL;
            lit = astI64Type(v);
        } else if (parseAddrConst(rhs, &target, &off) &&
                   !(target->kind == AST_GVAR && (target->flags & AST_FLAG_TLS))) {
            /* `&g`, `&arr[2]`, `&Fn`: as `(U8 *)&target + off`, which the
             * backends write as a relocation (asmAddrConst) */
            int is_err = 0;
            Ast *addr = astUnaryOperator(astMakePointerType(ast_u8_type),
                                         AST_UN_OP_ADDR_OF, target);
            if (off) {
                addr = astBinaryOp(AST_BIN_OP_ADD, addr, astI64Type(off),
                                   &is_err);
                if (is_err) return NULL;
            }
            return addr;
        } else {
            return NULL;
        }
    } else {
        return NULL;
    }
    lit->type = astTypeCopy(t);
    return lit;
}

/* A global's `{...}` initialiser is written out as static data, which only
 * holds literals and string addresses (asmInitImage). Any other item -
 * `&gx`, `&Foo`, `gx`, `&arr[1]` - is not known until the program runs,
 * so store it at startup like a scalar global's `I64 g = gx;`: append
 * `*(T *)((U8 *)&var + off) = item` to the file-scope initialisers and
 * leave its slot zero in the data. Offsets as in asmInitImageAt. */
/* M2: the module whose `_init` a run-time initialiser being parsed goes
 * into, NULL for the program's own (the entry runs those) and in the REPL
 * (each input runs its own) */
static LexModule *parseInitModule(Cctrl *cc) {
    if (!cc->cur_module || !cc->lexer_ || (cc->flags & CCTRL_REPL)) return NULL;
    return cc->lexer_->modules[cc->cur_module - 1];
}

/* The module a `-lib` build of one module makes (a package's prebuild
 * too), NULL for any other build */
static LexModule *parseLibraryModule(Cctrl *cc) {
    Lexer *l = cc->lexer_;
    if (!l || !l->building_module || (cc->flags & CCTRL_REPL)) return NULL;
    for (int i = 0; i < l->modules_len; ++i) {
        LexModule *m = l->modules[i];
        if (!m->prebuilt && !strcmp(m->path->data, l->building_module)) return m;
    }
    return NULL;
}

/* A global's run-time initialiser: its module's, or the program's. A
 * prebuilt module's `_init` is already compiled, so what a generic of it
 * adds while made for this program (reflect.of in json.decode<T>) runs
 * with the program's own initialisers; in a `-lib` build of one module,
 * which has no program start of its own, with that module's `_init`,
 * which its importers call (the descriptor json.decode<Header> uses in
 * a prebuilt package was left zeroed). */
/* Run-time initialisers added so far: a threadlocal's must not get one */
static u64 parse_init_count = 0;

/* A `-lib` build of a tree of modules: the initialisers that belong to
 * no module of it (a prebuilt generic's, as above). Every module of the
 * tree calls them from its `_init`, once (parseModuleInits). */
static List *parse_tree_inits = NULL;

static void parseAddInitialiser(Cctrl *cc, Ast *ast) {
    LexModule *m = parseInitModule(cc);
    parse_init_count++;
    if (!m || m->prebuilt) {
        LexModule *lib = parseLibraryModule(cc);
        if (lib) {
            m = lib;
        } else if (cc->lexer_ && cc->lexer_->building_tree && !(cc->flags & CCTRL_REPL)) {
            if (!parse_tree_inits) parse_tree_inits = listNew();
            listAppend(parse_tree_inits, ast);
            return;
        }
    }
    if (m && !m->prebuilt) {
        if (!m->init_stmts) m->init_stmts = listNew();
        listAppend(m->init_stmts, ast);
        return;
    }
    listAppend(cc->initalisers, ast);
}

/* ---- Reflection (v0.4.0): reflect.typeOf(T) and reflect.of(x) ----
 *
 * A type a program asks about is described by a hidden, zeroed static
 * reflect.Type global, filled in when the program (or the module's
 * _init) starts, with stores at the offsets of reflect's own structs:
 * the same statements a global's run-time initialiser gets. Every type
 * it leads to (fields, elements) is described too, each once. */

/* reflect.Kind's members, in order (src/std/reflect/reflect.ktm) */
enum {
    REFLECT_NONE, REFLECT_INT, REFLECT_FLOAT, REFLECT_BOOLEAN, REFLECT_ENUM,
    REFLECT_POINTER, REFLECT_ARRAY, REFLECT_SLICE, REFLECT_STRING,
    REFLECT_STRUCT, REFLECT_UNION, REFLECT_FN
};

static AstType *parseReflectStruct(Cctrl *cc, char *name) {
    AstType *t = mapGet(cc->clsdefs, name);
    if (!t) {
        cctrlRaiseException(cc, "reflect.typeOf and reflect.of need `import reflect;`");
    }
    return t;
}

static int parseReflectOffset(AstType *cls, char *field) {
    AstType *f = mapGet(cls->fields, field);
    assert(f);
    return f->offset;
}

/* At start-up, `value` (a `ty`) into `var` at byte `off` */
static void parseReflectStore(Cctrl *cc, Ast *var, int off, AstType *ty, Ast *value) {
    int is_err = 0;
    Ast *base = astCast(astUnaryOperator(astMakePointerType(var->type),
                                         AST_UN_OP_ADDR_OF, var),
                        astMakePointerType(ast_u8_type));
    Ast *addr = astBinaryOp(AST_BIN_OP_ADD, base, astI64Type(off), &is_err);
    Ast *slot = astUnaryOperator(ty, AST_UN_OP_DEREF,
                                 astCast(addr, astMakePointerType(ty)));
    parseAddInitialiser(cc, astBinaryOp(AST_BIN_OP_ASSIGN, slot, value, &is_err));
}

static void parseReflectStoreInt(Cctrl *cc, Ast *var, int off, s64 n) {
    if (n) parseReflectStore(cc, var, off, ast_int_type, astI64Type(n));
}

/* A bool at `off`: one byte, so the next field is untouched */
static void parseReflectStoreBool(Cctrl *cc, Ast *var, int off, int b) {
    if (b) parseReflectStore(cc, var, off, ast_u8_type, astI64Type(1));
}

/* A u8[] at `off`: the bytes of `s` (read-only data) */
static void parseReflectStoreText(Cctrl *cc, Ast *var, int off, char *s, int len) {
    AstType *u8p = astMakePointerType(ast_u8_type);
    Ast *lit = cctrlGetOrSetString(cc, s, len, len + 1);
    parseReflectStore(cc, var, off, u8p, astCast(lit, u8p));
    parseReflectStoreInt(cc, var, off + 8, len);
}

/* A pointer at `off`: the address of the global `target` */
static void parseReflectStoreAddr(Cctrl *cc, Ast *var, int off, Ast *target) {
    AstType *vp = astMakePointerType(ast_void_type);
    parseReflectStore(cc, var, off, vp,
        astCast(astUnaryOperator(astMakePointerType(target->type),
                                 AST_UN_OP_ADDR_OF, target), vp));
}

static Ast *parseReflectGlobal(Cctrl *cc, AstType *type) {
    static int id = 0;
    AoStr *name = aoStrPrintf("#reflect%d", id++);
    Ast *var = astGVar(type, name->data, name->len, 1);
    listAppend(cc->ast_list, astDecl(var, NULL));
    return var;
}

static int parseReflectKind(AstType *t) {
    if (astTypeIsString(t)) return REFLECT_STRING;
    if (astTypeIsSlice(t)) return REFLECT_SLICE;
    if (t->is_enum) return REFLECT_ENUM;
    if (t->is_bool) return REFLECT_BOOLEAN;
    switch (t->kind) {
        case AST_TYPE_VOID:    return REFLECT_NONE;
        case AST_TYPE_INT:
        case AST_TYPE_CHAR:    return REFLECT_INT;
        case AST_TYPE_FLOAT:   return REFLECT_FLOAT;
        case AST_TYPE_POINTER: return REFLECT_POINTER;
        case AST_TYPE_ARRAY:   return REFLECT_ARRAY;
        case AST_TYPE_FUNC:    return REFLECT_FN;
        case AST_TYPE_UNION:   return REFLECT_UNION;
        case AST_TYPE_CLASS:   return t->is_intrinsic ? REFLECT_INT : REFLECT_STRUCT;
        default:               return REFLECT_NONE;
    }
}

/* The hidden reflect.Type global describing `t` */
static Ast *parseReflectDescribe(Cctrl *cc, AstType *t) {
    AstType *rtype = parseReflectStruct(cc, "reflect.Type");
    AoStr *key = astTypeToAoStr(t);
    if (!cc->reflect_types) {
        cc->reflect_types = mapNew(32, &map_cstring_opaque_type);
    }
    Ast *g = mapGet(cc->reflect_types, key->data);
    if (g) {
        aoStrRelease(key);
        return g;
    }
    g = parseReflectGlobal(cc, rtype);
    mapAdd(cc->reflect_types, strdup(key->data), g);

    int kind = parseReflectKind(t);
    parseReflectStoreInt(cc, g, parseReflectOffset(rtype, "kind"), kind);
    parseReflectStoreText(cc, g, parseReflectOffset(rtype, "name"), key->data, key->len);
    parseReflectStoreInt(cc, g, parseReflectOffset(rtype, "size"), t->size);
    if (kind == REFLECT_INT || kind == REFLECT_ENUM) {
        AstType *base = t;
        if (t->is_enum && t->ptr) base = t->ptr;
        parseReflectStoreInt(cc, g, parseReflectOffset(rtype, "isSigned"),
                             base->issigned ? 1 : 0);
    }
    AstType *elem = NULL;
    if (kind == REFLECT_SLICE) elem = t->elem;
    else if (kind == REFLECT_POINTER || kind == REFLECT_ARRAY) elem = t->ptr;
    if (elem) {
        parseReflectStoreAddr(cc, g, parseReflectOffset(rtype, "elem"),
                              parseReflectDescribe(cc, elem));
    }
    if (kind == REFLECT_ARRAY) {
        parseReflectStoreInt(cc, g, parseReflectOffset(rtype, "len"), t->len);
    }

    if ((kind == REFLECT_STRUCT || kind == REFLECT_UNION) && t->fields &&
        t->fields->indexes && t->fields->indexes->size) {
        AstType *rfield = parseReflectStruct(cc, "reflect.Field");
        Map *fields = t->fields;
        int n = 0;
        for (u64 i = 0; i < fields->indexes->size; ++i) {
            u64 fi = (u64)(uintptr_t)fields->indexes->entries[i];
            if (fields->entries[fi].value) n++;
        }
        Ast *arr = parseReflectGlobal(cc, astMakeArrayType(rfield, n));
        int j = 0;
        for (u64 i = 0; i < fields->indexes->size; ++i) {
            u64 fi = (u64)(uintptr_t)fields->indexes->entries[i];
            MapNode *node = &fields->entries[fi];
            AstType *ft = (AstType *)node->value;
            if (!ft) continue;
            char *fname = (char *)node->key;
            int at = j * rfield->size;
            parseReflectStoreText(cc, arr, at + parseReflectOffset(rfield, "name"),
                                  fname, strlen(fname));
            parseReflectStoreAddr(cc, arr, at + parseReflectOffset(rfield, "type"),
                                  parseReflectDescribe(cc, ft));
            parseReflectStoreInt(cc, arr, at + parseReflectOffset(rfield, "offset"),
                                 ft->offset);
            parseReflectStoreBool(cc, arr, at + parseReflectOffset(rfield, "isPrivate"),
                                 ft->private_module ? 1 : 0);
            AstAttr *key = astAttrGet(ft->field_attrs, "key");
            if (key) {
                parseReflectStoreText(cc, arr, at + parseReflectOffset(rfield, "key"),
                                      key->arg->data, key->arg->len);
            } else {
                parseReflectStoreText(cc, arr, at + parseReflectOffset(rfield, "key"),
                                      fname, strlen(fname));
            }
            parseReflectStoreBool(cc, arr, at + parseReflectOffset(rfield, "skip"),
                                 astAttrGet(ft->field_attrs, "skip") ? 1 : 0);
            parseReflectStoreBool(cc, arr, at + parseReflectOffset(rfield, "omitEmpty"),
                                 astAttrGet(ft->field_attrs, "omitEmpty") ? 1 : 0);
            AstAttr *env = astAttrGet(ft->field_attrs, "env");
            if (env) {
                parseReflectStoreText(cc, arr, at + parseReflectOffset(rfield, "env"),
                                      env->arg->data, env->arg->len);
            }
            parseReflectStoreBool(cc, arr, at + parseReflectOffset(rfield, "required"),
                                 astAttrGet(ft->field_attrs, "required") ? 1 : 0);
            static char *bounds[] = {"min", "max"};
            for (int b = 0; b < 2; ++b) {
                AstAttr *lim = astAttrGet(ft->field_attrs, bounds[b]);
                if (!lim) continue;
                parseReflectStoreBool(cc, arr, at + parseReflectOffset(rfield,
                                      b ? "hasMax" : "hasMin"), 1);
                parseReflectStore(cc, arr, at + parseReflectOffset(rfield, bounds[b]),
                                  ast_float_type, astF64Type(lim->num));
            }
            parseReflectStoreBool(cc, arr, at + parseReflectOffset(rfield, "email"),
                                 astAttrGet(ft->field_attrs, "email") ? 1 : 0);
            parseReflectStoreBool(cc, arr, at + parseReflectOffset(rfield, "url"),
                                 astAttrGet(ft->field_attrs, "url") ? 1 : 0);
            AstAttr *one = astAttrGet(ft->field_attrs, "oneOf");
            if (one) {
                parseReflectStoreText(cc, arr, at + parseReflectOffset(rfield, "oneOf"),
                                      one->arg->data, one->arg->len);
            }
            j++;
        }
        int off = parseReflectOffset(rtype, "fields");
        parseReflectStoreAddr(cc, g, off, arr);
        parseReflectStoreInt(cc, g, off + 8, n);
    }
    if (kind == REFLECT_STRUCT) {
        parseReflectStoreBool(cc, g, parseReflectOffset(rtype, "json"),
                             astAttrGet(t->attrs, "json") ? 1 : 0);
        parseReflectStoreBool(cc, g, parseReflectOffset(rtype, "yaml"),
                             astAttrGet(t->attrs, "yaml") ? 1 : 0);
        static char *methods[] = {"toJson", "fromJson", "freeDecoded"};
        for (int m = 0; t->clsname && astAttrGet(t->attrs, "json") && m < 3; ++m) {
            Ast *fn = mapGet(cc->global_env, mprintf("%s.%s", t->clsname->data, methods[m]));
            if (fn && (fn->kind == AST_FUNC || fn->kind == AST_FUN_PROTO)) {
                parseReflectStoreAddr(cc, g, parseReflectOffset(rtype, methods[m]), fn);
            }
        }
    }

    if (kind == REFLECT_ENUM && t->enum_members && t->enum_members->indexes &&
        t->enum_members->indexes->size) {
        AstType *rmember = parseReflectStruct(cc, "reflect.EnumMember");
        Map *members = t->enum_members;
        int n = (int)members->indexes->size;
        Ast *arr = parseReflectGlobal(cc, astMakeArrayType(rmember, n));
        for (int j = 0; j < n; ++j) {
            u64 mi = (u64)(uintptr_t)members->indexes->entries[j];
            MapNode *node = &members->entries[mi];
            Ast *value = (Ast *)node->value;
            char *mname = (char *)node->key;
            int at = j * rmember->size;
            parseReflectStoreText(cc, arr, at + parseReflectOffset(rmember, "name"),
                                  mname, strlen(mname));
            parseReflectStoreInt(cc, arr, at + parseReflectOffset(rmember, "value"),
                                 value ? value->i64 : 0);
        }
        int off = parseReflectOffset(rtype, "members");
        parseReflectStoreAddr(cc, g, off, arr);
        parseReflectStoreInt(cc, g, off + 8, n);
    }
    aoStrRelease(key);
    return g;
}

/* reflect.Value, for `any ...name`; NULL when reflect isn't loaded */
AstType *parseReflectValueType(Cctrl *cc) {
    Ast *fn = findFunctionDecl(cc, str_lit("reflect.__value"));
    return fn ? fn->type->rettype : NULL;
}

/* The address of the reflect.Type describing t */
Ast *parseReflectTypeAddr(Cctrl *cc, AstType *t) {
    Ast *g = parseReflectDescribe(cc, t);
    return astUnaryOperator(astMakePointerType(g->type), AST_UN_OP_ADDR_OF, g);
}

/* `reflect.typeOf(T or expr)` (a reflect.Type *) and `reflect.of(x)` (a
 * reflect.Value, x's address and type), the `(` read; NULL for any other
 * call */
Ast *parseReflectCall(Cctrl *cc, char *name, int len) {
    int type_of = len == 14 && !memcmp(name, "reflect.typeOf", 14);
    int value_of = len == 10 && !memcmp(name, "reflect.of", 10);
    int name_of = len == 12 && !memcmp(name, "reflect.name", 12);
    if (!type_of && !value_of && !name_of) {
        return NULL;
    }
    AstType *t = NULL;
    Ast *operand = NULL;
    Lexeme *peek = cctrlTokenPeek(cc);
    if (type_of && peek && (cctrlTokIsKeyword(cc, peek) ||
                            (tokenMayStartFnType(peek) && parseFnStartsType(cc)))) {
        t = parseFullType(cc);
    } else {
        operand = parseExpr(cc, 16);
        if (!operand || !operand->type) {
            cctrlRaiseException(cc, "%.*s: expected %s", len, name,
                                type_of ? "a type or an expression" : "a variable");
        }
        t = operand->type;
    }
    cctrlTokenExpect(cc, ')');
    if (name_of && !t->is_enum) {
        cctrlRaiseException(cc, "reflect.name takes an enum value, not a %s",
                            astTypeToString(t));
    }
    Ast *g = parseReflectDescribe(cc, t);
    AstType *tp = astMakePointerType(g->type);
    Ast *type_addr = astUnaryOperator(tp, AST_UN_OP_ADDR_OF, g);
    if (type_of) {
        return type_addr;
    }
    if (name_of) {
        /* reflect.nameOf(reflect.typeOf(e), e) */
        Ast *fn = findFunctionDecl(cc, str_lit("reflect.nameOf"));
        if (!fn) {
            cctrlRaiseException(cc, "reflect.name needs `import reflect;`");
        }
        Vec *argv = astVecNew();
        vecPush(argv, type_addr);
        vecPush(argv, astCast(operand, ast_int_type));
        return astFunctionCall(fn->type->rettype, fn->fname->data, fn->fname->len, argv);
    }
    if (!assertLValue(operand)) {
        cctrlRaiseException(cc, "reflect.of takes a variable, a field or an element "
                            "(something with an address), not a value");
    }
    Ast *fn = findFunctionDecl(cc, str_lit("reflect.__value"));
    if (!fn) {
        cctrlRaiseException(cc, "reflect.typeOf and reflect.of need `import reflect;`");
    }
    Vec *argv = astVecNew();
    AstType *vp = astMakePointerType(ast_void_type);
    vecPush(argv, astCast(astUnaryOperator(astMakePointerType(t), AST_UN_OP_ADDR_OF,
                                           operand), vp));
    vecPush(argv, type_addr);
    return astFunctionCall(fn->type->rettype, fn->fname->data, fn->fname->len, argv);
}

static void parseGlobalInitRuntimeItems(Cctrl *cc, Ast *var, Ast *init,
                                        AstType *type, int off)
{
    int is_class = !astIsIntrinsicClass(type) &&
                   (type->kind == AST_TYPE_CLASS ||
                    type->kind == AST_TYPE_UNION);
    int idx = 0;
    listForEach(init->arrayinit) {
        Ast *item = (Ast *)it->value;
        AstType *item_ty = NULL;
        int item_off = off;
        if (is_class) {
            item_ty = astClassFieldAt(type, idx);
            if (item_ty) item_off = off + item_ty->offset;
        } else if (type->kind == AST_TYPE_ARRAY) {
            item_ty = type->ptr;
            item_off = off + idx * item_ty->size;
        }
        idx++;
        if (!item || !item_ty || item->kind == AST_LITERAL ||
            item->kind == AST_STRING)
        {
            continue;
        }
        if (item->kind == AST_ARRAY_INIT) {
            parseGlobalInitRuntimeItems(cc, var, item, item_ty, item_off);
            continue;
        }
        int is_err = 0;
        Ast *base = astCast(
                astUnaryOperator(astMakePointerType(var->type),
                                 AST_UN_OP_ADDR_OF, var),
                astMakePointerType(ast_u8_type));
        Ast *addr = astBinaryOp(AST_BIN_OP_ADD, base, astI64Type(item_off),
                                &is_err);
        Ast *slot = astUnaryOperator(item_ty, AST_UN_OP_DEREF,
                astCast(addr, astMakePointerType(item_ty)));
        Ast *assign = astBinaryOp(AST_BIN_OP_ASSIGN, slot, item, &is_err);
        if (is_err) {
            cctrlRaiseException(cc, "Cannot initialise %s with %s",
                                astTypeToString(item_ty),
                                astTypeToString(item->type));
        }
        parseAddInitialiser(cc, assign);
    }
}

/* `T[] s = { ... }`. Two items, a T * and a length, are the slice's own
 * fields as before (`i64[] s = {ptr, n};`); anything else is a list of
 * Ts, kept in a hidden array the slice views, as `T arr[] = {...};
 * T[] s = arr;` would (it lives as long as the function, or the
 * program for a global). The `{` is the next token. */
static Ast *parseSliceInit(Cctrl *cc, Ast *var, s64 terminator_flags) {
    AstType *st = var->type;
    AstType *elem = st->elem;
    AstType *arr_t = astMakeArrayType(elem, -1);
    volatile int depth = 0;
    List *items = listNew();
    Vec *starts = vecNew(&vec_unsigned_long_type);
    int nested = 0;

    cctrlTokenGetRequired(cc); /* { */
    depth++;
    u64 i = 0;
    while (1) {
        Lexeme *tok = cctrlTokenGetRequired(cc);
        if (tokenPunctIs(tok, '}')) {
            depth--;
            break;
        }
        cctrlTokenRewind(cc);
        Ast *item;
        Lexeme *start = malloc(sizeof(Lexeme));
        if (tokenPunctIs(tok, '{')) {
            item = parseDeclNestedInit(cc, arr_t, 0, i, &depth);
            nested = 1;
            *start = *tok;
        } else {
            item = parseDeclInitExpr(cc, start);
        }
        listAppend(items, item);
        vecPush(starts, start);
        i++;
        tok = cctrlTokenGet(cc);
        if (!tokenPunctIs(tok, ',')) cctrlTokenRewind(cc);
    }
    Lexeme *end = cctrlTokenGet(cc);
    assertTokenIsTerminator(cc, end, terminator_flags);

    u64 n = listCount(items);
    Ast *first = n == 2 ? (Ast *)items->next->value : NULL;
    Ast *second = n == 2 ? (Ast *)items->next->next->value : NULL;
    int fields = !nested && first && second && first->type && second->type &&
                 first->type->kind == AST_TYPE_POINTER &&
                 astIsIntType(second->type) &&
                 !(elem->kind == AST_TYPE_POINTER &&
                   !strcmp(astTypeToString(first->type), astTypeToString(elem)));
    List *inits = listNew();
    i = 0;
    listForEach(items) {
        Ast *item = it->value;
        Lexeme *start = vecGet(Lexeme *, starts, i);
        if (fields) {
            item = parseDeclScalarInit(cc, st, 1, i, &depth, item, start);
        } else if (item->kind != AST_ARRAY_INIT) {
            item = parseDeclScalarInit(cc, arr_t, 0, i, &depth, item, start);
        }
        listAppend(inits, item);
        free(start);
        i++;
    }
    vecRelease(starts);
    Ast *list = astArrayInit(inits);
    if (fields) {
        if (var->kind == AST_GVAR) parseGlobalInitRuntimeItems(cc, var, list, st, 0);
        return astDecl(var, list);
    }

    arr_t->len = (int)n;
    arr_t->size = (int)n * elem->size;
    static int arr_id = 0;
    AoStr *name = aoStrPrintf("#slicearr%d", arr_id++);
    Ast *arr;
    Ast *arr_decl;
    if (var->kind == AST_LVAR) {
        arr = astLVar(arr_t, name->data, name->len);
        if (cc->tmp_locals) listAppend(cc->tmp_locals, arr);
        arr_decl = astDecl(arr, list);
    } else {
        arr = astGVar(arr_t, name->data, name->len, 1);
        listAppend(cc->ast_list, astDecl(arr, list));
        parseGlobalInitRuntimeItems(cc, arr, list, arr_t, 0);
        arr_decl = NULL;
    }
    if (!arr_decl) {
        /* a global: the view is set when the program starts, after the
         * array's own run-time items */
        int is_err = 0;
        parseAddInitialiser(cc, astBinaryOp(AST_BIN_OP_ASSIGN, var,
                                            parseSliceFrom(cc, st, arr), &is_err));
        return astDecl(var, NULL);
    }
    Ast *decl = astDecl(var, parseSliceFrom(cc, st, arr));
    List *both = listNew();
    listAppend(both, arr_decl);
    listAppend(both, decl);
    return astCompountStatement(both);
}

/* `eq_line`:`eq_col` is where the `=` is, for the type check warning. */
Ast *parseVariableAssignment(Cctrl *cc, Ast *var, s64 terminator_flags,
                             int eq_line, int eq_col)
{
    Ast *init;
    int len;
    Lexeme *peek = cctrlTokenPeek(cc);
    assert(var);

    if (var->type->kind == AST_TYPE_ARRAY) {
        init = parseDeclArrayInitInt(cc,var->type);
        int is_str = init->kind == AST_STRING;
        if (is_str) {
            /* `real_len` is the byte length with escapes decoded (and
             * the NUL), which is what lands in the array; `sval` keeps
             * the escapes in their textual form. */
            len = init->real_len;
        } else {
            len = listCount(init->arrayinit);
        }
        if (var->type->len == -1) {
            var->type->len = len;
            var->type->size = len * var->type->ptr->size;
        } else if (var->type->len < len) {
            /* Like C, `U8 buf[8] = "abc"` copies the string and
             * `I64 a[4] = {1,2}` sets the first two items; the rest is
             * zero-filled. Only a too-small array is an error. */
            cctrlRaiseExceptionFromTo(cc, NULL, '{', '}',
                                     "Invalid array initializer: expected %d items but got %d",
                                      var->type->len, len);
        }
        Lexeme *tok = cctrlTokenGet(cc);
        assertTokenIsTerminator(cc,tok,terminator_flags);
        if (var->kind == AST_GVAR && !is_str) {
            parseGlobalInitRuntimeItems(cc,var,init,var->type,0);
        }
        return astDecl(var,init);
    } else if (astTypeIsSlice(var->type) && tokenPunctIs(peek,'{')) {
        return parseSliceInit(cc, var, terminator_flags);
    } else if ((var->type->kind == AST_TYPE_CLASS ||
                var->type->kind == AST_TYPE_UNION) &&
               !var->type->is_intrinsic && tokenPunctIs(peek,'{')) {
        init = parseDeclArrayInitInt(cc,var->type);
        Lexeme *tok = cctrlTokenGet(cc);
        assertTokenIsTerminator(cc,tok,terminator_flags);
        if (var->kind == AST_GVAR) {
            parseGlobalInitRuntimeItems(cc,var,init,var->type,0);
        }
        return astDecl(var,init);
    } else if (tokenPunctIs(peek,'{')) {
        /* `I64 x = {1};` is C but not ktm. Reported at the `{`, which
         * is not consumed so the recovery skips the whole list. */
        cctrlRaiseException(cc,
                "Cannot use an initialiser list for a variable of type %s",
                astTypeToString(var->type));
    }

    init = parseExpr(cc,16);
    if (!init) {
        /* `I64 v = ;`: the same error as the assignment `v = ;` (this
         * went on with no initialiser and crashed) */
        Lexeme *bad = cctrlTokenPeek(cc);
        cctrlTokenRewind(cc);
        char *name = astLValueToString(var,0);
        char *msg = mprintf("`%s = var2` is the expected usage however got `%s`",
                            name, lexemeAsWritten(bad));
        cctrlRaiseSuggestion(cc,msg,"Second operand missing to `%s =` got invalid %s `%s`",
                             name, lexemeTypeToString(bad->tk_type),
                             lexemeAsWritten(bad));
    }
    Lexeme *tok = cctrlTokenGet(cc);
    assertTokenIsTerminator(cc,tok,terminator_flags);
    if (var->kind == AST_GVAR && var->type->kind == AST_TYPE_INT) {
        init = astI64Type(evalIntConstExpr(init));
    }

    /* `i64[] s = array;`: the array becomes a slice */
    init = parseSliceFrom(cc, var->type, init);
    parseResolveOverloadRef(cc, var->type, init);
    if (var->kind == AST_LVAR && init->kind == AST_SLICE_MAKE &&
        init->slice_ptr && init->slice_ptr->kind == AST_STRING) {
        var->flags |= AST_FLAG_LIT_SLICE;
    }

    /* `auto`: the type is the initialiser's, nothing to check it against
     * (a string's `u8[6]` "wasn't assignable to" its own `u8[6]`) */
    int from_auto = var->type->kind == AST_TYPE_AUTO;
    if (from_auto) {
        var->type = init->type;
    }

    /* A value the `=` operator rejects (`I64 z = p` with a class `p`) is
     * the same error as the assignment `z = p`, and the only diagnostic;
     * one it accepts with a different type gets the warning. */
    int is_err = 0;
    if (init->kind != AST_COMPOUND_STMT) {
        astBinaryOp(AST_BIN_OP_ASSIGN,var,init,&is_err);
    }
    if (is_err) {
        parseCreateBinaryOp(cc,AST_BIN_OP_ASSIGN,var,init);
    } else if (!from_auto && !astTypeCheck(var->type,init,AST_BIN_OP_ASSIGN)) {
        typeCheckWarn(cc,eq_line,eq_col,var,init);
    }

    /* This is for when we have parsed a call to an inline function that is 
     * being assigned to a variable */
    if (init->kind == AST_COMPOUND_STMT) {
        /* Attach the Ast to the current function that is being called */
        if (cc->tmp_func) {
            listAppend(cc->tmp_func->body->stms,init);
        }
        return astDecl(var,init->inline_ret);
    }

    return astDecl(var,init);
}

Ast *parseVariableInitialiser(Cctrl *cc, Ast *var, s64 terminator_flags) {
    Lexeme *tok = cctrlTokenGet(cc);
    if (tokenPunctIs(tok,'=')) {
        /* as in Go, a local named like an import prefix shadows it only
         * after its declaration: its initialiser still sees the module
         * (`u8 *str = str.format(...)`, see cctrlTokenGet) */
        Ast *outer = cc->decl_in_init;
        cc->decl_in_init = var;
        Ast *ast = parseVariableAssignment(cc,var,terminator_flags,
                                           tok->line,tok->col);
        cc->decl_in_init = outer;
        return ast;
    }
    if (var->type->len == -1) {
        cctrlRaiseException(cc, "Missing array initializer: %s",astToString(var));
    }
    cctrlTokenRewind(cc);
    tok = cctrlTokenGet(cc);

    assertTokenIsTerminator(cc,tok,terminator_flags);
    return astDecl(var,NULL);
}

Ast *parseDecl(Cctrl *cc) {
    AstType *type;
    Lexeme *varname;
    Ast *var, *ast;
    int is_const = 0;

    Lexeme *lead = cctrlTokenPeek(cc);
    if (lead && lead->tk_type == TK_KEYWORD && lead->i64 == KW_CONST) {
        cctrlTokenGet(cc);
        is_const = 1;
    }

    parseDeclInternal(cc,&varname,&type);
    if (varname == NULL) {
        cctrlTokenExpect(cc,';');
        return NULL;
    }
    /* a local may shadow an import prefix for its scope (cctrlTokenGet
     * reports `prefix.name` used there) */
    var = astLVar(type,varname->start,varname->len);
    /* astNew stamped the CURRENT token (post-name); anchor the decl
     * to the name itself for jump-to-definition. */
    var->line = varname->line;
    var->col = varname->col;
    if (is_const) {
        var->flags |= AST_FLAG_CONST;
        if (!tokenPunctIs(cctrlTokenPeek(cc),'=')) {
            cctrlRaiseException(cc,"const '%.*s' needs an initializer",
                    varname->len, varname->start);
        }
    }
    if (!mapAddOrErr(cc->localenv,var->lname->data,var)) {
        cctrlRaiseException(cc,"variable %s already declared",astLValueToString(var,0));
    }
    if (cc->tmp_locals) {
        listAppend(cc->tmp_locals, var);
    }
    ast = parseVariableInitialiser(cc,var,PUNCT_TERM_SEMI|PUNCT_TERM_COMMA);
    if (type->kind == AST_TYPE_AUTO) {
        parseAssignAuto(cc,ast);
    }
    if (is_const && ast->kind == AST_DECL && ast->declinit &&
        astIsIntType(var->type))
    {
        int const_ok = 1;
        s64 const_val = evalIntConstExprOrErr(ast->declinit, &const_ok);
        if (const_ok) {
            var->deref_symbol = const_val;
            var->flags |= AST_FLAG_CONST_VALUE;
        }
    }
    return ast;
}

int parseValidPostControlFlowToken(Lexeme *tok) {
    if (tok == NULL) return 0; /* EOF - caller raises */
    if (tok->tk_type == TK_IDENT) return 1;
    if (tok->tk_type == TK_STR) return 1;
    if (tok->tk_type == TK_PUNCT) {
        switch (tok->i64) {
            case TK_MINUS_MINUS:
            case TK_PLUS_PLUS:
            case '{':
            case ';':
            case '*':
                return 1;
        }
    }
    if (tok->tk_type == TK_KEYWORD) {
        switch (tok->i64) {
            case KW_DO:
            case KW_FOR:
            case KW_GOTO:
            case KW_IF:
            case KW_RETURN:
            case KW_SWITCH:
            case KW_WHILE:
            case KW_BREAK:
            case KW_CONTINUE:
            case KW_TRY:
            case KW_THROW:
            case KW_ASM:
            case KW_DEFER:  /* reported by parseStatement */
                return 1;
        }
    }
    return 0;
}

/* Parse one clause inside an `if (...)` header - either a declaration
 * (`Type name [= expr]`, including pointers) or a plain expression - and
 * report which terminator (';' or ')') closed it. Used to support C++17
 * style `if (init-statement; condition)` and a declaration used directly
 * as the condition. The declared variable is registered in the current
 * (if-scoped) localenv. */
/* Recovery from an error inside the `( ... )` of an if, while, do-while,
 * for or switch. Without it the statement-level recovery resyncs to the
 * next `;`, which inside `if (cond) a; else b;` is the one before the
 * `else`, and the `else` is then reported as a second, bogus error. With
 * it the rest of the parentheses is skipped and the statement - its body
 * and any `else` - is parsed as usual, so only real errors are reported.
 *
 * The end of the parentheses is found in the source text when they are
 * opened (the token buffer can't look that far ahead): the matching
 * close, or, when that is missing, the `{`, `}` (or `;` where one can't
 * appear: anywhere but the top level of `for (a; b; c)` and
 * `if (init; cond)`) that the condition runs into, which is left for the
 * body. */
typedef struct ParseCondGuard {
    int armed;
    int end_line, end_col;  /* the closing token, or the one to stop at */
    int stop_before;        /* the close is missing: keep the end token */
    jmp_buf *outer;
    Map *localenv;
} ParseCondGuard;

/* `open` is the `(` (or `[`) just consumed. `allow_semi` for the `;`s of
 * `for (a; b; c)` and `if (init; cond)`. */
static void parseCondGuardInit(Cctrl *cc, ParseCondGuard *g, Lexeme *open,
                               int allow_semi)
{
    g->armed = 0;
    g->outer = cc->current_recovery;
    g->localenv = cc->localenv;
    LexFile *open_file = cc->lexer_ ? cctrlCurFile(cc) : NULL;
    if (!open || !open_file || !open_file->src || open->line <= 0 ||
        open->col <= 0) {
        return;
    }
    char *src = open_file->src->data;
    s64 size = open_file->src->len;
    s64 i = 0, line = 1;
    while (i < size && line < open->line) {
        if (src[i] == '\n') line++;
        i++;
    }
    s64 line_start = i;
    i += open->col - 1;
    if (i >= size || (src[i] != '(' && src[i] != '[')) {
        return; /* not where the token says: a macro, an include */
    }
    char close = src[i] == '(' ? ')' : ']';
    int depth = 1;
    /* The first `;` at the top: where an unclosed condition most likely
     * ends, as in `if (F((1, 2)) a; else b;` */
    s64 semi = -1, semi_line = 0, semi_line_start = 0;
    s64 limit = i + 20000;
    i++;
    while (i < size && i < limit && src[i]) {
        char c = src[i];
        if (c == '\n') {
            line++;
            line_start = i + 1;
        } else if (c == '"' || c == '\'') {
            /* a string or character constant: skip to its end */
            i++;
            while (i < size && src[i] && src[i] != c) {
                if (src[i] == '\\' && i + 1 < size) i++;
                if (src[i] == '\n') {
                    line++;
                    line_start = i + 1;
                }
                i++;
            }
        } else if (c == '/' && i + 1 < size && src[i + 1] == '/') {
            while (i < size && src[i] && src[i] != '\n') i++;
            continue;
        } else if (c == '/' && i + 1 < size && src[i + 1] == '*') {
            i += 2;
            while (i + 1 < size && !(src[i] == '*' && src[i + 1] == '/')) {
                if (src[i] == '\n') {
                    line++;
                    line_start = i + 1;
                }
                i++;
            }
            i++;
        } else if (c == '(' || c == '[') {
            depth++;
        } else if (c == ')' || c == ']') {
            if (--depth == 0) {
                if (c != close) return;
                g->stop_before = 0;
                break;
            }
        } else if (c == '{' || c == '}' ||
                   (c == ';' && (!allow_semi || depth > 1))) {
            /* A `;` is only at the top of `for (a; b; c)` */
            g->stop_before = 1;
            if (c != ';' && semi >= 0) {
                i = semi;
                line = semi_line;
                line_start = semi_line_start;
            }
            break;
        } else if (c == ';' && semi < 0) {
            semi = i;
            semi_line = line;
            semi_line_start = line_start;
        }
        i++;
    }
    if (i >= size || i >= limit || !src[i]) {
        return;
    }
    g->end_line = (int)line;
    g->end_col = (int)(i - line_start + 1);
    g->armed = 1;
}

/* Called from the guard's setjmp: the error is reported, skip what is
 * left of the parentheses. An error after them (a range `for` parses its
 * body inside its parentheses) goes on to the outer recovery. */
static void parseCondGuardRecover(Cctrl *cc, ParseCondGuard *g) {
    cc->current_recovery = g->outer;
    cc->localenv = g->localenv;
    Lexeme *tok = cctrlTokenPeek(cc);
    if (tok && (tok->line > g->end_line ||
                (tok->line == g->end_line && tok->col > g->end_col))) {
        /* Past the end: fine when the close itself was the last token
         * read (`while ()`), else the error was after the parentheses */
        cctrlTokenRewind(cc);
        Lexeme *last = cctrlTokenGet(cc);
        if (last && last->line == g->end_line && last->col == g->end_col) {
            /* The `{` a condition missing its `)` runs into is the
             * body's: give it back */
            if (g->stop_before) cctrlTokenRewind(cc);
            return;
        }
        cctrlTerminate(cc);
    }
    while ((tok = cctrlTokenPeek(cc)) != NULL) {
        if (tok->line > g->end_line ||
            (tok->line == g->end_line && tok->col >= g->end_col)) {
            if (!g->stop_before && tok->line == g->end_line &&
                tok->col == g->end_col) {
                cctrlTokenGet(cc);
            }
            break;
        }
        cctrlTokenGet(cc);
    }
}

/* Whether the condition of an if, while, for or switch is written in
 * parentheses, `if (x > 0) {`, or without them as in Go, `if x > 0 {`.
 * Both mean the same. A condition can start with a `(` of its own, as
 * `if (a + b) * 2 > c {` does, so a leading `(` only wraps the whole
 * condition when what follows its matching `)` can't continue an
 * expression: the `{` of the body (or the `;` after `do { } while (x)`),
 * or a statement, which is reported as a body missing its braces. The
 * matching `)` is found in the source text, as parseCondGuardInit does. */
/* Where the token `tok` starts in the source text of the current file,
 * or -1 when that is past its end */
static s64 parseTokenSrcOffset(Cctrl *cc, Lexeme *tok, char **src_out,
                               s64 *size_out)
{
    LexFile *file = cc->lexer_ ? cctrlCurFile(cc) : NULL;
    if (!tok || !file || !file->src || tok->line <= 0 || tok->col <= 0) {
        return -1;
    }
    char *src = file->src->data;
    s64 size = file->src->len;
    s64 i = 0, line = 1;
    while (i < size && line < tok->line) {
        if (src[i] == '\n') line++;
        i++;
    }
    i += tok->col - 1;
    if (i >= size) return -1;
    *src_out = src;
    *size_out = size;
    return i;
}

static int parseCondInParens(Cctrl *cc) {
    Lexeme *open = cctrlTokenPeek(cc);
    if (!tokenPunctIs(open, '(')) return 0;
    char *src;
    s64 size;
    s64 i = parseTokenSrcOffset(cc, open, &src, &size);
    if (i < 0 || src[i] != '(') {
        return 1; /* not where the token says: a macro, an include */
    }
    int depth = 0;
    while (i < size && src[i]) {
        char c = src[i];
        if (c == '"' || c == '\'') {
            i++;
            while (i < size && src[i] && src[i] != c) {
                if (src[i] == '\\' && i + 1 < size) i++;
                i++;
            }
        } else if (c == '/' && i + 1 < size && src[i + 1] == '/') {
            while (i < size && src[i] && src[i] != '\n') i++;
            continue;
        } else if (c == '/' && i + 1 < size && src[i + 1] == '*') {
            i += 2;
            while (i + 1 < size && !(src[i] == '*' && src[i + 1] == '/')) i++;
            i++;
        } else if (c == '(' || c == '[') {
            depth++;
        } else if (c == ')' || c == ']') {
            if (--depth == 0) break;
        } else if (c == '{' || c == '}' || c == ';') {
            return 1; /* unclosed: the parenthesised form reports it */
        }
        i++;
    }
    if (i >= size || !src[i]) return 1;
    /* the first character after the `)`, past spaces and comments */
    i++;
    while (i < size && src[i]) {
        if (isspace((unsigned char)src[i])) {
            i++;
        } else if (src[i] == '/' && i + 1 < size && src[i + 1] == '/') {
            while (i < size && src[i] && src[i] != '\n') i++;
        } else if (src[i] == '/' && i + 1 < size && src[i + 1] == '*') {
            i += 2;
            while (i + 1 < size && !(src[i] == '*' && src[i + 1] == '/')) i++;
            i += 2;
        } else {
            break;
        }
    }
    if (i >= size || !src[i]) return 1;
    char c = src[i], next = i + 1 < size ? src[i + 1] : '\0';
    if ((c == '+' || c == '-') && next == c) return 1;   /* `++x;` */
    return strchr(".[(<>=!&|^?/%+-*", c) == NULL;
}

/* The guard for a condition without parentheses: it ends at the `{` of
 * the body, the first one at the top, which is left for the body */
static void parseCondGuardInitBare(Cctrl *cc, ParseCondGuard *g) {
    g->armed = 0;
    g->outer = cc->current_recovery;
    g->localenv = cc->localenv;
    char *src;
    s64 size;
    Lexeme *tok = cctrlTokenPeek(cc);
    s64 i = parseTokenSrcOffset(cc, tok, &src, &size);
    if (i < 0) return;
    s64 line = tok->line, line_start = i - (tok->col - 1);
    int depth = 0;
    s64 limit = i + 20000;
    while (i < size && i < limit && src[i]) {
        char c = src[i];
        if (c == '\n') {
            line++;
            line_start = i + 1;
        } else if (c == '"' || c == '\'') {
            i++;
            while (i < size && src[i] && src[i] != c) {
                if (src[i] == '\\' && i + 1 < size) i++;
                if (src[i] == '\n') {
                    line++;
                    line_start = i + 1;
                }
                i++;
            }
        } else if (c == '/' && i + 1 < size && src[i + 1] == '/') {
            while (i < size && src[i] && src[i] != '\n') i++;
            continue;
        } else if (c == '/' && i + 1 < size && src[i + 1] == '*') {
            i += 2;
            while (i + 1 < size && !(src[i] == '*' && src[i + 1] == '/')) {
                if (src[i] == '\n') {
                    line++;
                    line_start = i + 1;
                }
                i++;
            }
            i++;
        } else if (c == '(' || c == '[') {
            depth++;
        } else if (c == ')' || c == ']') {
            if (depth > 0) depth--;
        } else if (c == '}') {
            return;
        } else if (c == '{' && depth == 0) {
            g->end_line = (int)line;
            g->end_col = (int)(i - line_start + 1);
            g->stop_before = 1;
            g->armed = 1;
            return;
        }
        i++;
    }
}

/* `for cond {`, a header without parentheses that is only a condition:
 * no `;` before the `{` of the body. A declaration starts the three-part
 * or the range form. */
static int parseForIsCondOnly(Cctrl *cc) {
    Lexeme *tok = cctrlTokenPeek(cc);
    if (!tok) return 0;
    if (tok->tk_type == TK_KEYWORD && tok->i64 == KW_CONST) return 0;
    if ((cctrlTokIsKeyword(cc, tok) && !parseTypeNameFollowedByDot(cc)) ||
        (tokenMayStartFnType(tok) && parseFnLooksLikeType(cc))) {
        return 0;
    }
    char *src;
    s64 size;
    s64 i = parseTokenSrcOffset(cc, tok, &src, &size);
    if (i < 0) return 0;
    int depth = 0;
    while (i < size && src[i]) {
        char c = src[i];
        if (c == '"' || c == '\'') {
            i++;
            while (i < size && src[i] && src[i] != c) {
                if (src[i] == '\\' && i + 1 < size) i++;
                i++;
            }
        } else if (c == '/' && i + 1 < size && src[i + 1] == '/') {
            while (i < size && src[i] && src[i] != '\n') i++;
            continue;
        } else if (c == '/' && i + 1 < size && src[i + 1] == '*') {
            i += 2;
            while (i + 1 < size && !(src[i] == '*' && src[i + 1] == '/')) i++;
            i++;
        } else if (c == '(' || c == '[') {
            depth++;
        } else if (c == ')' || c == ']') {
            depth--;
        } else if (c == ';' || c == '}') {
            return 0;
        } else if (c == '{' && depth == 0) {
            return 1;
        }
        i++;
    }
    return 0;
}

/* A condition without parentheses, `what` being "the `while` condition":
 * a `{` straight away is the body, left for it, not a missing value */
static Ast *parseBareCond(Cctrl *cc, char *what) {
    Lexeme *peek = cctrlTokenPeek(cc);
    if (tokenPunctIs(peek, '{')) {
        cctrlRaiseExceptionAt(cc, peek->line, peek->col, peek->len,
                              "Expected %s, got `{`", what);
    }
    return parseRequiredExpr(cc, what);
}

/* The `{` of a body after a condition written without parentheses: there
 * is nothing else the condition can be followed by */
static void parseExpectBodyAfterCond(Cctrl *cc, const char *what) {
    Lexeme *peek = cctrlTokenPeek(cc);
    if (!peek) {
        cctrlRaiseException(cc, "Unexpected end of input, expected the "
                            "body of `%s`", what);
    }
    if (!tokenPunctIs(peek, '{')) {
        cctrlRaiseExceptionAt(cc, peek->line, peek->col, peek->len,
            "expected `{` after the `%s` condition, got `%s`",
            what, lexemeAsWritten(peek));
    }
}

/* A copy of the token just consumed */
static Lexeme parseConsumedToken(Cctrl *cc) {
    cctrlTokenRewind(cc);
    return *cctrlTokenGet(cc);
}

/* Arm the guard: errors from here jump to `jb` */
static void parseCondGuardArm(Cctrl *cc, ParseCondGuard *g, jmp_buf *jb) {
    if (g->armed) {
        cc->current_recovery = jb;
    }
}

/* The parentheses parsed: errors go to the outer recovery again */
static void parseCondGuardDone(Cctrl *cc, ParseCondGuard *g) {
    cc->current_recovery = g->outer;
}

/* An expression where one is required: parseExpr gives NULL when the next
 * token can't start one (`while ()`), which compiled to nothing or
 * crashed later. Reported at that token. */
Ast *parseRequiredExpr(Cctrl *cc, char *what) {
    Ast *expr = parseExpr(cc,16);
    if (!expr) {
        Lexeme *bad = cctrlTokenGet(cc);
        if (!bad) {
            cctrlRaiseException(cc, "Unexpected end of input, expected %s", what);
        }
        cctrlRaiseException(cc, "Expected %s, got `%s`", what,
                            lexemeAsWritten(bad));
    }
    return expr;
}

/* `parens`: the condition is in `( ... )`, else it runs up to the `{` of
 * the body, which is left for the body */
static Ast *parseIfClause(Cctrl *cc, char *term_out, int parens) {
    Ast *clause;
    Lexeme *tok = cctrlTokenPeek(cc);
    if (tok && (cctrlTokIsKeyword(cc, tok) ||
                (tokenMayStartFnType(tok) && parseFnLooksLikeType(cc)))) {
        AstType *type = parseDeclSpec(cc);
        if (tokenPunctIs(cctrlTokenPeek(cc), '.')) {
            /* `if (Color.red == c)`: an enum member reference, not an
             * `if (Type name)` declaration. `type` is a bare name here
             * (no `*`), so rewinding once puts it back. */
            cctrlTokenRewind(cc);
            clause = parseExpr(cc, 16);
        } else {
            Lexeme *name = cctrlTokenGet(cc);
            if (!name || name->tk_type != TK_IDENT) {
                cctrlRaiseException(cc,
                    "Expected identifier in `if` declaration, got `%.*s`",
                    name ? name->len : 0, name ? name->start : "");
            }
            Ast *var = astLVar(type, name->start, name->len);
            var->line = name->line;
            var->col = name->col;
            if (!mapAddOrErr(cc->localenv, var->lname->data, var)) {
                cctrlRaiseException(cc, "variable %s already declared",
                                    astLValueToString(var, 0));
            }
            if (cc->tmp_locals) {
                listAppend(cc->tmp_locals, var);
            }
            Lexeme *eq = cctrlTokenGet(cc);
            if (tokenPunctIs(eq, '=')) {
                Ast *init = parseRequiredExpr(cc, "an initial value");
                clause = astDecl(var, init);
                if (type->kind == AST_TYPE_AUTO) {
                    parseAssignAuto(cc, clause);
                }
            } else {
                cctrlTokenRewind(cc);
                clause = astDecl(var, NULL);
            }
        }
    } else {
        clause = parseExpr(cc, 16);
    }

    Lexeme *t = cctrlTokenGet(cc);
    if (tokenPunctIs(t, ';'))      *term_out = ';';
    else if (parens && tokenPunctIs(t, ')')) *term_out = ')';
    else if (!parens && tokenPunctIs(t, '{')) {
        cctrlTokenRewind(cc);
        *term_out = ')';
    } else {
        /* Point at `t`, not at the token after it */
        if (t) cctrlTokenRewind(cc);
        if (parens) {
            cctrlRaiseException(cc,
                "Expected ';' or ')' in `if (...)`, got `%.*s`",
                t ? t->len : 0, t ? t->start : "");
        }
        cctrlRaiseException(cc,
            "Expected ';' or `{` after the `if` condition, got `%.*s`",
            t ? t->len : 0, t ? t->start : "");
    }
    return clause;
}

/* Y13: the body of an if, else, for, while or do is a `{ ... }` block
 * (`else if` aside): a lone statement there reads as guarded when the
 * next one isn't, or the other way round */
static Ast *parseBracedBody(Cctrl *cc, const char *what) {
    Lexeme *peek = cctrlTokenPeek(cc);
    if (peek && !tokenPunctIs(peek, '{')) {
        /* reported, and the statement parsed on as the body */
        cctrlErrorAt(cc, peek->line, peek->col, peek->len,
            "braces required: the body of `%s` goes in `{ ... }`", what);
        if (peek->tk_type == TK_KEYWORD && peek->i64 == KW_DEFER) {
            /* (not "`defer` must be in a block" too): skip it */
            cctrlSyncStatement(cc);
            return NULL;
        }
    }
    return parseStatement(cc);
}

Ast *parseIfStatement(Cctrl *cc) {
    int parens = parseCondInParens(cc);
    Lexeme open = {0};
    if (parens) {
        cctrlTokenExpect(cc,'(');
        open = parseConsumedToken(cc);
    }

    /* C++17 `if (init; cond)`: zero or more `;`-separated init statements
     * followed by the condition. The init declarations and a
     * declaration-condition are scoped to the if (and its bodies), so we
     * open a child environment and, when present, desugar to a block. */
    cc->localenv = cctrlCreateAstMap(cc->localenv);
    List *pre = listNew();
    Ast *volatile cond_clause = NULL;
    ParseCondGuard guard;
    jmp_buf cond_recovery;
    if (parens) parseCondGuardInit(cc, &guard, &open, 1);
    else parseCondGuardInitBare(cc, &guard);
    if (setjmp(cond_recovery) != 0) {
        /* Reported; the error stops the compile, parse on for the body */
        parseCondGuardRecover(cc, &guard);
        cond_clause = astI64Type(1);
    } else {
        parseCondGuardArm(cc, &guard, &cond_recovery);
        while (1) {
            char term = 0;
            Ast *clause = parseIfClause(cc, &term, parens);
            if (term == ')') {
                if (!clause) {
                    /* `if ()`: the `)` was the last token read */
                    cctrlRaiseException(cc,
                            "Expected the `if` condition, got `%s`",
                            parens ? ")" : "{");
                }
                cond_clause = clause;
                break;
            }
            if (clause) listAppend(pre, clause);   /* an init statement */
        }
        parseCondGuardDone(cc, &guard);
    }

    /* A declaration used as the condition evaluates to the declared
     * variable; emit the declaration first, then test the variable. */
    Ast *cond;
    if (cond_clause->kind == AST_DECL) {
        listAppend(pre, cond_clause);
        cond = cond_clause->declvar;
    } else {
        cond = cond_clause;
    }
    parseCheckCondition(cc, cond);

    Lexeme *peek = cctrlTokenPeek(cc);
    if (peek == NULL) {
        cctrlRaiseException(cc, "Unexpected end of input");
    }
    if (!parseValidPostControlFlowToken(peek)) {
        cctrlRaiseException(cc,"Unexpected %s `%s` while parsing if body",
                lexemeTypeToString(peek->tk_type), lexemeAsWritten(peek));
    }

    Ast *then = parseBracedBody(cc, "if");
    Lexeme *tok = cctrlTokenGet(cc);
    Ast *els = NULL;

    if (tok && tok->tk_type == TK_KEYWORD && tok->i64 == KW_ELSE) {
        Lexeme *epeek = cctrlTokenPeek(cc);
        if (!epeek) {
            cctrlRaiseException(cc,"Unexpected end of input after `else`");
        }
        if (!parseValidPostControlFlowToken(epeek)) {
            cctrlTokenRewind(cc);
            cctrlTokenRewind(cc);
            cctrlRaiseException(cc,"Unexpected %s `%s` while parsing else body",
                    lexemeTypeToString(epeek->tk_type), lexemeAsWritten(epeek));
        }
        els = epeek->tk_type == TK_KEYWORD && epeek->i64 == KW_IF
            ? parseStatement(cc)        /* else if */
            : parseBracedBody(cc, "else");
    } else {
        cctrlTokenRewind(cc);
    }

    cc->localenv = cc->localenv->parent;

    Ast *if_ast = astIf(cond, then, els);
    if (listEmpty(pre)) {
        return if_ast;
    }
    listAppend(pre, if_ast);
    return astCompountStatement(pre);
}

Ast *parseOptDeclOrStmt(Cctrl *cc) {
    Lexeme *tok = cctrlTokenGet(cc);
    if (tokenPunctIs(tok,';')) {
        return NULL;
    }
    cctrlTokenRewind(cc);
    return parseDeclOrStatement(cc);
}

Ast *parseOptExpr(Cctrl *cc) {
    Lexeme *tok = cctrlTokenGet(cc);
    if (tokenPunctIs(tok,';')) {
        return NULL;
    }
    cctrlTokenRewind(cc);
    Ast *ast = parseExpr(cc,16);
    tok = cctrlTokenGet(cc);
    assertTokenIsTerminatorWithMsg(cc,tok,PUNCT_TERM_SEMI|PUNCT_TERM_COMMA, "for loop requires either a conditional statement or a semi colon to terminate the initalisation");
    return ast;
}

/* Parse the body of a range loop and prepend the declaration of the loop
 * variable. A body without braces is a single statement, so it is wrapped
 * in a compound statement to have a statement list to prepend to. */
/* The `for` being parsed has its header in `( ... )`: a range loop
 * closes it before its body. Set by parseForStatement. */
static int parse_for_parens = 1;

static void parseRangeLoopClose(Cctrl *cc) {
    if (parse_for_parens) cctrlTokenExpect(cc,')');
    else parseExpectBodyAfterCond(cc, "for");
}

static Ast *parseRangeLoopBody(Cctrl *cc, Ast *iterator) {
    Ast *forbody = parseBracedBody(cc, "for");
    if (forbody == NULL || forbody->kind != AST_COMPOUND_STMT) {
        List *stmts = listNew();
        if (forbody) listAppend(stmts,forbody); /* NULL: an empty `;` body */
        forbody = astCompountStatement(stmts);
    }
    listPrepend(forbody->stms,iterator);
    return forbody;
}

Ast *parseDesugarArrayLoop(Cctrl *cc, Ast *iteratee, Ast *static_array) {
    /* Create a temporay variable as the counter */
    AoStr *range_tmp_var = getRangeLoopIdx();
    Ast *counter_var = astLVar(astTypeCopy(ast_int_type),range_tmp_var->data,
                               range_tmp_var->len);

    Ast *counter = astDecl(counter_var,astI64Type(0));

    /* How much memory it takes up / size of one element */
    Ast *array_len = astI64Type(static_array->type->size/static_array->type->ptr->size);

    if (iteratee->type->kind == AST_TYPE_AUTO) {
        AstType *deref_type = static_array->type->ptr;
        iteratee->type = deref_type;
    }

    mapAddLen(cc->localenv,range_tmp_var->data,
                 range_tmp_var->len,counter_var);
    mapAdd(cc->localenv,iteratee->lname->data,iteratee);

    if (cc->tmp_locals) {
        listAppend(cc->tmp_locals,counter_var);
        listAppend(cc->tmp_locals,iteratee);
    }

    Ast *cond = parseCreateBinaryOp(cc,AST_BIN_OP_LT, counter_var, array_len);


    Ast *iterator = astDecl(iteratee,
            astUnaryOperator(static_array->type->ptr,
                AST_UN_OP_DEREF,
                parseCreateBinaryOp(cc,AST_BIN_OP_ADD, static_array, counter_var))
            );
    Ast *step = astUnaryOperator(astTypeCopy(ast_int_type),AST_UN_OP_PRE_INC,counter_var);

    parseRangeLoopClose(cc);
    Ast *forbody = parseRangeLoopBody(cc,iterator);
    return astFor(counter,cond,step,forbody,NULL,NULL,NULL);
}

void parseAssertContainerHasFields(Cctrl *cc, AstType *size_field, 
                                   AstType *entries_field)
{
    if (!size_field) {
        cctrlRaiseException(cc,"Range for loop must be on a struct with both a 'size' and 'entries' property");
    }

    if (!astIsIntType(size_field)) {
        cctrlRaiseException(cc,"Range for loop struct's size field must be an int got: %s",
                astTypeToColorString(size_field));
    }

    if (!entries_field) {
        cctrlRaiseException(cc,"Range for loop must be on a struct with both a 'size' and 'entries' property");
    }

    if (entries_field->kind != AST_TYPE_POINTER && entries_field->kind != AST_TYPE_ARRAY) {
        cctrlRaiseException(cc,"'entries' field must be a pointer or array got '%s'",
                astTypeKindToString(entries_field->kind));
    }

    if (entries_field->ptr->kind == AST_TYPE_VOID) {
        cctrlRaiseException(cc,"cannot dereference void pointer");
    }
    parseCheckPrivateField(cc, size_field, "size", 4);
    parseCheckPrivateField(cc, entries_field, "entries", 7);
}

Ast *parseCreateForRange(Cctrl *cc, Ast *iteratee,
                         Ast *size_ref, Ast *entries_ref)
{
    AoStr *range_tmp_var = getRangeLoopIdx();
    Ast *counter_var = astLVar(astTypeCopy(ast_int_type),range_tmp_var->data,
                               range_tmp_var->len);
    Ast *counter = astDecl(counter_var,astI64Type(0));

    mapAddLen(cc->localenv,range_tmp_var->data,
                 range_tmp_var->len,counter_var);
    mapAdd(cc->localenv,iteratee->lname->data,iteratee);
    if (cc->tmp_locals) {
        listAppend(cc->tmp_locals,counter_var);
        listAppend(cc->tmp_locals,iteratee);
    }

    Ast *cond = parseCreateBinaryOp(cc,AST_BIN_OP_LT, counter_var, size_ref);
    Ast *iterator = astDecl(iteratee,
            astUnaryOperator(entries_ref->type->ptr,
                AST_UN_OP_DEREF,
                parseCreateBinaryOp(cc,AST_BIN_OP_ADD, entries_ref, counter_var))
            );
    Ast *step = astUnaryOperator(astTypeCopy(ast_int_type),AST_UN_OP_PRE_INC,counter_var);
    parseRangeLoopClose(cc);
    Ast *forbody = parseRangeLoopBody(cc,iterator);
    return astFor(counter,cond,step,forbody,NULL,NULL,NULL);
}

Ast *parseRangeLoop(Cctrl *cc, Ast *iteratee) {
    Lexeme *colon = cctrlTokenGet(cc);
    Lexeme *next = cctrlTokenPeek(cc);
    if (next && tokenPunctIs(next, '[')) {
        cctrlRaiseExceptionAt(cc, next->line, next->col, 1,
            "ktm has no array literals: declare the array first, as in "
            "`u8 *names[2] = { \"a\", \"b\" };` and `for (auto %s : names)`",
            iteratee->lname->data);
    }
    Ast *container = parseExpr(cc,16);
    if (!container) {
        cctrlRaiseExceptionAt(cc, colon->line, colon->col, 1,
            "expected what to range over after `:`, as in `for (auto %s : items)`",
            iteratee->lname->data);
    }

    /* `for (auto v : s)` over a slice: its ptr[0] to ptr[len - 1]. A slice
     * that is not a variable or field (`str.split(...)`) is computed once,
     * into a hidden local, not on every pass. */
    if (container->type && astTypeIsSlice(container->type)) {
        Ast *once = NULL;
        if (container->kind != AST_LVAR && container->kind != AST_GVAR &&
            container->kind != AST_CLASS_REF) {
            AoStr *tmp_name = getRangeLoopIdx();
            Ast *tmp = astLVar(container->type, tmp_name->data, tmp_name->len);
            mapAddLen(cc->localenv, tmp_name->data, tmp_name->len, tmp);
            if (cc->tmp_locals) listAppend(cc->tmp_locals, tmp);
            once = astDecl(tmp, container);
            container = tmp;
        }
        Map *fields = container->type->fields;
        Ast *len_ref = astClassRef(mapGetLen(fields, str_lit("len")), container, "len");
        Ast *ptr_ref = astClassRef(mapGetLen(fields, str_lit("ptr")), container, "ptr");
        if (iteratee->type->kind == AST_TYPE_AUTO) {
            iteratee->type = container->type->elem;
        }
        Ast *loop = parseCreateForRange(cc, iteratee, len_ref, ptr_ref);
        if (once) {
            List *init = listNew();
            listAppend(init, once);
            listAppend(init, loop->forinit);
            loop->forinit = astCompountStatement(init);
        }
        return loop;
    }
    /* a struct with `len` and `data` (vec.Vec<T>), held by value or
     * through a pointer: data[0] to data[len - 1] */
    {
        AstType *st = container->type;
        int through_ptr = st && st->kind == AST_TYPE_POINTER;
        if (through_ptr) st = st->ptr;
        AstType *len_field = st && st->kind == AST_TYPE_CLASS && st->fields
                           ? mapGetLen(st->fields, str_lit("len")) : NULL;
        AstType *data_field = len_field ? mapGetLen(st->fields, str_lit("data")) : NULL;
        if (len_field && data_field && data_field->kind == AST_TYPE_POINTER &&
            astIsIntType(len_field) && (container->kind == AST_LVAR ||
                                        container->kind == AST_GVAR ||
                                        container->kind == AST_CLASS_REF ||
                                        through_ptr)) {
            Ast *base = through_ptr ? astUnaryOperator(st, AST_UN_OP_DEREF, container) : container;
            Ast *len_ref = astClassRef(len_field, base, "len");
            Ast *data_ref = astClassRef(data_field, base, "data");
            if (iteratee->type->kind == AST_TYPE_AUTO) iteratee->type = data_field->ptr;
            return parseCreateForRange(cc, iteratee, len_ref, data_ref);
        }
    }
    if (container->kind == AST_LVAR) {
        if (container->type->kind == AST_TYPE_POINTER) {
            if (container->type->ptr->kind != AST_TYPE_CLASS) {
                cctrlRaiseException(cc,"pointer '%s' has no fields; range requires 'i64 size' and '<type> *entries'",
                        astLValueToString(container, 0));
            }

            AstType *size_field = mapGetLen(container->type->ptr->fields, str_lit("size"));
            AstType *entries_field = mapGetLen(container->type->ptr->fields, str_lit("entries"));

            parseAssertContainerHasFields(cc,size_field,entries_field);

            Ast *deref = astUnaryOperator(container->type->ptr,
                                          AST_UN_OP_DEREF,
                                          container);
            Ast *size_ref = astClassRef(size_field,deref,"size");
            Ast *entries_ref = astClassRef(entries_field,deref,"entries");

            if (iteratee->type->kind == AST_TYPE_AUTO) {
                AstType *deref_type = entries_field->ptr;
                iteratee->type = deref_type;
            }

            return parseCreateForRange(cc, iteratee, size_ref, entries_ref);
        } else if (container->type->kind == AST_TYPE_ARRAY) {
            return parseDesugarArrayLoop(cc,iteratee,container);
        } else {
            cctrlRaiseException(cc,"can only range over arrays and pointers");
        }
    } else if (container->kind == AST_CLASS_REF) {
        Map *fields = NULL;
        if (container->type->kind == AST_TYPE_POINTER) {
            fields = container->type->ptr->fields;
        } else if (container->type->kind == AST_TYPE_ARRAY) {
            return parseDesugarArrayLoop(cc,iteratee,container);
        } else {
            fields = container->type->fields;
        }

        AstType *size_field = mapGetLen(fields, str_lit("size"));
        AstType *entries_field = mapGetLen(fields, str_lit("entries"));

        parseAssertContainerHasFields(cc,size_field,entries_field);

        /* A class held by value is used as is, a pointer is dereferenced */
        Ast *deref = container;
        if (container->type->kind == AST_TYPE_POINTER) {
            deref = astUnaryOperator(container->type->ptr,AST_UN_OP_DEREF,container);
        }
        Ast *size_ref = astClassRef(size_field,deref,"size");
        Ast *entries_ref = astClassRef(entries_field,deref,"entries");

        if (iteratee->type->kind == AST_TYPE_AUTO) {
            AstType *deref_type = entries_field->ptr;
            iteratee->type = deref_type;
        }

        return parseCreateForRange(cc, iteratee, size_ref, entries_ref);
    }
    cctrlRaiseException(cc,"Can only handle lvars, arrays and class references at this time got: %s %s",
                astKindToString(container->kind), astToString(container));
}

/* A for loop's declaration `var = init` up to and including its `,` or
 * `;` */
static Ast *parseForDeclInit(Cctrl *cc, Ast *var, int is_const) {
    if (cc->tmp_locals) {
        listAppend(cc->tmp_locals, var);
    }
    int is_auto = var->type->kind == AST_TYPE_AUTO;
    Ast *ast = parseVariableInitialiser(cc,var,PUNCT_TERM_SEMI|PUNCT_TERM_COMMA);
    if (is_auto) {
        parseAssignAuto(cc,ast);
    }
    if (is_const && ast->kind == AST_DECL && ast->declinit &&
        astIsIntType(var->type))
    {
        int const_ok = 1;
        s64 const_val = evalIntConstExprOrErr(ast->declinit, &const_ok);
        if (const_ok) {
            var->deref_symbol = const_val;
            var->flags |= AST_FLAG_CONST_VALUE;
        }
    }
    return ast;
}

/* Either parse the initialiser or a range loop. Range loop is experimental */
Ast *parseForLoopInitialiser(Cctrl *cc) {
    Lexeme *tok = cctrlTokenGet(cc);
    if (tokenPunctIs(tok,';')) {
        return NULL;
    }
    cctrlTokenRewind(cc);
    
    tok = cctrlTokenPeek(cc);
    if (!tok) {
        return NULL;
    }

    int is_const = 0;
    if (tok->tk_type == TK_KEYWORD && tok->i64 == KW_CONST) {
        is_const = 1;
        cctrlTokenGet(cc);
        tok = cctrlTokenPeek(cc);
        if (!tok) {
            cctrlRaiseException(cc,"Unexpected end of input after `const`");
        }
    }

    if ((cctrlTokIsKeyword(cc, tok) &&
         !parseTypeNameFollowedByDot(cc)) ||
        (tokenMayStartFnType(tok) && parseFnLooksLikeType(cc))) {
        /* the base type for more declarations after a `,` */
        AstType *base_type = parseBaseDeclSpec(cc);
        if (base_type == NULL) {
            cctrlRaiseException(cc, "Unexpected end of input, expected a type");
        }
        AstType *type = parsePointerType(cc, base_type);
        tok = cctrlTokenGetRequired(cc);
        if (tok->tk_type != TK_IDENT) {
            cctrlTokenRewind(cc);
            cctrlRaiseException(cc,"expected an identifier, got `%s`",
                    lexemeAsWritten(tok));
        }

        /* We have a for loop variable */
        Ast *init_var = astLVar(type,tok->start,tok->len);
        if (is_const) {
            init_var->flags |= AST_FLAG_CONST;
        }

        /* can be : for an auto loop or ';' for a normal loop */
        tok = cctrlTokenPeek(cc);
        if (!mapAddOrErr(cc->localenv,init_var->lname->data,init_var)) {
            cctrlRaiseException(cc,"variable `%s` already declared!",
                    astLValueToString(init_var,0));
        }

        /* For this to work the type must have:
         * 1) a pointer called entries
         * 2) a size value that must be an integer */
        if (tokenPunctIs(tok,':')) {
            return parseRangeLoop(cc,init_var);
        } else if (tokenPunctIs(tok, '=')) {
            Ast *ast = parseForDeclInit(cc, init_var, is_const);
            cctrlTokenRewind(cc);
            if (!tokenPunctIs(cctrlTokenGet(cc), ',')) return ast;
            /* `for (i64 i = 0, j = n; ...)`: more declarations of the
             * base type, each with its own `*`s */
            List *decls = listNew();
            listAppend(decls, ast);
            do {
                type = parsePointerType(cc, base_type);
                tok = cctrlTokenGetRequired(cc);
                if (tok->tk_type != TK_IDENT) {
                    cctrlTokenRewind(cc);
                    cctrlRaiseException(cc,"expected an identifier, got `%s`",
                            lexemeAsWritten(tok));
                }
                Ast *var = astLVar(type,tok->start,tok->len);
                var->line = tok->line;
                var->col = tok->col;
                if (is_const) var->flags |= AST_FLAG_CONST;
                if (!mapAddOrErr(cc->localenv,var->lname->data,var)) {
                    cctrlRaiseException(cc,"variable `%s` already declared!",
                            astLValueToString(var,0));
                }
                if (is_const && !tokenPunctIs(cctrlTokenPeek(cc),'=')) {
                    cctrlRaiseException(cc,"const '%s' needs an initializer",
                            var->lname->data);
                }
                listAppend(decls, parseForDeclInit(cc, var, is_const));
                cctrlTokenRewind(cc);
            } while (tokenPunctIs(cctrlTokenGet(cc), ','));
            return astCompountStatement(decls);
        } else if (is_const) {
            cctrlRaiseException(cc,"const '%s' needs an initializer",
                    init_var->lname->data);
        }
    } else if (is_const) {
        cctrlRaiseException(cc,"const is only allowed on declarations");
    }
    /* An expression ends at a `,` or the `;`: `for (k = 0, i = n; ...)`
     * runs each in turn */
    Ast *first = parseStatement(cc);
    cctrlTokenRewind(cc);
    if (!tokenPunctIs(cctrlTokenGet(cc), ',')) return first;
    List *inits = listNew();
    if (first) listAppend(inits, first);
    do {
        Ast *next = parseStatement(cc);
        if (next) listAppend(inits, next);
        cctrlTokenRewind(cc);
    } while (tokenPunctIs(cctrlTokenGet(cc), ','));
    return astCompountStatement(inits);
}

Ast *parseForStatement(Cctrl *cc) {
    Ast *volatile forinit, *volatile forcond, *volatile forstep;
    Ast *forbody;
    AoStr *for_begin, *for_end, *for_middle,
          *prev_begin, *prev_end;
    /* `for (i = 0; i < n; i++) {` or, as in Go, `for i = 0; i < n; i++ {`,
     * `for cond {` and `for {` */
    int parens = parseCondInParens(cc);
    Lexeme open = {0};
    if (parens) {
        cctrlTokenExpect(cc,'(');
        open = parseConsumedToken(cc);
    }
    int outer_for_parens = parse_for_parens;
    parse_for_parens = parens;

    prev_begin = cc->tmp_loop_begin;
    prev_end = cc->tmp_loop_end;

    for_begin = astMakeLabel();
    for_middle = astMakeLabel();
    for_end = astMakeLabel();

    cc->tmp_loop_begin = for_middle;
    cc->tmp_loop_end = for_end;

    cc->localenv = cctrlCreateAstMap(cc->localenv);
    ParseCondGuard guard;
    jmp_buf cond_recovery;
    if (parens) parseCondGuardInit(cc, &guard, &open, 1);
    else parseCondGuardInitBare(cc, &guard);
    if (setjmp(cond_recovery) != 0) {
        parseCondGuardRecover(cc, &guard);
        forinit = forcond = forstep = NULL;
    } else if (!parens && tokenPunctIs(cctrlTokenPeek(cc), '{')) {
        /* `for {`: for ever */
        forinit = forcond = forstep = NULL;
    } else if (!parens && parseForIsCondOnly(cc)) {
        /* `for cond {`: a while loop */
        parseCondGuardArm(cc, &guard, &cond_recovery);
        forinit = forstep = NULL;
        forcond = parseRequiredExpr(cc, "the `for` condition");
        parseCheckCondition(cc, forcond);
        parseExpectBodyAfterCond(cc, "for");
        parseCondGuardDone(cc, &guard);
    } else {
        parseCondGuardArm(cc, &guard, &cond_recovery);
        forinit = parseForLoopInitialiser(cc);
        //parseOptDeclOrStmt(cc);

        if (forinit && forinit->kind == AST_FOR) {
            /* A range loop, body and all */
            parseCondGuardDone(cc, &guard);
            forinit->for_begin = for_begin;
            forinit->for_middle = for_middle;
            forinit->for_end = for_end;
            cc->localenv = cc->localenv->parent;
            cc->tmp_loop_begin = prev_begin;
            cc->tmp_loop_end = prev_end;
            parse_for_parens = outer_for_parens;
            return forinit;
        }

        forcond = parseOptExpr(cc);
        parseCheckCondition(cc, forcond);
        if (tokenPunctIs(cctrlTokenPeek(cc), parens ? ')' : '{')) {
            forstep = NULL;
        } else {
            forstep = parseExpr(cc,16);
            /* `i++, j--`: several steps, in order */
            if (tokenPunctIs(cctrlTokenPeek(cc), ',')) {
                List *steps = listNew();
                listAppend(steps, forstep);
                while (tokenPunctIs(cctrlTokenPeek(cc), ',')) {
                    cctrlTokenGet(cc);
                    listAppend(steps, parseExpr(cc,16));
                }
                forstep = astCompountStatement(steps);
            }
        }
        if (parens) cctrlTokenExpect(cc,')');
        else parseExpectBodyAfterCond(cc, "for");
        parseCondGuardDone(cc, &guard);
    }
    parse_for_parens = outer_for_parens;

    Lexeme *peek = cctrlTokenPeek(cc);
    if (peek == NULL) {
        cctrlRaiseException(cc, "Unexpected end of input");
    }
    if (!parseValidPostControlFlowToken(peek)) {
        cctrlRaiseException(cc,"Unexpected %s `%s` while parsing for loop body", 
                lexemeTypeToString(peek->tk_type), lexemeAsWritten(peek));
    }
    ElideLoop *elide = parseElideLoopOpen(cc, forinit, forcond, forstep);
    forbody = parseBracedBody(cc, "for");
    parseElideLoopClose(cc, elide);
    /* Go back up */
    cc->localenv = cc->localenv->parent;
    cc->tmp_loop_begin = prev_begin;
    cc->tmp_loop_end = prev_end;
    return astFor(forinit,forcond,forstep,forbody,for_begin,for_middle,for_end);
}

Ast *parseWhileStatement(Cctrl *cc) {
    Ast *volatile whilecond;
    Ast *whilebody;
    AoStr *while_begin, *while_end,
          *prev_begin, *prev_end;
    int parens = parseCondInParens(cc);
    Lexeme open = {0};
    if (parens) {
        cctrlTokenExpect(cc,'(');
        open = parseConsumedToken(cc);
    }

    prev_begin = cc->tmp_loop_begin;
    prev_end = cc->tmp_loop_end;

    while_begin = astMakeLabel();
    while_end = astMakeLabel();
    cc->tmp_loop_begin = while_begin;
    cc->tmp_loop_end = while_end;

    cc->localenv = cctrlCreateAstMap(cc->localenv);
    ParseCondGuard guard;
    jmp_buf cond_recovery;
    if (parens) parseCondGuardInit(cc, &guard, &open, 0);
    else parseCondGuardInitBare(cc, &guard);
    if (setjmp(cond_recovery) != 0) {
        parseCondGuardRecover(cc, &guard);
        whilecond = astI64Type(0);
    } else {
        parseCondGuardArm(cc, &guard, &cond_recovery);
        whilecond = parens
            ? parseRequiredExpr(cc, "the `while` condition")
            : parseBareCond(cc, "the `while` condition");
        parseCheckCondition(cc, whilecond);
        if (parens) cctrlTokenExpect(cc,')');
        else parseExpectBodyAfterCond(cc, "while");
        parseCondGuardDone(cc, &guard);
    }

    Lexeme *peek = cctrlTokenPeek(cc);
    if (peek == NULL) {
        cctrlRaiseException(cc, "Unexpected end of input");
    }
    if (!parseValidPostControlFlowToken(peek)) {
        cctrlRaiseException(cc,"Unexpected %s `%s` while parsing while loop body", 
                lexemeTypeToString(peek->tk_type), lexemeAsWritten(peek));
    }

    whilebody = parseBracedBody(cc, "while");
    cc->localenv = cc->localenv->parent;
    cc->tmp_loop_begin = prev_begin;
    cc->tmp_loop_end = prev_end;
    return astWhile(whilecond,whilebody,while_begin, while_end);
}

Ast *parseDoWhileStatement(Cctrl *cc) {
    Ast *volatile whilecond;
    Ast *whilebody;
    Lexeme *tok;
    AoStr *while_begin, *while_end,
          *prev_begin, *prev_end;
    

    prev_begin = cc->tmp_loop_begin;
    prev_end = cc->tmp_loop_end;

    while_begin = astMakeLabel();
    while_end = astMakeLabel();
    cc->tmp_loop_begin = while_begin;
    cc->tmp_loop_end = while_end;

    cc->localenv = cctrlCreateAstMap(cc->localenv);

    Lexeme *peek = cctrlTokenPeek(cc);
    if (peek == NULL) {
        cctrlRaiseException(cc, "Unexpected end of input");
    }
    if (!parseValidPostControlFlowToken(peek)) {
        cctrlRewindUntilStrMatch(cc,peek->start,peek->len,NULL);
        cctrlRaiseException(cc,"Unexpected %s `%s` while parsing do while loop body", 
                lexemeTypeToString(peek->tk_type), lexemeAsWritten(peek));
    }


    whilebody = parseBracedBody(cc, "do");
    
    tok = cctrlTokenGetRequired(cc);

    if (tok->tk_type != TK_KEYWORD || tok->i64 != KW_WHILE) {
        cctrlTokenRewind(cc);
        cctrlRaiseException(cc,"expected `while` after do block, got `%s`",
                lexemeAsWritten(tok));
    }

    int parens = parseCondInParens(cc);
    Lexeme open = {0};
    if (parens) {
        cctrlTokenExpect(cc, '(');
        open = parseConsumedToken(cc);
    }
    ParseCondGuard guard;
    jmp_buf cond_recovery;
    parseCondGuardInit(cc, &guard, parens ? &open : NULL, 0);
    if (setjmp(cond_recovery) != 0) {
        parseCondGuardRecover(cc, &guard);
        whilecond = astI64Type(0);
    } else {
        parseCondGuardArm(cc, &guard, &cond_recovery);
        whilecond = parseRequiredExpr(cc, "the `while` condition");
        parseCheckCondition(cc, whilecond);
        if (parens) cctrlTokenExpect(cc,')');
        parseCondGuardDone(cc, &guard);
    }
    cctrlTokenExpect(cc,';');
    cc->localenv = cc->localenv->parent;
    cc->tmp_loop_begin = prev_begin;
    cc->tmp_loop_end = prev_end;
    return astDoWhile(whilecond,whilebody,while_begin, while_end);
}

Ast *parseBreakStatement(Cctrl *cc) {
    if (cc->tmp_loop_end == NULL && cc->defer_depth) {
        cctrlTokenRewind(cc);
        cctrlRaiseException(cc,"`break` can't leave a deferred statement");
    }
    if (cc->tmp_loop_end == NULL) {
        cctrlRaiseException(cc,"Floating break, not inside a breakable statement");
    }
    Ast *ast = astBreak(cc->tmp_loop_end);
    cctrlTokenExpect(cc,';');
    return ast;
}

Ast *parseContinueStatement(Cctrl *cc) {
    if (cc->tmp_loop_begin == NULL && cc->defer_depth) {
        cctrlTokenRewind(cc);
        cctrlRaiseException(cc,"`continue` can't leave a deferred statement");
    }
    if (cc->tmp_loop_begin == NULL) {
        cctrlRaiseException(cc,"Floating continue, not inside a loop");
    }
    Ast *ast = astContinue(cc->tmp_loop_begin);
    cctrlTokenExpect(cc,';');
    return ast;
}

/* A struct or union by value (String and slices included), not an
 * intrinsic class */
static int parseIsStructType(AstType *t) {
    return t && (t->kind == AST_TYPE_CLASS || t->kind == AST_TYPE_UNION) &&
           !t->is_intrinsic;
}

/* Two struct types that are the same one */
static int parseSameStructType(AstType *a, AstType *b) {
    if (a == b) return 1;
    if (!parseIsStructType(a) || !parseIsStructType(b) || a->kind != b->kind) return 0;
    if (a->fields && a->fields == b->fields) return 1;
    return a->clsname && b->clsname && aoStrCmp(a->clsname, b->clsname);
}

Ast *parseReturnStatement(Cctrl *cc) {
    /* The `return`, just consumed, and the `;`, for the warning below */
    cctrlTokenRewind(cc);
    Lexeme ret_tok = *cctrlTokenGet(cc);
    /* Top-level code (`if (x) return 0;` outside a function) has no
     * function to return from: tmp_fname and tmp_rettype are NULL. */
    if (!cc->tmp_fname || !cc->tmp_rettype) {
        cctrlRaiseExceptionAt(cc,ret_tok.line,ret_tok.col,ret_tok.len,
                "`return` outside a function");
        cctrlTerminate(cc);
    }
    if (cc->defer_depth) {
        cctrlRaiseExceptionAt(cc,ret_tok.line,ret_tok.col,ret_tok.len,
                "`return` can't leave a deferred statement");
    }
    Ast *retval = parseExpr(cc,16);
    AstType *check;
    if (retval && retval->kind == AST_LVAR && (retval->flags & AST_FLAG_AUTODEFER)) {
        cctrlRaiseExceptionAt(cc, ret_tok.line, ret_tok.col, ret_tok.len,
            "`%s` is declared `scoped`, so it is freed when this function returns: "
            "declare it without `scoped` to return it", retval->lname->data);
    }
    cctrlTokenExpect(cc,';');
    cctrlTokenRewind(cc);
    Lexeme semi_tok = *cctrlTokenGet(cc);
    /* A best attempt at trying to get the return type of a function */
    if (cc->tmp_rettype->kind == AST_TYPE_AUTO) {
        cc->tmp_rettype = parseReturnAuto(cc,retval);
    }

    Ast *maybe_fn = findFunctionDecl(cc,cc->tmp_fname->data,cc->tmp_fname->len);

    if (retval) retval = parseSliceFrom(cc, cc->tmp_rettype, retval);
    if (retval) parseResolveOverloadRef(cc, cc->tmp_rettype, retval);
    if (retval) check = retval->type;
    else        check = ast_void_type;

    if (check->kind == AST_TYPE_VOID && maybe_fn->type->rettype->kind == AST_TYPE_VOID) {
        if (maybe_fn->flags & AST_FLAG_INLINE) {
            /* `return Inc();`: keep the call, there is no value to set */
            return retval ? retval : astDecl(maybe_fn->inline_ret,NULL);
        }
        return astReturn(retval,cc->tmp_rettype);
    }

    if (retval && astFnPtrMismatch(cc->tmp_rettype, check)) {
        cctrlRaiseExceptionAt(cc,ret_tok.line,ret_tok.col,ret_tok.len,
                "cannot convert %s to %s%s",
                astTypeToString(check), astTypeToString(cc->tmp_rettype),
                parseFnConvertHint(cc->tmp_rettype, check));
    }
    AstType *ok = astTypeCheck(cc->tmp_rettype,retval,'\0');
    if (!ok && astIsEnumType(cc->tmp_rettype)) {
        cctrlRaiseExceptionAt(cc,ret_tok.line,ret_tok.col,ret_tok.len,
                "Cannot convert `%s` to enum `%s` without a cast",
                astTypeToString(check), cc->tmp_rettype->clsname->data);
    } else if (retval && (parseIsStructType(check) || parseIsStructType(cc->tmp_rettype)) &&
               (!ok || !parseSameStructType(check, cc->tmp_rettype))) {
        /* a struct (a String, a slice) for something else, or the other way
         * round: there is nothing to convert, the value would be garbage
         * (a call or an assignment says the same) */
        cctrlRaiseExceptionAt(cc,ret_tok.line,ret_tok.col,ret_tok.len,
                "cannot return a %s from a function returning %s",
                astTypeToString(check), astTypeToString(cc->tmp_rettype));
    } else if (!ok) {
        Ast *func = mapGet(cc->global_env, cc->tmp_fname->data);
        typeCheckReturnTypeWarn(cc,func,check,retval,&ret_tok,&semi_tok);
    }
    if (maybe_fn->flags & AST_FLAG_INLINE) {
        return astDecl(maybe_fn->inline_ret,retval);
    }
    return astReturn(retval,cc->tmp_rettype);
}

/* A call to a runtime helper taking &var, for the lowerings below */
static Ast *parseCallWithAddrOf(char *fname, Ast *var) {
    Vec *args = astVecNew();
    vecPush(args, astUnaryOperator(astMakePointerType(var->type),
                                   AST_UN_OP_ADDR_OF, var));
    return astFunctionCall(ast_void_type, fname, strlen(fname), args);
}

/* `catch (Error e) <stmt>` after the `catch`: the handler becomes
 *     { Error e; KTM_ErrorTake(&e); defer KTM_ErrorDrop(&e); <stmt> }
 * so e owns the message until the handler is left, however it is left.
 * NULL (nothing consumed) when no `(Type name)` follows. */
static Ast *parseCatchBinding(Cctrl *cc) {
    Lexeme *open = cctrlTokenPeek(cc);
    if (!tokenPunctIs(open, '(')) return NULL;
    cctrlTokenGet(cc);
    Lexeme *tname = cctrlTokenPeek(cc);
    if (!tname || !cctrlGetKeyWord(cc, tname->start, tname->len)) {
        cctrlTokenRewind(cc);
        return NULL;
    }
    int tline = tname->line, tcol = tname->col, tlen = tname->len;
    AstType *type = parseDeclSpec(cc);
    if (!parseIsErrorType(type)) {
        cctrlRaiseExceptionAt(cc, tline, tcol, tlen,
            "`catch` can only bind an Error, as in `catch (Error e)`, got %s",
            astTypeToString(type));
    }
    Lexeme *name = cctrlTokenGetRequired(cc);
    if (name->tk_type != TK_IDENT) {
        cctrlRaiseException(cc, "Expected a name for the Error in `catch (Error <name>)`");
    }
    cctrlTokenExpect(cc, ')');

    Map *outer = cc->localenv;
    cc->localenv = cctrlCreateAstMap(cc->localenv);
    Ast *var = astLVar(type, name->start, name->len);
    var->line = name->line;
    var->col = name->col;
    mapAdd(cc->localenv, var->lname->data, var);
    if (cc->tmp_locals) {
        listAppend(cc->tmp_locals, var);
    }
    Ast *handler = parseStatement(cc);
    cc->localenv = outer;

    List *stmts = listNew();
    listAppend(stmts, astDecl(var, NULL));
    listAppend(stmts, parseCallWithAddrOf(astRuntimeSymbol("__ktmErrorTake"), var));
    listAppend(stmts, astDefer(parseCallWithAddrOf(astRuntimeSymbol("__ktmErrorDrop"), var)));
    if (handler) listAppend(stmts, handler);
    return astCompountStatement(stmts);
}

/* `try { ... } catch <stmt>` or `try { ... } catch (Error e) <stmt>`.
 * The handler is a single statement (often `{...}`). The binding form
 * gets the error (see parseCatchBinding); without one the code is in
 * `Fs->except_ch`, and the message, which nothing can read, is freed
 * when the handler starts. */
Ast *parseTryStatement(Cctrl *cc) {
    cctrlTokenExpect(cc,'{');
    Ast *try_body = parseCompoundStatement(cc);
    Lexeme *tok = cctrlTokenPeek(cc);
    if (!tok) {
        cctrlRaiseException(cc,
            "Expected `catch` after `try { ... }`, got end of input");
    }
    if (tok->tk_type != TK_KEYWORD || tok->i64 != KW_CATCH) {
        cctrlRaiseException(cc,
            "Expected `catch` after `try { ... }`, got `%s`",
            lexemeAsWritten(tok));
    }
    cctrlTokenGet(cc);  /* consume `catch` */
    Ast *catch_body = parseCatchBinding(cc);
    if (!catch_body) {
        catch_body = parseStatement(cc);
        List *stmts = listNew();
        char *forget = astRuntimeSymbol("__ktmErrorForget");
        listAppend(stmts, astFunctionCall(ast_void_type, forget,
                                          strlen(forget), astVecNew()));
        if (catch_body) listAppend(stmts, catch_body);
        catch_body = astCompountStatement(stmts);
    }
    return astTry(try_body, catch_body);
}

/* The file a throw is in, as a string literal: the file of the token
 * just read (a package's function is parsed from its saved tokens while
 * the lexer is in the importing file), as a crash report shows it. */
Ast *parseThrowFile(Cctrl *cc) {
    const char *path = "";
    LexFile *f = cc->tok_file;
    if (!f && cc->lexer_) f = cc->lexer_->cur_file;
    if (f && f->filename) {
        path = asmLinesPath(cc, f->filename->data);
    }
    AoStr *esc = aoStrNew();
    s64 real_len = 1;
    for (const char *p = path; *p; ++p, ++real_len) {
        if (*p == '\\' || *p == '"') aoStrPutChar(esc, '\\');
        aoStrPutChar(esc, *p);
    }
    return cctrlGetOrSetString(cc, esc->data, esc->len, real_len);
}

/* `throw(code);`, `throw(code, fmt, ...);` or `throw(e);` with an Error
 * (see parseThrowCall). The code is usually an enum member (or, from
 * before Error, a character constant like 'BlkDev'); the runtime sets the
 * thread's Error, file and line included, and longjmps to the nearest
 * enclosing catch. */
Ast *parseThrowStatement(Cctrl *cc) {
    int line = cc->lineno;   /* of the `throw` just consumed */
    cctrlTokenExpect(cc,'(');
    if (tokenPunctIs(cctrlTokenPeek(cc), ')')) {
        /* `throw();`: reported as a missing expression */
        parseRequiredExpr(cc, "the value to `throw`");
    }
    /* the old `throw('X')`: a character as the code */
    Lexeme *code_tok = cctrlTokenPeek(cc);
    if (code_tok && code_tok->tk_type == TK_CHAR_CONST) {
        cctrlErrorAt(cc, code_tok->line, code_tok->col, code_tok->len + 2,
            "a character as the code of `throw`: use an enum member or a number");
    }
    Ast *file = parseThrowFile(cc);
    Ast *code = NULL;
    Ast *call = parseThrowCall(cc, file, line, &code);
    cctrlTokenExpect(cc,';');
    Ast *ast = astThrow(code);
    ast->throw_call = call;
    ast->throw_file = file;
    ast->line = line;
    return ast;
}

void parseRaiseCaseException(Cctrl *cc, Ast *case_expr) {
    cctrlRewindUntilStrMatch(cc,str_lit("case"),NULL);
    char *exp = astLValueToString(case_expr,0);
    char *type = astTypeToString(case_expr->type);
    char *suggestion = mprintf("Invalid use of type %s", type);
    cctrlRaiseExceptionFromTo(cc, suggestion, 'c', ':', "`case` must be followed by an integer constant got - %s", exp);
}

/* Does `peek` end a case's arm: the next `case`, `default` or the
 * switch's `}`? */
static int parseArmEnds(Lexeme *peek) {
    return !peek || tokenPunctIs(peek, '}') ||
           (peek->tk_type == TK_KEYWORD &&
            (peek->i64 == KW_CASE || peek->i64 == KW_DEFAULT));
}

static Ast *parseArmBlock(Cctrl *cc);

/* The statements of a `case` or `default` (Y8): every one up to the next
 * label. The arm leaves the switch at its end, unless its last statement
 * is `fallthrough;`, which runs on into the next arm. An empty arm
 * (`case 1: case 2: ...`) shares the next one's statements. */
static void parseCaseArm(Cctrl *cc, Ast *arm) {
    List *stmts = arm->case_asts;
    int ft = 0;
    s64 ft_line = 0, ft_col = 0;
    if (!parseArmEnds(cctrlTokenPeek(cc))) {
        /* a block of its own (F33): declarations, `defer` and a scope,
         * and the block's checks place a `fallthrough` */
        cc->fallthrough_pending = 0;
        Ast *block = parseArmBlock(cc);
        if (cc->fallthrough_pending) {
            ft = 1;
            ft_line = cc->fallthrough_line;
            ft_col = cc->fallthrough_col;
        }
        if (!listEmpty(block->stms)) listAppend(stmts, block);
    }
    arm->case_fallthrough = ft;
    arm->case_ft_line = ft_line;
    arm->case_ft_col = ft_col;
    if (!ft && !listEmpty(stmts)) {
        listAppend(stmts, astBreak(cc->tmp_loop_end));
    }
    /* the switch reports a `fallthrough` with no next case */
    cc->fallthrough_pending = ft;
    cc->fallthrough_line = ft_line;
    cc->fallthrough_col = ft_col;
}

/* The index of case string `str` in the switch's strings, added when it
 * is new; a string already there is an error (case-sensitive, by bytes) */
static int parseCaseStringIndex(Cctrl *cc, Ast *str, Lexeme *case_tok) {
    Vec *strings = cc->tmp_case_strings;
    char *bytes = malloc(str->sval->len + 1);
    char *other = NULL;
    astStringDecode(str, bytes);
    int n = (int)str->real_len - 1;
    for (u64 i = 0; i < strings->size; ++i) {
        Ast *cur = vecGet(Ast *, strings, i);
        other = realloc(other, cur->sval->len + 1);
        astStringDecode(cur, other);
        int m = (int)cur->real_len - 1;
        if (m != n || memcmp(bytes, other, n)) continue;
        char *msg = mprintf("duplicate case value \"%s\"", str->sval->data);
        AoStr *bold = aoStrNew();
        aoStrCatColoured(bold, ESC_BOLD, msg);
        AoStr *buf = cctrlCreateErrorLineAt(cc, case_tok->line, case_tok->col,
                                            case_tok->len, bold->data,
                                            CCTRL_ERROR, NULL);
        aoStrRelease(bold);
        CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, NULL);
        d->line = d->end_line = case_tok->line;
        d->col = case_tok->col;
        d->end_col = case_tok->col + case_tok->len;
        cctrlDiagPush(cc, d);
        break;
    }
    free(bytes);
    free(other);
    vecPush(strings, str);
    return (int)strings->size - 1;
}

Ast *parseCaseLabel(Cctrl *cc, Lexeme *tok) {
    if (cc->tmp_case_list == NULL) {
        cctrlRaiseException(cc,"unexpected 'case' found");
    }
    cc->fallthrough_pending = 0;
    Lexeme *case_tok = tok;
    Ast *case_, *prev, *case_expr = NULL, *value_expr = NULL;
    Lexeme *peek;
    AoStr *label;
    int begining,end;
    int ok = 1;

    peek = cctrlTokenPeek(cc);
    if (cc->tmp_case_strings) {
        /* a switch on strings: the case's number is its string's index */
        case_expr = parseRequiredExpr(cc, "a `case` string");
        value_expr = case_expr;
        if (case_expr->kind != AST_STRING) {
            cctrlRewindUntilStrMatch(cc,str_lit("case"),NULL);
            cctrlRaiseExceptionFromTo(cc, NULL, 'c', ':',
                    "a switch on a u8[] or String takes a string literal after `case`, got - %s",
                    astLValueToString(case_expr,0));
        }
        if (tokenPunctIs(cctrlTokenPeek(cc),TK_ELLIPSIS)) {
            cctrlRaiseException(cc,"a `case` range can't be used in a switch on strings");
        }
        cctrlTokenExpect(cc,':');
        begining = parseCaseStringIndex(cc, case_expr, case_tok);
        label = astMakeLabel();
        List *stmts = listNew();
        case_ = astCase(label,begining,begining,stmts);
        vecPush(cc->tmp_case_list,case_);
        parseCaseArm(cc, case_);
        return case_;
    }
    if (tokenPunctIs(peek,':')) {
        if (vecEmpty(cc->tmp_case_list)) {
            begining = 0;
        } else {
            prev = cc->tmp_case_list->entries[cc->tmp_case_list->size - 1];
            begining = prev->case_end+1;
        }
        label = astMakeLabel();
    } else {
        /* A bare `case:` (above) is the previous value plus one */
        case_expr = parseRequiredExpr(cc, "a `case` value or `:`");
        value_expr = case_expr;
        begining = evalIntConstExprOrErr(case_expr, &ok);
        if (!ok) {
            parseRaiseCaseException(cc,case_expr);
        } else {
            label = astMakeLabel();
        }
    }

    tok = cctrlTokenPeek(cc);

    if (tokenPunctIs(tok,TK_ELLIPSIS)) {
        cctrlTokenGet(cc);
        case_expr = parseRequiredExpr(cc, "the end of the `case` range");
        ok = 1;
        end = evalIntConstExprOrErr(case_expr, &ok);
        cctrlTokenExpect(cc,':');
        if (begining > end) {
            cctrlRewindUntilStrMatch(cc,str_lit("case"),NULL);
            char *suggestion = mprintf("Swap the conditions around `case %d ... %d: `",end,begining);
            cctrlRaiseExceptionFromTo(cc, suggestion, 'c', ':', " Condition is in the wrong order '%d' must be lower than '%d'",
                    begining, end);
        }
    } else {
        cctrlTokenExpect(cc,':');
        end = begining;
    }

    List *stmts = listNew();
    peek = cctrlTokenPeek(cc);

    case_ = astCase(label,begining,end,stmts);
    assertUniqueSwitchCaseLabels(cc,cc->tmp_case_list,case_,value_expr,case_tok);

    vecPush(cc->tmp_case_list,case_);
    parseCaseArm(cc, case_);
    return case_;
}

Ast *parseDefaultStatement(Cctrl *cc) {
    cctrlTokenExpect(cc,':');
    cc->fallthrough_pending = 0;
    if (cc->tmp_default_case) {
        cctrlRaiseException(cc,"Duplicate default case");
    }
    List *stmts = listNew();
    AoStr *default_label = astMakeLabel();
    /* set here so this is non-null for the next call to parseStatement */
    cc->tmp_default_case = astDefault(default_label,stmts);
    cc->tmp_default_at = (int)cc->tmp_case_list->size;
    parseCaseArm(cc, cc->tmp_default_case);
    return cc->tmp_default_case;
}

/* A switch on strings (L7): its value is a u8[] (bytes) or a String */
static int parseIsStringSwitch(AstType *t) {
    if (astTypeIsString(t)) return 1;
    return astTypeIsSlice(t) && t->elem && t->elem->size == 1 &&
           (t->elem->kind == AST_TYPE_CHAR || t->elem->kind == AST_TYPE_INT);
}

/* The case number a switch on strings switches on: the index in
 * `strings` of the case equal to `value` (same length, then the same
 * bytes, case-sensitive), or -1. `value` is read once, into a hidden
 * u8[], by the first length compare. */
static Ast *parseStringSwitchIndex(Cctrl *cc, Ast *value, Vec *strings) {
    AstType *slice_t = astTypeIsString(value->type) ? astMakeSliceType(ast_u8_type)
                                                     : value->type;
    value = parseSliceFrom(cc, slice_t, value);
    Ast *var = parseHiddenSliceVar(cc, slice_t);
    AstType *len_t = mapGetLen(slice_t->fields, str_lit("len"));
    AstType *ptr_t = mapGetLen(slice_t->fields, str_lit("ptr"));
    AstType *elem_t = slice_t->elem;
    int is_err = 0;
    Ast *assign = astBinaryOp(AST_BIN_OP_ASSIGN, var, value, &is_err);
    Ast *read = astClassRef(len_t, assign, "len");

    /* built from the last case back: c0 ? 0 : (c1 ? 1 : ... -1) */
    Ast *index = astI64Type(-1);
    char *bytes = NULL;
    for (s64 i = (s64)strings->size - 1; i >= -1; --i) {
        if (i < 0) {
            /* no case at all: still read the value once */
            if (strings->size == 0) {
                Ast *never = astBinaryOp(AST_BIN_OP_LT, read, astI64Type(0), &is_err);
                index = astTernary(ast_int_type, never, astI64Type(-1), index);
            }
            break;
        }
        Ast *str = vecGet(Ast *, strings, i);
        bytes = realloc(bytes, str->sval->len + 1);
        /* its bytes; an empty literal is kept as "\\0" */
        astStringDecode(str, bytes);
        int n = (int)str->real_len - 1;
        Ast *len = i == 0 ? read : astClassRef(len_t, var, "len");
        Ast *test = astBinaryOp(AST_BIN_OP_EQ, len, astI64Type(n), &is_err);
        for (int b = 0; b < n; ++b) {
            Ast *ptr = astClassRef(ptr_t, var, "ptr");
            Ast *at = astBinaryOp(AST_BIN_OP_ADD, ptr, astI64Type(b), &is_err);
            Ast *byte = astUnaryOperator(elem_t, AST_UN_OP_DEREF, at);
            s64 want = elem_t->issigned ? (s64)(s8)bytes[b] : (s64)(u8)bytes[b];
            Ast *same = astBinaryOp(AST_BIN_OP_EQ, byte, astI64Type(want), &is_err);
            test = astBinaryOp(AST_BIN_OP_LOG_AND, test, same, &is_err);
        }
        index = astTernary(ast_int_type, test, astI64Type(i), index);
    }
    free(bytes);
    return index;
}

Ast *parseSwitchStatement(Cctrl *cc) {
    Ast *volatile cond;
    Ast *tmp, *original_default_label;
    Lexeme *peek;
    Vec *original_cases;
    AoStr *end_label,*tmp_name,*original_break;
    volatile int switch_bounds_checked = 1;
    volatile char terminating_char = ')';
    volatile int is_strings = 0;

    peek = cctrlTokenPeek(cc);
    if (!peek) {
        cctrlRaiseException(cc, "Unexpected end of input after `switch`");
    }

    /* `switch (x) {`, `switch x {` (as `if x {`) or the unchecked
     * `switch [x] {` */
    int parens = tokenPunctIs(peek,'[') || parseCondInParens(cc);
    Lexeme open = {0};
    if (parens) {
        /* Walk past and set the expected terminting character */
        if (peek->i64 == '[') {
            switch_bounds_checked = 0;
            terminating_char = ']';
        }
        cctrlTokenGet(cc);
        open = parseConsumedToken(cc);
    }
    ParseCondGuard guard;
    jmp_buf cond_recovery;
    if (parens) parseCondGuardInit(cc, &guard, &open, 0);
    else parseCondGuardInitBare(cc, &guard);
    if (setjmp(cond_recovery) != 0) {
        parseCondGuardRecover(cc, &guard);
        cond = astI64Type(0);
    } else {
        parseCondGuardArm(cc, &guard, &cond_recovery);
        cond = parens ? parseRequiredExpr(cc, "the `switch` value")
                      : parseBareCond(cc, "the `switch` value");
        if (parseIsStringSwitch(cond->type)) {
            is_strings = 1;
        } else if (!astIsIntOrIntrinsic(cond->type)) {
            cctrlRaiseException(cc,"Switch can only have an integer, a u8[] or a String value");
        }
        if (parens) cctrlTokenExpect(cc,terminating_char);
        else parseExpectBodyAfterCond(cc, "switch");
        parseCondGuardDone(cc, &guard);
    }

    original_break = cc->tmp_loop_end;
    original_default_label = cc->tmp_default_case;
    original_cases = cc->tmp_case_list;
    int original_default_at = cc->tmp_default_at;
    Vec *original_strings = cc->tmp_case_strings;

    cc->tmp_case_list = astVecNew();
    cc->tmp_default_case = NULL;
    cc->tmp_case_strings = is_strings ? astVecNew() : NULL;

    end_label = astMakeLabel();

    /* this is the current label */
    cc->tmp_loop_end = end_label;

    tmp_name = astMakeTmpName();

    tmp = astLVar(is_strings ? ast_int_type : cond->type, tmp_name->data, tmp_name->len);
    listAppend(cc->tmp_locals,tmp);

    int outer_fallthrough = cc->fallthrough_pending;
    s64 outer_ft_line = cc->fallthrough_line, outer_ft_col = cc->fallthrough_col;
    cc->fallthrough_pending = 0;
    parseStatement(cc);
    aoStrRelease(tmp_name);
    int last_fallthrough = cc->fallthrough_pending;
    s64 last_ft_line = cc->fallthrough_line, last_ft_col = cc->fallthrough_col;
    cc->fallthrough_pending = outer_fallthrough;
    cc->fallthrough_line = outer_ft_line;
    cc->fallthrough_col = outer_ft_col;

    if (is_strings) {
        cond = parseStringSwitchIndex(cc, cond, cc->tmp_case_strings);
        /* the index is -1 when no case matches: never a `switch [...]`
         * jump without its bounds check */
        switch_bounds_checked = 1;
    }
    cc->tmp_case_strings = original_strings;

    Ast *switch_ast = astSwitch(
        cond,
        cc->tmp_case_list,
        cc->tmp_default_case,
        end_label,
        switch_bounds_checked
    );

    Ast *dflt = cc->tmp_default_case;
    Vec *cases = cc->tmp_case_list;
    int default_at = cc->tmp_default_at;
    cc->tmp_loop_end = original_break;
    cc->tmp_case_list = original_cases;
    cc->tmp_default_case = original_default_label;
    cc->tmp_default_at = original_default_at;
    if (last_fallthrough) {
        cctrlRaiseExceptionAt(cc, last_ft_line, last_ft_col, 11,
                "`fallthrough` in the last case of a switch: there is no next case");
    }
    /* `default` runs after every case (its code is placed last), so it
     * can't be fallen into or out of from the middle of the cases */
    if (dflt && default_at < (int)cases->size) {
        Ast *before = default_at > 0 ? vecGet(Ast *, cases, default_at - 1) : NULL;
        Ast *ft = dflt->case_fallthrough ? dflt
                : before && before->case_fallthrough ? before : NULL;
        if (ft) {
            cctrlRaiseExceptionAt(cc, ft->case_ft_line, ft->case_ft_col, 11,
                "`fallthrough` into or out of a `default` that isn't the "
                "switch's last label: put `default` last");
        }
    }
    return switch_ast;
}

/* `fallthrough`, a keyword the lexer gives as an identifier (no
 * variable can have its name, see parseCheckNotReserved) */
static int parseIsFallthrough(Cctrl *cc, Lexeme *tok, Lexeme *peek) {
    (void)cc;
    (void)peek;
    return tok->tk_type == TK_IDENT && tok->len == 11 &&
           memcmp(tok->start, "fallthrough", 11) == 0;
}

/* A case of a switch says it goes on into the next one (Y8: otherwise it
 * leaves the switch); only inside a switch, as the last statement of a
 * case (parseCaseArm) and not of its last one (parseSwitchStatement). */
static Ast *parseFallthrough(Cctrl *cc, Lexeme *tok) {
    s64 line = tok->line, col = tok->col;
    cctrlTokenExpect(cc, ';');
    if (cc->tmp_case_list == NULL) {
        cctrlRaiseExceptionAt(cc, line, col, 11,
                "`fallthrough` can only be used in a case of a switch");
    }
    cc->fallthrough_pending = 1;
    cc->fallthrough_line = line;
    cc->fallthrough_col = col;
    return NULL;
}

/* Concatinate the label of the goto with the name of the function 
 * currently being parsed to be able to have uniqe goto labels  */
AoStr *createFunctionLevelGotoLabel(Cctrl *cc, Lexeme *tok) {
    AoStr *label = aoStrNew();
    aoStrCatFmt(label,".%S_%.*s",cc->tmp_fname,tok->len,tok->start);
    return label;
}

Ast *parseStatement(Cctrl *cc) {
    Lexeme *tok, *peek;
    AoStr *label;
    Ast *ret, *ast;
    Map *env;
    tok = cctrlTokenGet(cc);

    if (tok == NULL) {
        cctrlRaiseException(cc, "Unexpected end of input, expected a "
                "statement");
    }
    if (tokenPunctIs(tok, '@')) {
        cctrlRaiseExceptionAt(cc, tok->line, tok->col, 1,
                "attributes go on a struct or on a struct's field, not inside a function");
    }
    if (tok->tk_type == TK_KEYWORD) {
        switch (tok->i64) {
            case KW_IF:       return parseIfStatement(cc);
            case KW_FOR:      return parseForStatement(cc);
            case KW_WHILE:    return parseWhileStatement(cc);
            case KW_DO:       return parseDoWhileStatement(cc);
            case KW_RETURN:   return parseReturnStatement(cc);
            case KW_TRY:      return parseTryStatement(cc);
            case KW_THROW:    return parseThrowStatement(cc);
            case KW_DEFER:
                cctrlTokenRewind(cc);
                cctrlRaiseException(cc,
                    "`defer` must be a statement of a { } block");
            case KW_THREADLOCAL:
                cctrlTokenRewind(cc);
                cctrlRaiseException(cc,
                    "`threadlocal` only goes on a global variable, not a local");
            case KW_SWITCH:   return parseSwitchStatement(cc);
            case KW_CASE:     return parseCaseLabel(cc,tok);
            case KW_DEFAULT:  return parseDefaultStatement(cc);
            case KW_BREAK: {
                /* at the `break`, not the token after it */
                s64 line = tok->line, col = tok->col;
                Ast *brk = parseBreakStatement(cc);
                brk->line = line;
                brk->col = col;
                return brk;
            }
            case KW_CONTINUE: return parseContinueStatement(cc);
            case KW_ASM: {
                /* Inline `asm { ... }` block as a statement:
                 * functions can splice raw asm directly into the body
                 * (no `Name::` wrapper inside). prsAsm's `parse_one`
                 * mode is the one-block-no-label path we need. */
                Lexeme *p = cctrlTokenPeek(cc);
                if (!tokenPunctIs(p,'{')) {
                    cctrlRaiseException(cc,
                        "Expected `{` after `asm`, got `%.*s`",
                        p ? p->len : 0, p ? p->start : "");
                }
                Ast *asm_block = prsAsm(cc, 1);
                listAppend(cc->asm_blocks, asm_block);
                return asm_block;
            }
            case KW_STATIC: {
                env = cc->localenv;
                cc->localenv = NULL;

                AstType *type = parseFullType(cc);
                tok = cctrlTokenGetRequired(cc);

                if (tok->tk_type != TK_IDENT) {
                    cctrlTokenRewind(cc);
                    cctrlRaiseException(cc,"Expected variable name following type declaration '%s' - '%s' <var_name>",
                            astTypeToString(type), astTypeToString(type));
                }
                type = parseArrayDimensions(cc,type);

                Ast *gvar_ast = astGVar(type,tok->start,tok->len,1);


                if (type->kind == AST_TYPE_ARRAY) {
                    ast = parseVariableInitialiser(cc,gvar_ast,
                            PUNCT_TERM_SEMI|PUNCT_TERM_COMMA);

                    cc->localenv = env;
                    mapAdd(env,gvar_ast->gname->data,gvar_ast);
                    listAppend(cc->ast_list,ast);
                    return ast;
                }

                peek = cctrlTokenPeek(cc);
                if (tokenPunctIs(peek,'=')) {
                    ast = parseVariableInitialiser(cc,gvar_ast,
                            PUNCT_TERM_SEMI|PUNCT_TERM_COMMA);

                    if (type->kind == AST_TYPE_AUTO) {
                        gvar_ast->type = ast->declinit->type;
                        if (ast->declinit->kind == AST_STRING) {
                            /* @Leak: we've just lost the original string array
                             * that was parsed... or have we? Possibly not as it
                             * would exist on the declinit->type and we do not 
                             * change it */
                            gvar_ast->type = astMakePointerType(ast_u8_type);
                            gvar_ast->type->len = ast->declinit->type->len;
                        }
                    }

                    if (ast->declinit->kind == AST_ASM_FUNCALL ||
                        parseIsFunctionCall(ast->declinit))
                    {
                        cctrlRaiseException(cc,"'%s %s' must be a compile time "
                                "constant",
                                astTypeToColorString(gvar_ast->type),
                                astLValueToString(ast,0));
                    }
                } else {
                    cctrlTokenExpect(cc,';');
                    ast = astDecl(gvar_ast,NULL);
                }
                cc->localenv = env;
                mapAdd(env,gvar_ast->gname->data,gvar_ast);
                listAppend(cc->ast_list,ast);
                return ast;
            }

            case KW_GOTO: {
                int goto_line = tok->line, goto_col = tok->col;
                if (cc->defer_depth) {
                    cctrlRaiseExceptionAt(cc, goto_line, goto_col, tok->len,
                        "`goto` can't leave a deferred statement");
                }
                tok = cctrlTokenGet(cc);
                if (tok == NULL) {
                    cctrlRaiseException(cc,
                            "Unexpected end of input after `goto`");
                }
                label = createFunctionLevelGotoLabel(cc,tok);
                ret = astGoto(label);
                ret->line = goto_line;
                ret->col = goto_col;
                cctrlTokenExpect(cc,';');
                return ret;
            }

            default: {
                cctrlTokenRewind(cc);
                cctrlRaiseException(cc,"Keyword '%.*s' cannot be used in this context",
                        tok->len,tok->start);
            }
        }
    }

    if (tok->tk_type == TK_CHAR_CONST) {
        /* the old print of a character constant */
        if (!(cc->flags & CCTRL_REPL)) {
            cctrlErrorAt(cc, tok->line, tok->col, tok->len + 2,
                "a character printed as a statement: write `printf(\"%%c\", ...)`");
        }
        return parseFloatingCharConst(cc,tok);
    }

    if (tok->tk_type == TK_STR) {
        cctrlTokenRewind(cc);
        /* HACK in ktm printf */
        return parsePrintStatement(cc);
    }

    /* Hacked in goto label ;) */
    peek = cctrlTokenPeek(cc);
    if (parseIsFallthrough(cc, tok, peek)) {
        return parseFallthrough(cc, tok);
    }
    if (tok->tk_type == TK_IDENT && tokenPunctIs(peek,':')) {
        if (cc->defer_depth) {
            cctrlRaiseExceptionAt(cc, tok->line, tok->col, tok->len,
                "a label can't be inside a deferred statement");
        }
        label = createFunctionLevelGotoLabel(cc,tok);
        ret = astLabel(label);
        parseElideNoteLabel(cc);
        /* consume ':' */
        cctrlTokenExpect(cc,':');
        return ret;
    }

    if (tokenPunctIs(tok,'{')) {
        return parseCompoundStatement(cc);
    }

    if (tok->tk_type == TK_I64) {
        cctrlRaiseException(cc,"Floating integer constant '%ld' cannot be used in this context", tok->i64);
    } else if (tok->tk_type == TK_F64) {
        cctrlRaiseException(cc,"Floating integer constant '%f' cannot be used in this context", tok->f64);
    } else if (tok->tk_type == TK_PUNCT && (tok->i64 == ',')) {
        lexemePrint(tok);
        cctrlTokenRewind(cc);
        cctrlRaiseException(cc, "Floating '%c' cannot be used in this context", tok->i64);
    }

    cctrlTokenRewind(cc);
    ast = parseExpr(cc,16);
    if (!ast && tokenPunctIs(tok,'(')) {
        /* `();`: empty brackets are no expression (a lone `;` is the
         * empty statement) */
        cctrlRaiseException(cc, "Expected an expression between `(` and `)`");
    }
    if (ast && ast->kind == AST_CLASS_REF) {
        /* `self.port;`: read and dropped, most likely a slip */
        /* written as ktm does: `self.port`, not `self->port` */
        AoStr *shown = aoStrNew();
        for (char *c = astLValueToString(ast, 0); *c; ++c) {
            if (c[0] == '-' && c[1] == '>') { aoStrPutChar(shown, '.'); c++; }
            else aoStrPutChar(shown, *c);
        }
        cctrlWarningAt(cc, tok->line, tok->col, tok->len,
                       "`%s` on its own does nothing", shown->data);
    }
    tok = cctrlTokenGet(cc);
    assertTokenIsTerminator(cc,tok,PUNCT_TERM_SEMI|PUNCT_TERM_COMMA);
    return ast;
}

/* Is `tok` (a type/class/enum name, not yet consumed) followed by `.`?
 * A type name is never followed by `.` in real code; `Name.member` is an
 * enum member reference, not the start of a declaration (`Name var`).
 * Gets and rewinds rather than peeking further ahead, which the token
 * ring buffer does not support reliably beyond one token. */
static int parseTypeNameFollowedByDot(Cctrl *cc) {
    Lexeme *t1 = cctrlTokenGet(cc);
    Lexeme *t2 = cctrlTokenGet(cc);
    int dot = tokenPunctIs(t2, '.');
    cctrlTokenRewind(cc);
    cctrlTokenRewind(cc);
    (void)t1;
    return dot;
}

/* `defer <stmt>`: <stmt> (one statement or a block) runs whenever the
 * enclosing block is left. Only a statement of a { } block: the block is
 * its scope. Inside <stmt> nothing can jump out of it, so the loop
 * markers are cleared while it is parsed (a loop inside sets its own). */
static Ast *parseDeferStatement(Cctrl *cc) {
    Lexeme *kw = cctrlTokenGet(cc);
    int line = kw->line, col = kw->col, len = kw->len;
    if (!cc->tmp_fname) {
        cctrlRaiseExceptionAt(cc, line, col, len,
            "`defer` is only allowed inside a function");
    }
    Lexeme *peek = cctrlTokenPeek(cc);
    if (!peek || tokenPunctIs(peek, ';') || tokenPunctIs(peek, '}')) {
        cctrlRaiseExceptionAt(cc, line, col, len,
            "expected a statement after `defer`");
    }
    AoStr *loop_begin = cc->tmp_loop_begin;
    AoStr *loop_end = cc->tmp_loop_end;
    cc->tmp_loop_begin = NULL;
    cc->tmp_loop_end = NULL;
    cc->defer_depth++;
    Ast *stmt = parseStatement(cc);
    cc->defer_depth--;
    cc->tmp_loop_begin = loop_begin;
    cc->tmp_loop_end = loop_end;
    Ast *ast = astDefer(stmt);
    ast->line = line;
    ast->col = col;
    return ast;
}

/* `type Name = ...`: tok is `type`, next the name, after the `=`. `type`
 * is a keyword only there: a variable or field called `type` is fine. */
static int parseStartsTypeAlias(Lexeme *tok, Lexeme *next, Lexeme *after) {
    return tok && next && after && tok->tk_type == TK_IDENT && tok->len == 4 &&
           !memcmp(tok->start, "type", 4) && next->tk_type == TK_IDENT &&
           tokenPunctIs(after, '=');
}

/* `atomic` was a keyword that was silently skipped (`atomic i64 n;` was a
 * plain i64); now it is module atomic. `atomic` (tok) followed by a type
 * (next) is that old declaration: say so instead of a confusing syntax
 * error. */
static void parseRejectAtomicDecl(Cctrl *cc, Lexeme *tok, Lexeme *next) {
    if (tok && next && tok->tk_type == TK_IDENT && tok->len == 6 &&
        !memcmp(tok->start, "atomic", 6) && next->tk_type != TK_PUNCT &&
        cctrlTokIsKeyword(cc, next)) {
        cctrlRaiseExceptionAt(cc, tok->line, tok->col, tok->len,
            "`atomic` is not a keyword (it never made anything atomic): declare a plain "
            "integer and use module atomic on it, as in `atomic.add(&n, 1)`");
    }
}

Ast *parseDeclOrStatement(Cctrl *cc) {
    Lexeme *tok = cctrlTokenPeek(cc);
    if (!tok) {
        return NULL;
    }
    parseRejectAtomicDecl(cc, tok, cctrlTokenPeekBy(cc, 1));
    if ((cctrlTokIsKeyword(cc, tok) &&
         !parseTypeNameFollowedByDot(cc)) ||
        (tok->tk_type == TK_KEYWORD && tok->i64 == KW_CONST) ||
        (tokenMayStartFnType(tok) && parseFnLooksLikeType(cc))) {
        return parseDecl(cc);
    }
    return parseStatement(cc);
}

/* The statements of a `{ ... }` block into `body`, or, with `arm` set, of
 * a switch case's arm: up to the next `case`, `default` or the switch's
 * `}` (left for the switch), in a scope of its own like a block's */
static void parseBlockInternal(Cctrl *cc, Ast *body, int arm) {
    Ast *stmt = NULL;
    Ast *var = NULL;
    AstType *base_type, *type, *next_type;
    Lexeme *tok, *varname, *peek;
    int is_const;
    Map *block_scope = cctrlCreateAstMap(cc->localenv);
    cc->localenv = block_scope;
    tok = NULL;

    /* Per-statement recovery point hoisted to the function frame
     * so the jmp_buf has a stable address across iterations. A
     * longjmp here lands at the top of the iteration's body via
     * the setjmp check below; we restore the block's scope (in
     * case a half-parsed nested block left us in a child) and
     * resync the token stream to the next `;` or `}`. The outer
     * recovery (toplevel decl) is reinstated when this function
     * returns so the caller's failure mode is unchanged. */
    jmp_buf stmt_recovery;
    jmp_buf *outer_recovery = cc->current_recovery;
    cc->current_recovery = &stmt_recovery;

    tok = cctrlTokenPeek(cc);

    while (tok && !(arm ? parseArmEnds(tok) : tokenPunctIs(tok, '}'))) {
        /* Snapshot the tail of body->stms and cc->tmp_locals
         * *before* attempting the next statement. If the parse
         * longjmps mid-way, half-built nodes may already have
         * been appended; truncate back to these saved tails so
         * the recovered block has only the statements that
         * actually parsed cleanly. Allocations themselves leak
         * into the arena (freed at end of compilation), but the
         * AST no longer points at them. */
        List *volatile body_tail = (body && body->stms) ? body->stms->prev
                                                        : NULL;
        List *volatile locals_tail = cc->tmp_locals ? cc->tmp_locals->prev
                                                    : NULL;
        /* Where this statement starts in the token stream */
        volatile s64 stmt_start = cc->token_buffer->pos;
        /* An error inside a `defer <stmt>` unwinds past the code that
         * restores these (parseDeferStatement) */
        volatile int stmt_defer_depth = cc->defer_depth;
        AoStr *volatile stmt_loop_begin = cc->tmp_loop_begin;
        AoStr *volatile stmt_loop_end = cc->tmp_loop_end;

        if (setjmp(stmt_recovery) != 0) {
            cc->localenv = block_scope;
            cc->defer_depth = stmt_defer_depth;
            cc->tmp_loop_begin = stmt_loop_begin;
            cc->tmp_loop_end = stmt_loop_end;
            if (body && body->stms && body_tail) {
                body_tail->next = body->stms;
                body->stms->prev = body_tail;
            }
            if (cc->tmp_locals && locals_tail) {
                locals_tail->next = cc->tmp_locals;
                cc->tmp_locals->prev = locals_tail;
            }
            /* A diagnostic can rewind to the token it points at, and
             * that may be before this statement: resync from no
             * earlier than its start. Syncing from an earlier `;`
             * would parse this statement again, fail the same way
             * and never get past it. */
            while (cc->token_buffer->pos < stmt_start &&
                   cctrlTokenGet(cc) != NULL);
            cctrlSyncStatement(cc);
            tok = cctrlTokenPeek(cc);
            continue;
        }

        parseRejectAtomicDecl(cc, tok, cctrlTokenPeekBy(cc, 1));
        if (parseStartsTypeAlias(tok, cctrlTokenPeekBy(cc, 1), cctrlTokenPeekBy(cc, 2))) {
            cctrlRaiseExceptionAt(cc, tok->line, tok->col, tok->len,
                "a type alias goes at file scope, not inside a function");
        }

        if (tok->tk_type == TK_KEYWORD && tok->i64 == KW_DEFER) {
            listAppend(body->stms, parseDeferStatement(cc));
            tok = cctrlTokenPeek(cc);
            continue;
        }

        /* `scoped T name = ...;`: `defer name.free();` follows */
        Lexeme *scoped_tok = NULL;
        int scoped_word = parseScopedWord(tok);
        if (scoped_word) {
            Lexeme *next = cctrlTokenPeekBy(cc, 1);
            if (next && next->tk_type != TK_PUNCT && cctrlTokIsKeyword(cc, next)) {
                scoped_tok = cctrlTokenGet(cc);
                if (scoped_word == 2) {
                    parseWarnAd(cc, scoped_tok);
                }
                tok = cctrlTokenPeek(cc);
            } else if (next && next->tk_type == TK_IDENT) {
                /* `ad Name x` / `ad mod.Name x`: a declaration whose type
                 * isn't known (two names in a row are no expression) */
                Lexeme *after = cctrlTokenPeekBy(cc, 2);
                Lexeme *member = after && tokenPunctIs(after, '.') ? cctrlTokenPeekBy(cc, 3) : NULL;
                if (member) {
                    parseRaiseIfNotImported(cc, next, next->start, next->len);
                }
                if (member && member->tk_type == TK_IDENT) {
                    cctrlRaiseExceptionAt(cc, next->line, next->col, next->len,
                        "Type `%.*s.%.*s` has not been declared", next->len, next->start,
                        member->len, member->start);
                }
                cctrlRaiseExceptionAt(cc, next->line, next->col, next->len,
                    "Type `%.*s` has not been declared", next->len, next->start);
            }
        }

        is_const = 0;
        if (tok->tk_type == TK_KEYWORD && tok->i64 == KW_CONST) {
            is_const = 1;
            cctrlTokenGet(cc);
            tok = cctrlTokenPeek(cc);
        }

        if ((cctrlTokIsKeyword(cc, tok) &&
             !parseTypeNameFollowedByDot(cc)) ||
            (tokenMayStartFnType(tok) && parseFnLooksLikeType(cc))) {
            base_type = parseBaseDeclSpec(cc);
            while (1) {
                next_type = parsePointerType(cc,base_type);
                int pinned_kind;
                AoStr *pinned_reg;
                parseRegModifier(cc, &pinned_kind, &pinned_reg);
                peek = cctrlTokenPeek(cc);

                if (!tokenPunctIs(peek,'(')) {
                    /* A normal variable */
                    varname = cctrlTokenGetRequired(cc);
                    /* may shadow an import prefix, see cctrlTokenGet */
                    if (varname->tk_type != TK_IDENT) {
                        cctrlTokenRewind(cc);
                        cctrlRaiseException(cc,"Expected type declaration with identifer got '%.*s' - should be"ESC_BLUE" '%s "ESC_RESET ESC_BOLD"<var_name>'",
                                peek->len, peek->start, astTypeToString(next_type));
                        break;
                    }
                    parseCheckNotReserved(cc, varname);
                    type = parseArrayDimensions(cc,next_type);
                    var = astLVar(type,varname->start,varname->len);
                    var->line = varname->line;
                    var->col = varname->col;
                    var->pinned_kind = pinned_kind;
                    var->pinned_reg = pinned_reg;
                    if (is_const) {
                        var->flags |= AST_FLAG_CONST;
                        if (!tokenPunctIs(cctrlTokenPeek(cc),'=')) {
                            cctrlRaiseException(cc,"const '%.*s' needs an initializer",
                                    varname->len, varname->start);
                        }
                    }
                    if (!mapAddOrErr(cc->localenv,var->lname->data,var)) {
                        cctrlRewindUntilStrMatch(cc,var->lname->data,var->lname->len,NULL);
                        cctrlRaiseException(cc,"variable `%s` already declared",
                                astLValueToString(var,0));
                    }
                } else {
                    cctrlTokenGet(cc);
                    var = parseFunctionPointer(cc,next_type);
                    /* An array of function pointers is an AST_LVAR */
                    AoStr *var_name = var->kind == AST_LVAR ? var->lname : var->fname;
                    mapAdd(cc->localenv,var_name->data,var);
                }

                if (cc->tmp_locals) {
                    listAppend(cc->tmp_locals, var);
                }

                stmt = parseVariableInitialiser(cc,var,PUNCT_TERM_COMMA|PUNCT_TERM_SEMI);
                if (next_type->kind == AST_TYPE_AUTO) {
                    parseAssignAuto(cc,stmt);
                }

                if (is_const && stmt && stmt->kind == AST_DECL &&
                    stmt->declinit && astIsIntType(var->type))
                {
                    int const_ok = 1;
                    s64 const_val = evalIntConstExprOrErr(stmt->declinit, &const_ok);
                    if (const_ok) {
                        var->deref_symbol = const_val;
                        var->flags |= AST_FLAG_CONST_VALUE;
                    }
                }

                if (stmt) {
                    listAppend(body->stms,stmt);
                }
                if (scoped_tok) {
                    /* Without a value the variable starts zeroed (every
                     * local does, each time its declaration runs): an
                     * empty struct or slice, which its free() takes, or a
                     * null pointer, freed only once it holds something */
                    int no_value = !stmt || stmt->kind != AST_DECL || !stmt->declinit;
                    var->flags |= AST_FLAG_AUTODEFER;
                    Ast *free_call = parseAutoDeferFree(cc, var, scoped_tok);
                    if (no_value && var->type->kind == AST_TYPE_POINTER) {
                        free_call = astIf(var, free_call, NULL);
                    }
                    Ast *deferred = astDefer(free_call);
                    deferred->line = scoped_tok->line;
                    deferred->col = scoped_tok->col;
                    listAppend(body->stms, deferred);
                }
                cctrlTokenRewind(cc);
                tok = cctrlTokenGet(cc);

                if (tokenPunctIs(tok, ',')) {
                    continue;
                } else if (tokenPunctIs(tok,';')) {
                    break;
                } else {
                    cctrlRaiseException(cc,"Unexpected %s `%s` while parsing statement, perhaps you meant to terminate the declaration with `;`?",
                            lexemeTypeToString(tok->tk_type), lexemeAsWritten(tok));
                }
            }
        } else if (tokenPunctIs(tok, ';')) {
            /* The empty statement; ending the block here made the next
             * statement a "perhaps you meant `}`" error */
            cctrlTokenGet(cc);
        } else { 
            int ft_before = cc->fallthrough_pending;
            stmt = parseStatement(cc);
            /* (a `case` of the switch's own block carries its arm's
             * `fallthrough` out to parseSwitchStatement) */
            if (!ft_before && cc->fallthrough_pending &&
                !(stmt && (stmt->kind == AST_CASE || stmt->kind == AST_DEFAULT))) {
                /* `fallthrough;` here, or a block ending in it: only as
                 * the block's last statement, and not under an `if`, a
                 * loop or the like */
                s64 line = cc->fallthrough_line, col = cc->fallthrough_col;
                if (stmt && stmt->kind != AST_COMPOUND_STMT) {
                    cc->fallthrough_pending = 0;
                    cctrlRaiseExceptionAt(cc, line, col, 11,
                        "`fallthrough` must be the last statement of its case, "
                        "not inside a block or another statement");
                }
                if (stmt) listAppend(body->stms, stmt);
                tok = cctrlTokenPeek(cc);
                if (tok && !(arm ? parseArmEnds(tok) : tokenPunctIs(tok, '}'))) {
                    cc->fallthrough_pending = 0;
                    cctrlRaiseExceptionAt(cc, line, col, 11,
                        "`fallthrough` must be the last statement of its case");
                }
                continue;
            }
            if (stmt != NULL) {
                if (stmt->kind == AST_DECL && stmt->declvar->kind == AST_GVAR) {
                    tok = cctrlTokenPeek(cc);
                    continue;
                }
                listAppend(body->stms,stmt);
            } else {
                break;
            }
        }
        tok = cctrlTokenPeek(cc);
    }
    cc->current_recovery = outer_recovery;
    cc->localenv = cc->localenv->parent;
    if (!arm) {
        cctrlTokenExpect(cc,'}');
    }
    cctrlTokenPeek(cc);
}

void parseCompoundStatementInternal(Cctrl *cc, Ast *body) {
    parseBlockInternal(cc, body, 0);
}

/* A case's arm (parseCaseArm): its statements as one block */
static Ast *parseArmBlock(Cctrl *cc) {
    Ast *block = astCompountStatement(listNew());
    parseBlockInternal(cc, block, 1);
    return block;
}

Ast *parseCompoundStatement(Cctrl *cc) {
    List *stmts = listNew();
    Ast *ast_compound = astCompountStatement(stmts);
    parseCompoundStatementInternal(cc, ast_compound);
    return ast_compound;
}

/* ---- "non-void function falls off the end" analysis ----
 *
 * `astStmtFallsThrough` answers: can control flow run off the end of
 * `s` (i.e. reach the next statement) rather than diverting via
 * return/throw/goto/infinite-loop? A non-void function whose body falls
 * through can return an undefined value, which is almost always a
 * missing `return`. The analysis is deliberately conservative -
 * anything it can't model returns "falls through" only when that's the
 * safe assumption, so we under-warn rather than cry wolf. */

static int astIsConstTrueCond(Ast *c) {
    /* `while(1)` / `while (true)` / `for(;1;)` / `for(;;)` style infinite
     * loops (`true` is a literal of type bool) */
    return c && c->kind == AST_LITERAL && c->type &&
           (c->type->kind == AST_TYPE_INT || c->type->is_bool) && c->i64 != 0;
}

/* Does `s` contain a `break` that targets the *enclosing* loop/switch
 * (i.e. not one captured by a nested loop/switch of its own)? */
static int astStmtHasBreak(Ast *s) {
    if (!s) return 0;
    switch (s->kind) {
        case AST_BREAK:
            return 1;
        /* Nested loops / switch capture their own breaks. */
        case AST_FOR:
        case AST_WHILE:
        case AST_DO_WHILE:
        case AST_SWITCH:
            return 0;
        case AST_COMPOUND_STMT:
            if (s->stms) {
                listForEach(s->stms) {
                    if (astStmtHasBreak((Ast *)it->value)) return 1;
                }
            }
            return 0;
        case AST_IF:
            return astStmtHasBreak(s->then) || astStmtHasBreak(s->els);
        default:
            return 0;
    }
}

/* Does `s` hold a label, which a goto can jump to? */
static int astStmtHasLabel(Ast *s) {
    if (!s) return 0;
    switch (s->kind) {
        case AST_LABEL:
            return 1;
        case AST_COMPOUND_STMT:
            if (s->stms) {
                listForEach(s->stms) {
                    if (astStmtHasLabel((Ast *)it->value)) return 1;
                }
            }
            return 0;
        case AST_IF:
            return astStmtHasLabel(s->then) || astStmtHasLabel(s->els);
        case AST_FOR:
            return astStmtHasLabel(s->forbody);
        case AST_WHILE:
        case AST_DO_WHILE:
            return astStmtHasLabel(s->whilebody);
        case AST_SWITCH:
            if (s->cases) {
                for (u64 i = 0; i < s->cases->size; ++i) {
                    if (astStmtHasLabel(vecGet(Ast *, s->cases, i))) return 1;
                }
            }
            return astStmtHasLabel(s->case_default);
        case AST_CASE:
        case AST_DEFAULT:
            if (s->case_asts) {
                listForEach(s->case_asts) {
                    if (astStmtHasLabel((Ast *)it->value)) return 1;
                }
            }
            return 0;
        case AST_TRY:
            return astStmtHasLabel(s->try_body) ||
                   astStmtHasLabel(s->catch_body);
        default:
            return 0;
    }
}

static int astStmtFallsThrough(Ast *s) {
    if (!s) return 1;
    switch (s->kind) {
        case AST_RETURN:
        case AST_THROW:
        case AST_GOTO:    /* control leaves, doesn't reach the end */
        case AST_JUMP:
            return 0;
        case AST_COMPOUND_STMT: {
            /* After a return or goto the next statements are only
             * reached through a label in them, and from there control
             * carries on as usual. */
            int reachable = 1;
            if (s->stms) {
                listForEach(s->stms) {
                    Ast *stmt = (Ast *)it->value;
                    if (!reachable && astStmtHasLabel(stmt)) reachable = 1;
                    if (reachable) reachable = astStmtFallsThrough(stmt);
                }
            }
            return reachable;
        }
        case AST_IF:
            /* No else: the condition-false path reaches the end. */
            if (!s->els) return 1;
            return astStmtFallsThrough(s->then) ||
                   astStmtFallsThrough(s->els);
        case AST_FOR:
            if ((s->forcond == NULL || astIsConstTrueCond(s->forcond)) &&
                !astStmtHasBreak(s->forbody))
                return 0;
            return 1;
        case AST_WHILE:
        case AST_DO_WHILE:
            if (astIsConstTrueCond(s->whilecond) &&
                !astStmtHasBreak(s->whilebody))
                return 0;
            return 1;
        case AST_SWITCH:
            /* A switch with no default leaves the unmatched value to
             * reach the end. With a default we assume the author
             * handled every arm (the common exhaustive-switch-with-
             * returns pattern) rather than risk a false positive. */
            return s->case_default ? 0 : 1;
        case AST_TRY:
            /* Control leaves the try either at the end of the body or,
             * after a throw, at the end of the handler. */
            return astStmtFallsThrough(s->try_body) ||
                   astStmtFallsThrough(s->catch_body);
        default:
            /* Plain statements (calls, decls, assignments, ...). */
            return 1;
    }
}

/* Naked `asm { }` functions get no compiler-generated epilogue - the
 * body is pasted verbatim, so a missing `RET` lets the CPU fall off the
 * end into whatever follows (typically a SIGILL). Heuristic: scan for a
 * whole-word control transfer that ends the function - RET/ERET/IRET or
 * a tail branch (JMP/B/BR) - case-insensitive. A tail branch counts so
 * legitimate tail-call exits don't trip the warning. */
static int asmTextHasReturn(AoStr *text) {
    static const char *exits[] = { "ret", "eret", "iret", "jmp", "br", "b" };
    if (!text || !text->data) return 0;
    const char *s = text->data;
    int n = (int)text->len;
    for (int i = 0; i < n; i++) {
        if (i > 0) {
            char p = s[i - 1];   /* only test at a word start */
            if ((p >= 'A' && p <= 'Z') || (p >= 'a' && p <= 'z') ||
                (p >= '0' && p <= '9') || p == '_')
                continue;
        }
        for (size_t k = 0; k < sizeof(exits) / sizeof(exits[0]); k++) {
            const char *w = exits[k];
            int wl = (int)strlen(w);
            if (i + wl > n) continue;
            int match = 1;
            for (int j = 0; j < wl; j++) {
                char a = s[i + j];
                if (a >= 'A' && a <= 'Z') a = (char)(a - 'A' + 'a');
                if (a != w[j]) { match = 0; break; }
            }
            if (!match) continue;
            char after = (i + wl < n) ? s[i + wl] : '\0';   /* whole word */
            if ((after >= 'A' && after <= 'Z') || (after >= 'a' && after <= 'z') ||
                (after >= '0' && after <= '9') || after == '_')
                continue;
            return 1;
        }
    }
    return 0;
}

/* ---- `goto` into a try body ----
 *
 * Entering a try body runs KTM_PushFrame; a goto that lands inside one
 * from outside it (or from its catch handler, which runs after the
 * frame is popped) skips that, and leaving the body then pops a frame
 * that was never pushed. Like C++, reject it. Jumps within a try body
 * and out of it are fine.
 *
 * A label is reachable from a goto when the label's innermost try body
 * also contains the goto: every try around that one contains it too. */

typedef struct ParseTryScope {
    Ast *try_ast;
    struct ParseTryScope *outer;
} ParseTryScope;

static void parseLabelTriesWalk(Map *label_try, Ast *s, Ast *inner_try);

static void parseLabelTriesWalkList(Map *label_try, List *l, Ast *inner_try) {
    if (!l) return;
    listForEach(l) {
        parseLabelTriesWalk(label_try, (Ast *)it->value, inner_try);
    }
}

/* Record the innermost try body around each label (labels outside any
 * try are left out). The rest of a block after a `defer` counts as a try
 * body too: jumping into it would skip registering the defer. */
static void parseLabelTriesWalk(Map *label_try, Ast *s, Ast *inner_try) {
    if (!s) return;
    switch (s->kind) {
        case AST_LABEL:
            if (inner_try && s->slabel) {
                mapAddLen(label_try, s->slabel->data, s->slabel->len,
                          inner_try);
            }
            return;
        case AST_COMPOUND_STMT:
            if (s->stms) {
                listForEach(s->stms) {
                    Ast *stmt = (Ast *)it->value;
                    if (stmt && stmt->kind == AST_DEFER) inner_try = stmt;
                    parseLabelTriesWalk(label_try, stmt, inner_try);
                }
            }
            return;
        case AST_IF:
            parseLabelTriesWalk(label_try, s->then, inner_try);
            parseLabelTriesWalk(label_try, s->els, inner_try);
            return;
        case AST_FOR:
            parseLabelTriesWalk(label_try, s->forinit, inner_try);
            parseLabelTriesWalk(label_try, s->forbody, inner_try);
            return;
        case AST_WHILE:
        case AST_DO_WHILE:
            parseLabelTriesWalk(label_try, s->whilebody, inner_try);
            return;
        case AST_SWITCH:
            if (s->cases) {
                for (u64 i = 0; i < s->cases->size; ++i) {
                    parseLabelTriesWalk(label_try,
                                        vecGet(Ast *, s->cases, i),
                                        inner_try);
                }
            }
            parseLabelTriesWalk(label_try, s->case_default, inner_try);
            return;
        case AST_CASE:
        case AST_DEFAULT:
            parseLabelTriesWalkList(label_try, s->case_asts, inner_try);
            return;
        case AST_TRY:
            parseLabelTriesWalk(label_try, s->try_body, s);
            parseLabelTriesWalk(label_try, s->catch_body, inner_try);
            return;
        default:
            return;
    }
}

static void parseGotoIntoTryWalk(Cctrl *cc, Map *label_try, Ast *s,
                                 ParseTryScope *scope);

static void parseGotoIntoTryWalkList(Cctrl *cc, Map *label_try, List *l,
                                     ParseTryScope *scope)
{
    if (!l) return;
    listForEach(l) {
        parseGotoIntoTryWalk(cc, label_try, (Ast *)it->value, scope);
    }
}

static void parseGotoIntoTryWalk(Cctrl *cc, Map *label_try, Ast *s,
                                 ParseTryScope *scope)
{
    if (!s) return;
    switch (s->kind) {
        case AST_GOTO: {
            if (!s->slabel) return;
            Ast *target_try = (Ast *)mapGetLen(label_try, s->slabel->data,
                                               s->slabel->len);
            if (!target_try) return;
            for (ParseTryScope *it = scope; it; it = it->outer) {
                if (it->try_ast == target_try) return;
            }
            /* Labels are stored as `.<function>_<name>`. */
            char *name = s->slabel->data;
            if (cc->tmp_fname && s->slabel->len > cc->tmp_fname->len + 2) {
                name += cc->tmp_fname->len + 2;
            }
            if (target_try->kind == AST_DEFER) {
                cctrlRaiseExceptionAt(cc, s->line, s->col, 4,
                    "`goto %s` jumps past a `defer` into its scope", name);
                return;
            }
            cctrlRaiseExceptionAt(cc, s->line, s->col, 4,
                "`goto %s` jumps into a try body from outside it; "
                "a try body can only be entered from its start", name);
            return;
        }
        case AST_COMPOUND_STMT: {
            if (!s->stms) return;
            /* Each `defer` opens a scope for the rest of the block, held
             * on this frame; the walk is depth first so they outlive it */
            ParseTryScope *defers = NULL;
            int ndefers = 0;
            listForEach(s->stms) {
                Ast *stmt = (Ast *)it->value;
                if (stmt && stmt->kind == AST_DEFER) ndefers++;
            }
            if (ndefers) {
                defers = (ParseTryScope *)malloc(sizeof(ParseTryScope) * ndefers);
            }
            int d = 0;
            listForEach(s->stms) {
                Ast *stmt = (Ast *)it->value;
                if (stmt && stmt->kind == AST_DEFER) {
                    defers[d].try_ast = stmt;
                    defers[d].outer = scope;
                    scope = &defers[d++];
                }
                parseGotoIntoTryWalk(cc, label_try, stmt, scope);
            }
            free(defers);
            return;
        }
        case AST_IF:
            parseGotoIntoTryWalk(cc, label_try, s->then, scope);
            parseGotoIntoTryWalk(cc, label_try, s->els, scope);
            return;
        case AST_FOR:
            parseGotoIntoTryWalk(cc, label_try, s->forinit, scope);
            parseGotoIntoTryWalk(cc, label_try, s->forbody, scope);
            return;
        case AST_WHILE:
        case AST_DO_WHILE:
            parseGotoIntoTryWalk(cc, label_try, s->whilebody, scope);
            return;
        case AST_SWITCH:
            if (s->cases) {
                for (u64 i = 0; i < s->cases->size; ++i) {
                    parseGotoIntoTryWalk(cc, label_try,
                                         vecGet(Ast *, s->cases, i), scope);
                }
            }
            parseGotoIntoTryWalk(cc, label_try, s->case_default, scope);
            return;
        case AST_CASE:
        case AST_DEFAULT:
            parseGotoIntoTryWalkList(cc, label_try, s->case_asts, scope);
            return;
        case AST_TRY: {
            ParseTryScope inner = { .try_ast = s, .outer = scope };
            parseGotoIntoTryWalk(cc, label_try, s->try_body, &inner);
            parseGotoIntoTryWalk(cc, label_try, s->catch_body, scope);
            return;
        }
        default:
            return;
    }
}

static void parseCheckGotoIntoTry(Cctrl *cc, Ast *body) {
    Map *label_try = mapNew(8, &map_cstring_opaque_type);
    parseLabelTriesWalk(label_try, body, NULL);
    if (label_try->size) {
        parseGotoIntoTryWalk(cc, label_try, body, NULL);
    }
    mapRelease(label_try);
}

static void parseOverloadJoin(Ast *fn, Vec *set);
static int parseSameParamTypes(Vec *a, Vec *b);

Ast *parseFunctionDef(Cctrl *cc, AstType *rettype,
        char *fname, int len, Vec *params, int has_var_args, int is_inline,
        s64 name_line, s64 name_col)
{
    Lexeme *next = cctrlTokenPeek(cc);
    /* Anchor for the asm no-RET warning: by the time it is raised the
     * cursor has moved past the asm body. Snapshot the body's opening
     * token now so the warning points at this function. (The
     * missing-return warning points at the name: name_line/name_col.) */
    s64 fn_line = next ? next->line : cc->lineno;
    s64 fn_col  = next ? next->col  : 0;
    s64 fn_len  = next ? next->len  : 1;
    int errors_before = cc->errors_raised;
    if (next == NULL) {
        cctrlRaiseException(cc,
                "Unexpected end of input in function definition");
    }
    if (next->tk_type == TK_KEYWORD && next->i64 == KW_ASM) {
        cctrlTokenGet(cc);
        Lexeme *peek = cctrlTokenPeek(cc);
        if (tokenPunctIs(peek,'{')) {
            AoStr *fname_duped = aoStrDupRaw(fname,len);
            /* Target-aware: adds the leading `_` on Mach-O so it
             * matches what `bl _Add` callers expect. The compile-time
             * `astNormaliseFunctionName` only handles IS_BSD and would
             * leave the symbol as `Add` (no underscore) on this build. */
            AoStr *asm_fname = aoStrPrintf("%s",
                asmNormaliseFunctionName(cc, fname_duped));
            AoStr *prev_asm_name = cc->tmp_asm_fname;
            cc->tmp_asm_fname = asm_fname;
            Ast *asm_block = prsAsm(cc,1);

            /* If any param is `reg <REG>` pinned, emit shuffle moves
             * up-front so the user's asm can refer to the pinned
             * register names. The asm-only-function path bypasses
             * the normal ParamSpills path that runs for IR-codegen'd
             * functions; we splice the shuffle directly into the asm
             * text. Note: this path doesn't save/restore callee-
             * saved regs - the user's `RET` exits before any epilogue
             * can run, so if the body clobbers callee-saved registers
             * the caller is on the hook for that ABI rule.
             * asm functions are explicit by design.
             *
             * Target-aware: AArch64 uses x0..x7 (8 int arg regs, mov
             * "dst, src" order); x86_64 SysV uses rdi/rsi/rdx/rcx/r8/r9
             * (6 int arg regs, AT&T "movq %src, %dst" order). */
            const char **kIntArgRegs;
            int max_int_args;
            int is_x86; /* kept for symmetry with the switch below */
            switch (cc->target) {
                case TARGET_AARCH64_APPLE_DARWIN:
                case TARGET_AARCH64_UNKNOWN_LINUX_GNU: {
                    static const char *aarch64_regs[] = {
                        "x0", "x1", "x2", "x3",
                        "x4", "x5", "x6", "x7"
                    };
                    kIntArgRegs = aarch64_regs;
                    max_int_args = 8;
                    is_x86 = 0;
                    break;
                }
                case TARGET_X86_64_APPLE_DARWIN:
                case TARGET_X86_64_UNKNOWN_LINUX_GNU: {
                    static const char *x86_regs[] = {
                        "rdi", "rsi", "rdx", "rcx", "r8", "r9"
                    };
                    kIntArgRegs = x86_regs;
                    max_int_args = 6;
                    is_x86 = 1;
                    break;
                }
                default:
                    kIntArgRegs = NULL;
                    max_int_args = 0;
                    is_x86 = 0;
            }
            (void)is_x86;

            if (params && kIntArgRegs) {
                AoStr *shuffle = aoStrNew();
                int int_idx = 0;
                for (u64 pi = 0; pi < params->size; ++pi) {
                    Ast *p = vecGet(Ast *, params, pi);
                    if (!p || p->kind != AST_LVAR) continue;
                    if (p->pinned_kind != LVAR_REG || !p->pinned_reg) {
                        if (p->type && p->type->kind != AST_TYPE_FLOAT) {
                            int_idx++;
                        }
                        continue;
                    }
                    if (int_idx < max_int_args) {
                        /* libtasm dialect, both arches: "mov dst, src"
                         * => pinned = arg. */
                        aoStrCatFmt(shuffle,
                                "\tMOV %S, %s\n",
                                p->pinned_reg, kIntArgRegs[int_idx]);
                    }
                    int_idx++;
                }
                if (shuffle->len > 0) {
                    aoStrCatAoStr(shuffle, asm_block->asm_stmt);
                    aoStrRelease(asm_block->asm_stmt);
                    asm_block->asm_stmt = shuffle;
                } else {
                    aoStrRelease(shuffle);
                }
            }

            Ast *asm_function = astAsmFunctionDef(asm_fname, asm_block->asm_stmt);

            listAppend(asm_block->funcs, asm_function);
            /* a module's declarations pass: the full pass emits it */
            if (!cc->decls_only) listAppend(cc->asm_blocks, asm_block);

            Ast *asm_func = astAsmFunctionBind(
                    astMakeFunctionType(rettype, params),
                    asm_fname,asm_fname,params);

            /* REPL/LSP: a reparse redefines; shadow rather than abort.
             * So does a module's next pass over the same file. */
            if (!mapAddOrErr(cc->asm_functions, asm_fname->data, asm_func)) {
                if ((cc->flags & CCTRL_REPL) || cc->cur_module) {
                    mapRemove(cc->asm_functions, asm_fname->data);
                    mapAdd(cc->asm_functions, asm_fname->data, asm_func);
                } else {
                    cctrlIce(cc, "Already defined assembly function: %s", asm_fname->data);
                }
            }

            if (!mapAddOrErr(cc->global_env, fname_duped->data, asm_func)) {
                if ((cc->flags & CCTRL_REPL) || cc->cur_module) {
                    mapRemove(cc->global_env, fname_duped->data);
                    mapAdd(cc->global_env, fname_duped->data, asm_func);
                } else {
                    cctrlIce(cc, "Already defined assembly function: %s as a non Assembly function", asm_fname->data);
                }
            }

            if (is_inline) {
                asm_func->flags = AST_FLAG_INLINE;
            }

            if (!asmTextHasReturn(asm_block->asm_stmt)) {
                cctrlWarningAt(cc, fn_line, fn_col, fn_len,
                    "asm function '%.*s' has no RET; execution runs off the "
                    "end of the function - naked asm functions get no "
                    "epilogue, so add a `RET` yourself",
                    len, fname);
            }

            cctrlTokenExpect(cc,'}');
            cc->tmp_asm_fname = prev_asm_name;
            return asm_func;
        } else {
            cctrlRaiseException(cc,"Floating \"asm\" keyword, expected \"asm {\" got - %.*s", 
                    peek->len, peek->start);
        }
    }

    List *locals = listNew();
    Ast *func = NULL;
    List *body = listNew();
    Ast *func_body = astCompountStatement(body);
    AstType *fn_type = NULL;

    cc->localenv = cctrlCreateAstMap(cc->localenv);
    cc->tmp_locals = locals;
    cc->lit_writes = listNew();
    cc->elide_open = listNew();
    cc->elide_done = listNew();

    /* Upgrade a prototype to an actual function */
    func = mapGetLen(cc->global_env, fname, len);
    if (!func) {
        cc->tmp_params = params;
        cc->tmp_rettype = rettype;
        fn_type = astMakeFunctionType(cc->tmp_rettype, params);
        func = astFunction(fn_type,fname,len,params,NULL,locals,
                has_var_args);
        mapAdd(cc->global_env, func->fname->data, func);
        parseOverloadJoin(func, cc->tmp_overload_set);
        cc->tmp_overload_set = NULL;
    } else {
        switch (func->kind) {
            case AST_EXTERN_FUNC:
                if (!parseSameParamTypes(func->params, params)) {
                    cctrlRaiseException(cc,"`%.*s` is an extern \"c\" function: "
                            "it has one C symbol, so it can't be overloaded", len, fname);
                }
                cctrlRaiseException(cc,"Cannot redefine extern function: %.*s",len,fname);

            case AST_FUNC:
                if (cc->flags & CCTRL_REPL) {
                    /* REPL: shadow the old definition with a fresh
                     * function AST. Code already compiled against the
                     * old body keeps its old address; anything parsed
                     * from here on binds to this one. */
                    cc->tmp_params = params;
                    cc->tmp_rettype = rettype;
                    fn_type = astMakeFunctionType(cc->tmp_rettype, params);
                    Ast *old_fn = func;
                    func = astFunction(fn_type,fname,len,params,NULL,locals,
                            has_var_args);
                    mapAdd(cc->global_env, func->fname->data, func);
                    if (old_fn->overloads) {
                        /* the LSP: the new body takes the old one's place */
                        Vec *set = old_fn->overloads;
                        for (u64 i = 0; i < set->size; ++i) {
                            if (set->entries[i] == old_fn) set->entries[i] = func;
                        }
                        func->overloads = set;
                        func->flags |= AST_FLAG_OVERLOAD;
                    }
                    break;
                }
                cctrlRaiseException(cc,"Cannot redefine function: %s",
                        astModuleDemangle(fname, len));

            case AST_ASM_FUNC_BIND:
                cctrlRaiseException(cc,"Cannot redefine asm function: %.*s",
                        len,fname);

            case AST_FUN_PROTO:
                /* upgrade prototype to a function */
                func->locals = cc->tmp_locals;
                func->params = params;
                cc->tmp_params = func->params;
                cc->tmp_rettype = func->type->rettype;
                fn_type = func->type;
                func->kind = AST_FUNC;
                break;

            default:
                cctrlRaiseException(cc,"Unexpected function: %.*s -> %s",
                        cc->lineno,
                        len,fname, astToString(func));
                break;
        }
    }

    /* XXX: This allows us to do recursion by parsing the body after */
    cc->tmp_fname = func->fname;
    Ast *prev_func = cc->tmp_func;
    cc->tmp_func = func;
    func->body = func_body;

    if (is_inline) {
        func->flags |= AST_FLAG_INLINE;
        func->inline_ret = astLVar(func->type->rettype, str_lit("retval"));
    }
    /* `T ...name`: in the body, name is a `T[]` (u64[] untyped) of the
     * hidden pointer and count parameters, made first thing */
    if (params && params->size >= 2) {
        Ast *data = params->entries[params->size - 1];
        Ast *count = params->entries[params->size - 2];
        if (data && data->kind == AST_LVAR && (data->flags & AST_FLAG_VARARGS)) {
            Ast *slice = astLVar(astMakeSliceType(data->type->ptr),
                                 data->lname->data, data->lname->len);
            slice->flags |= AST_FLAG_VARARGS;
            slice->line = data->line;
            slice->col = data->col;
            mapAdd(cc->localenv, slice->lname->data, slice);
            listAppend(locals, slice);
            listAppend(body, astSliceMake(slice, data, count));
        }
    }
    parseCompoundStatementInternal(cc, func_body);
    parseCheckGotoIntoTry(cc, func_body);
    parseCheckOverloadRefs(cc);
    fn_type->rettype = cc->tmp_rettype;
    /* `astFunction` shallow-copies the type into the AST node, so
     * `func->type` is a different AstType than the local `fn_type`.
     * Updating only `fn_type->rettype` would leave the version stored
     * in `cc->global_env` (and hence visible to every later caller)
     * still pointing at the original `auto` type. Mirror the
     * resolution onto `func->type` too. */
    if (func->type) {
        func->type->rettype = cc->tmp_rettype;
    }
    if (is_inline) {
        listAppend(func->locals,func->inline_ret);
    }

    /* Rule: a function containing an `asm { }` block may
     * declare register-pinned locals via `<Type> reg <REG> name`;
     * the asm block can then reference the register directly. Plain
     * locals (default stack) still work as ordinary stack slots that
     * the asm can reach through `&var[RBP]`. We reject `noreg` here
     * because the explicit-stack form duplicates the default and just
     * adds complexity. */
    int has_asm = 0;
    if (func_body->stms) {
        listForEach(func_body->stms) {
            Ast *stmt = (Ast *)it->value;
            if (stmt && stmt->kind == AST_ASM_STMT) {
                has_asm = 1;
                break;
            }
        }
    }
    if (has_asm && func->locals) {
        listForEach(func->locals) {
            Ast *l = (Ast *)it->value;
            if (!l || l->kind != AST_LVAR) continue;
            if (l->pinned_kind == LVAR_NOREG) {
                cctrlRaiseException(cc,
                    "`noreg` is not supported in `asm { }` functions; "
                    "drop the modifier (locals default to a stack slot) "
                    "or use `reg <REG>` to pin to a register",
                    l->lname ? l->lname->data : "?",
                    len, fname);
            }
        }
    }

    /* Warn when a value-returning function can run off its end without
     * a `return` - control would then return whatever happens to be in
     * the return register. `inline` functions return through `retval`
     * and `asm { }` bodies are opaque, so neither is checked. A body
     * with an error may have lost its `return` to the error (`return
     * undefined(1);`): the error is the whole story. */
    AstType *rt = func->type ? func->type->rettype : NULL;
    if (rt && rt->kind != AST_TYPE_VOID && !(func->flags & AST_FLAG_INLINE) &&
        !has_asm && cc->errors_raised == errors_before &&
        astStmtFallsThrough(func_body))
    {
        cctrlWarningAt(cc, name_line, name_col, len,
            "control may reach the end of non-void function '%s' "
            "without returning a value",
            astModuleDemangle(fname, len));
    }

    parseCheckLiteralWrites(cc);
    parseElideSettle(cc);
    cc->localenv = NULL;
    cc->tmp_locals = NULL;
    cc->tmp_rettype = NULL;
    cc->tmp_params = NULL;
    cc->tmp_fname = NULL;
    cc->tmp_func = prev_func;
    return func;
}

/* A main-program global (not static, not a module's) gets its own
 * symbol, as its functions do (AST_FLAG_PROGRAM, astProgramSymbol): a
 * program's `environ` or `errno` can't replace the C library's. */
static void parseProgramGlobal(Cctrl *cc, Ast *gvar) {
    if (!cc->decl_from_main || cc->cur_module || gvar->is_static ||
        memchr(gvar->gname->data, '.', gvar->gname->len)) {
        return;
    }
    char *sym = astProgramSymbol(gvar->gname->data, gvar->gname->len);
    gvar->glabel = aoStrDupRaw(sym, strlen(sym));
    gvar->flags |= AST_FLAG_PROGRAM;
}

Ast *parseExternFunctionProto(Cctrl *cc, AstType *rettype, char *fname, int len) {
    Ast *func;
    int has_var_args = 0;
    Lexeme *tok;
    cc->localenv = cctrlCreateAstMap(cc->localenv);
    cc->tmp_locals = NULL;

    Vec *params = parseParams(cc,')', &has_var_args,1);
    tok = cctrlTokenGet(cc);
    if (!tokenPunctIs(tok, ';')) {
        cctrlRaiseException(cc,"extern %.*s() cannot have a function body "
                "this will be defined elsewhere",
                len,fname);
    }
    AstType *type = astMakeFunctionType(rettype, params);
    func = astFunction(type,fname,len,params,NULL,NULL,0);
    func->kind = AST_EXTERN_FUNC;
    /* In a module it is the module's name (`a.b.open`, private unless
     * pub), still calling the C symbol `open` */
    char *qual = cctrlDeclName(cc, fname, len);
    if (qual) {
        func->c_qual = aoStrDupRaw(qual, strlen(qual));
        mapAdd(cc->global_env, qual, func);
        mapAdd(cc->c_externs, func->fname->data, func);
        if (cc->decl_pub) setAdd(cc->pub_names, qual);
        return func;
    }
    /* The program's own C declaration (not one the stdlib headers
     * already make, which modules may use) */
    if (cc->decl_from_main && !mapGet(cc->global_env, func->fname->data)) {
        setAdd(cc->main_names, func->fname->data);
    }
    mapAdd(cc->global_env,func->fname->data,func);
    return func;
}


/* `fname` is being declared or defined again with `rettype`/`params`:
 * a different signature from the earlier prototype or definition (the
 * standard library's prototypes in std.ktmh included) is an error at the
 * name. Reported without unwinding, so the body still parses. The
 * REPL redefines on purpose. */
static void parseCheckFunctionRedeclaration(Cctrl *cc, AstType *rettype,
                                            char *fname, int len,
                                            Vec *params, int has_var_args,
                                            int line, int col)
{
    if (cc->flags & CCTRL_REPL) return;
    Ast *prev = mapGetLen(cc->global_env, fname, len);
    if (!prev) {
        /* `_extern _LABEL T Name(...)` bindings live in asm_funcs */
        prev = mapGetLen(cc->asm_funcs, fname, len);
        if (prev && prev->kind != AST_ASM_FUNC_BIND) return;
    }
    if (!prev || (prev->kind != AST_FUN_PROTO && prev->kind != AST_FUNC &&
                  prev->kind != AST_ASM_FUNC_BIND)) {
        return;
    }
    AstType *type = astMakeFunctionType(rettype, params);
    if (astFnTypesMatch(type, prev->type) &&
        !has_var_args == !prev->has_var_args) {
        return;
    }

    Ast *now = astFunction(type, fname, len, params, NULL, NULL, has_var_args);
    AoStr *where = cctrlLookUpFile(cc, prev->file_id);
    char *msg = mprintf("conflicting types for `%.*s`: `%s` does not match "
                        "the earlier declaration `%s`%s%s%s",
                        len, fname, astFunctionToString(now),
                        astFunctionToString(prev),
                        where && prev->line > 0 ? " at " : "",
                        where && prev->line > 0 ? where->data : "",
                        where && prev->line > 0
                            ? mprintf(":%d", prev->line) : "");
    AoStr *bold = aoStrNew();
    aoStrCatColoured(bold, ESC_BOLD, msg);
    AoStr *buf = cctrlCreateErrorLineAt(cc, line, col, len, bold->data,
                                        CCTRL_ERROR, NULL);
    aoStrRelease(bold);
    CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, NULL);
    d->line = d->end_line = line;
    d->col = col;
    d->end_col = col + len;
    cctrlDiagPush(cc, d);
}

/* Do two default-argument expressions give the same value? Constant
 * expressions compare by value (`2`, `1+1` and `2(I64)` agree), anything
 * else by its spelling. */
static int parseDefaultArgsEqual(Ast *a, Ast *b) {
    int ok = 1;
    if (!astIsFloatType(a->type) && !astIsFloatType(b->type)) {
        s64 x = evalIntConstExprOrErr(a, &ok);
        s64 y = ok ? evalIntConstExprOrErr(b, &ok) : 0;
        if (ok) return x == y;
    } else {
        double x = evalFloatExprOrErr(a, &ok);
        double y = ok ? evalFloatExprOrErr(b, &ok) : 0;
        if (ok) return x == y;
    }
    char *sa = astLValueToString(a, 0);
    char *sb = astLValueToString(b, 0);
    return sa && sb && !strcmp(sa, sb);
}

/* Swap `from` for `to` where parseParams registered the parameter */
static void parseReplaceParamLocal(Cctrl *cc, AoStr *name, Ast *from, Ast *to) {
    if (name && mapGetLen(cc->localenv, name->data, name->len) == from) {
        mapAdd(cc->localenv, name->data, to);
    }
    if (cc->tmp_locals) {
        listForEach(cc->tmp_locals) {
            if (it->value == from) it->value = to;
        }
    }
}

/* `fname` was declared before with the same signature: like C++, a later
 * declaration or the definition keeps the earlier one's default
 * arguments, so `I64 F(I64 n=2);` then `I64 F(I64 n) {...}` still allows
 * `F()`. Giving the same parameter a default again is fine if it is the
 * same value; a different value is an error (calls before and after the
 * definition would otherwise disagree). */
static void parseInheritDefaultParams(Cctrl *cc, AstType *rettype,
                                      char *fname, int len, Vec *params,
                                      int has_var_args)
{
    if (cc->flags & CCTRL_REPL) return;
    Ast *prev = mapGetLen(cc->global_env, fname, len);
    if (!prev || (prev->kind != AST_FUN_PROTO && prev->kind != AST_FUNC)) {
        return;
    }
    Vec *prev_params = prev->params;
    if (!prev_params || !params || prev_params->size != params->size) return;
    /* A conflicting signature is reported by
     * parseCheckFunctionRedeclaration; inherit nothing from it */
    AstType *type = astMakeFunctionType(rettype, params);
    if (!astFnTypesMatch(type, prev->type) ||
        !has_var_args != !prev->has_var_args) {
        return;
    }

    for (u64 i = 0; i < params->size; ++i) {
        Ast *old = prev_params->entries[i];
        Ast *cur = params->entries[i];
        if (!old || !cur || old->kind != AST_DEFAULT_PARAM) continue;

        if (cur->kind == AST_DEFAULT_PARAM) {
            if (parseDefaultArgsEqual(cur->declinit, old->declinit)) continue;
            Ast *var = cur->declvar;
            AoStr *name = var->kind == AST_FUNPTR ? var->fname : var->lname;
            char *was = astLValueToString(old->declinit, 0);
            char *now = astLValueToString(cur->declinit, 0);
            AoStr *where = cctrlLookUpFile(cc, prev->file_id);
            char *msg = mprintf("default argument `%s` for parameter `%s` of "
                                "`%.*s` does not match the earlier "
                                "declaration's `%s`%s%s%s",
                                now ? now : "?", name ? name->data : "?",
                                len, fname, was ? was : "?",
                                where && prev->line > 0 ? " at " : "",
                                where && prev->line > 0 ? where->data : "",
                                where && prev->line > 0
                                    ? mprintf(":%d", prev->line) : "");
            int line = var->line > 0 ? var->line : cc->lineno;
            int col = var->line > 0 ? var->col : 0;
            int nlen = name ? (int)name->len : 1;
            AoStr *bold = aoStrNew();
            aoStrCatColoured(bold, ESC_BOLD, msg);
            AoStr *buf = cctrlCreateErrorLineAt(cc, line, col, nlen,
                                                bold->data, CCTRL_ERROR, NULL);
            aoStrRelease(bold);
            CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, NULL);
            d->line = d->end_line = line;
            d->col = col;
            d->end_col = col + nlen;
            cctrlDiagPush(cc, d);
            continue;
        }

        if (cur->kind == AST_FUNPTR) {
            /* parseParams keeps the AST_FUNPTR itself in the local scope */
            Ast *def = astFunctionDefaultParam(cur, old->declinit);
            cur->default_fn = def;
            params->entries[i] = def;
        } else if (cur->kind == AST_LVAR) {
            Ast *def = astFunctionDefaultParam(cur, old->declinit);
            parseReplaceParamLocal(cc, cur->lname, cur, def);
            params->entries[i] = def;
        }
    }
}

/* The name in a function pointer declarator `(*name)`, `(**name)`, ...
 * whose `(` was just read (the `*`s are still to come). */
static Lexeme *parseFnPtrDeclName(Cctrl *cc) {
    int stars = 0;
    while (tokenPunctIs(cctrlTokenPeek(cc), '*')) {
        cctrlTokenGet(cc);
        stars++;
    }
    Lexeme *name = cctrlTokenGet(cc);
    if (name) cctrlTokenRewind(cc);
    while (stars--) cctrlTokenRewind(cc);
    return name;
}

/* The @caller parameters params starts with (from `from` on, after a
 * method's self), as "fl" for (file, line): "" for none, NULL when one
 * comes later */
static char *parseCallerPrefixSig(Vec *params, int from) {
    char sig[16];
    int n = 0, i = from;
    for (; params && i < (int)params->size && n < 15; ++i) {
        Ast *p = params->entries[i];
        if (!p || !(p->flags & AST_FLAG_CALLER)) break;
        sig[n++] = p->flags & AST_FLAG_CALLER_FILE ? 'f' : p->flags & AST_FLAG_CALLER_LINE ? 'l' : 'u';
    }
    for (; params && i < (int)params->size; ++i) {
        Ast *p = params->entries[i];
        if (p && (p->flags & AST_FLAG_CALLER)) return NULL;
    }
    return mprintf("%.*s", n, sig);
}

/* Do two parameter lists take the same types (names and defaults aside)?
 * As overloads tell them apart: an enum is not its integer, bool not u8 */
static int parseSameParamTypes(Vec *a, Vec *b) {
    return !strcmp(astOverloadSymbol("", a, 0), astOverloadSymbol("", b, 0));
}

/* A function declared as `fname` (its symbol: `f` for a program's, the
 * mangled `_K..` for a module's or a method) with `params`. When a ktm
 * function of that name with other parameter types exists, this one is
 * an overload: every function of the name is then known by its overload
 * symbol (astOverloadSymbol), the first one renamed now (a call compiled
 * before keeps naming it by its old name, which still leads to it, see
 * asmNormaliseFunctionName). Returns the symbol to declare it under,
 * fname itself when it isn't an overload or is the same function again;
 * *set is the overload set a new overload joins. */
static char *parseOverloadDecl(Cctrl *cc, char *fname, int len, Vec *params,
                               int is_program_fn, int is_method, Vec **set)
{
    *set = NULL;
    if ((cc->flags & CCTRL_REPL) && !(cc->flags & CCTRL_LSP)) return fname;
    Ast *prev = mapGetLen(cc->global_env, fname, len);
    if (!prev || (prev->kind != AST_FUNC && prev->kind != AST_FUN_PROTO)) {
        return fname;
    }
    int is_ktm = is_program_fn || (len > 2 && fname[0] == '_' && fname[1] == 'K');
    int prev_ktm = (prev->flags & (AST_FLAG_PROGRAM | AST_FLAG_OVERLOAD)) ||
                   (prev->fname && !strncmp(prev->fname->data, "_K", 2));
    if (!is_ktm || !prev_ktm) return fname;

    int from = is_method ? 1 : 0;
    if (prev->overloads) {
        for (u64 i = 0; i < prev->overloads->size; ++i) {
            Ast *fn = prev->overloads->entries[i];
            if (parseSameParamTypes(fn->params, params)) return fn->fname->data;
        }
    } else if (parseSameParamTypes(prev->params, params)) {
        return fname;
    }

    /* @caller parameters: the same ones first in every overload (a call
     * fills them in before it picks one, parseArgv) */
    char *callers = parseCallerPrefixSig(params, from);
    char *prev_callers = parseCallerPrefixSig(prev->params, from);
    if (!callers || !prev_callers || strcmp(callers, prev_callers)) {
        cctrlRaiseException(cc, "`%s` has a @caller parameter, so its overloads must all "
                            "start with the same @caller parameters",
                            astModuleDemangle(fname, len));
    }

    if (!prev->overloads) {
        char *base = (prev->flags & AST_FLAG_PROGRAM)
                   ? astProgramSymbol(prev->fname->data, prev->fname->len)
                   : prev->fname->data;
        char *sym = astOverloadSymbol(base, prev->params, from);
        prev->fname = aoStrDupRaw(sym, strlen(sym));
        prev->flags = (prev->flags | AST_FLAG_OVERLOAD) & ~AST_FLAG_PROGRAM;
        prev->overloads = astVecNew();
        vecPush(prev->overloads, prev);
        mapAdd(cc->global_env, prev->fname->data, prev);
    }
    char *base = is_program_fn ? astProgramSymbol(fname, len) : mprintf("%.*s", len, fname);
    *set = prev->overloads;
    return astOverloadSymbol(base, params, from);
}

/* A new function joins the overload set parseOverloadDecl gave */
static void parseOverloadJoin(Ast *fn, Vec *set) {
    if (!fn || !set) return;
    fn->flags = (fn->flags | AST_FLAG_OVERLOAD) & ~AST_FLAG_PROGRAM;
    fn->overloads = set;
    for (u64 i = 0; i < set->size; ++i) {
        if (set->entries[i] == fn) return;
    }
    vecPush(set, fn);
}

/* `ret_fnptr` > 0: the function returns a function pointer, declared C's
 * way, `T (*Name(params))(fn_params)`, with `ret_fnptr` stars; rettype is
 * then the T those pointed-to functions return. */
static Ast *parseFunctionOrDefImpl(Cctrl *cc, AstType *rettype, char *fname,
                                   int len, int is_inline, int ret_fnptr);

Ast *parseFunctionOrDef(Cctrl *cc, AstType *rettype, char *fname, int len, int is_inline) {
    return parseFunctionOrDefImpl(cc, rettype, fname, len, is_inline, 0);
}

static Ast *parseFunctionOrDefImpl(Cctrl *cc, AstType *rettype, char *fname,
                                   int len, int is_inline, int ret_fnptr) {
    cctrlNoteHeaderName(cc, fname, len);
    /* Anchor: the name token was just consumed, so the cursor still
     * sits on its line - stamp the function Ast with the NAME's
     * position, not the body's `{` (which is where the node is
     * actually astNew'd). Jump-to-definition wants the name. */
    int name_line = cc->lineno;
    int name_col  = ast_col_hint; /* last consumed token = the name */
    u32 name_file = ast_file_hint;
    /* The hint can be on a `(` that was taken and given back; the name
     * itself is the token before the `(` when nothing else is (PeekBy
     * counts from one past the tail, so -2 is the one before it). */
    int name_tok_line = name_line, name_tok_col = name_col;
    Lexeme *name_tok = tokenRingBufferPeekBy(cc->token_buffer, -2);
    if (name_tok && name_tok->start == fname) {
        name_tok_line = name_tok->line;
        name_tok_col = name_tok->col;
    }
    /* A module's function `a.b.f`: named by its symbol (see
     * astModuleMangle), `a.b.f` kept as a second name for lookups */
    char *module_name = NULL;
    if (memchr(fname, '.', len)) {
        module_name = mprintf("%.*s", len, fname);
        fname = astModuleMangle(fname, len);
        len = strlen(fname);
    }
    /* `main` (ktm syntax v0.2) is the entry point `Main`: inside it is
     * named Main, so every backend, the JIT and the file-scope
     * initialiser driver treat it as the entry, and `main` is kept as
     * a second name for calls. */
    int is_lower_main = len == 4 && !memcmp(fname, "main", 4);
    if (len == 4 && !memcmp(fname, "Main", 4) && !(cc->flags & CCTRL_REPL)) {
        cctrlErrorAt(cc, name_tok_line, name_tok_col, 4,
                     "`Main` is the old entry point: write `main`");
    }
    if (is_lower_main) {
        Ast *other = mapGetLen(cc->global_env, "Main", 4);
        if (other && other->kind == AST_FUNC) {
            cctrlRaiseException(cc, "both `main` and `Main` are defined: "
                                    "a program has one entry point");
        }
        fname = "Main";
    }
    /* A main-program function (not a module's, not the entry point) gets
     * its own symbol (AST_FLAG_PROGRAM, astProgramSymbol) */
    int is_program_fn = cc->decl_from_main && !cc->cur_module &&
                        !module_name && !(len == 4 && !memcmp(fname, "Main", 4));
    int has_var_args = 0;
    cctrlTokenExpect(cc,'(');
    cc->localenv = cctrlCreateAstMap(cc->localenv);
    cc->tmp_locals = listNew();

    /* Reset the unique id counter otherwise we get ridiculous numbers */
    astResetLVarId();

    /* A method: `self`, a const pointer to the type, is its first
     * parameter */
    Ast *self = NULL;
    if (cc->method_self) {
        self = astLVar(cc->method_self, "self", 4);
        self->flags |= AST_FLAG_CONST;
        cc->method_self = NULL;
        mapAdd(cc->localenv, self->lname->data, self);
        listAppend(cc->tmp_locals, self);
    }
    Vec *params = parseParams(cc,')',&has_var_args,1);
    if (self) vecInsertAt(params, self, 0);
    /* the old `(...)`, with the implicit argc and argv */
    for (u64 i = 0; params && i < params->size; ++i) {
        Ast *p = params->entries[i];
        if (p && p->kind == AST_VAR_ARGS) {
            cctrlErrorAt(cc, name_tok_line, name_tok_col, len,
                "`...` needs a name: write `...args` (args.len and args[i] "
                "replace argc and argv)");
        }
    }
    if (ret_fnptr) {
        /* `)(fn_params)` after the parameters: what the returned function
         * pointer points to */
        int fn_var_args = 0;
        cctrlTokenExpect(cc,')');
        cctrlTokenExpect(cc,'(');
        AstType *fn_type = astMakeFunctionType(rettype,
                parseParams(cc,')',&fn_var_args,0));
        fn_type->has_var_args = fn_var_args;
        rettype = fn_type;
        for (int i = 1; i < ret_fnptr; ++i) {
            rettype = astMakePointerType(rettype);
        }
        /* the old C declarator, an error like the variable one (see
         * parseFunctionPointerType); the fn type in front is the way */
        cctrlErrorAt(cc, name_tok_line, name_tok_col, len,
            "a C function-pointer declarator: write `%s %.*s(...)`",
            astTypeToString(rettype), len, fname);
    }
    Lexeme *tok = cctrlTokenGet(cc);
    /* A module's declarations-only pass: the body comes in the full
     * pass, this declares the function so every file can call it */
    int skipped_body = 0;
    if (cc->decls_only && tokenPunctIs(tok, '{')) {
        int depth = 1;
        while (depth) {
            Lexeme *t = cctrlTokenGet(cc);
            if (!t) {
                cctrlRaiseException(cc, "Unexpected end of input in the body of `%.*s()`",
                                    len, fname);
            }
            if (tokenPunctIs(t, '{')) depth++;
            else if (tokenPunctIs(t, '}')) depth--;
        }
        skipped_body = 1;
    }
    Vec *overload_set = NULL;
    if ((tokenPunctIs(tok, '{') || tokenPunctIs(tok, ';')) && !is_lower_main) {
        char *declared = parseOverloadDecl(cc, fname, len, params,
                                           is_program_fn, self != NULL,
                                           &overload_set);
        if (declared != fname) {
            /* an overload: its symbol is complete (no program symbol) */
            fname = declared;
            len = strlen(fname);
            is_program_fn = 0;
        }
    }
    if (tokenPunctIs(tok, '{') || tokenPunctIs(tok, ';')) {
        parseCheckFunctionRedeclaration(cc, rettype, fname, len, params,
                                        has_var_args, name_tok_line,
                                        name_tok_col);
        parseInheritDefaultParams(cc, rettype, fname, len, params,
                                  has_var_args);
    }
    if (tokenPunctIs(tok, '{') && !skipped_body) {
        cc->tmp_overload_set = overload_set;
        Ast *fn = parseFunctionDef(cc,rettype,fname,len,params,
                                   has_var_args,is_inline,
                                   name_tok_line,name_tok_col);
        cc->tmp_overload_set = NULL;
        if (fn) {
            if (cc->decl_pub) fn->flags |= AST_FLAG_PUB;
            fn->line = name_line;
            fn->col = name_col;
            fn->file_id = name_file;
            if (is_program_fn) fn->flags |= AST_FLAG_PROGRAM;
            if (is_lower_main) mapAdd(cc->global_env, "main", fn);
            if (module_name) mapAdd(cc->global_env, module_name, fn);
        }
        return fn;
    } else if (tokenPunctIs(tok, ';') || skipped_body) {
        if (rettype->kind == AST_TYPE_AUTO) {
            cctrlRaiseException(cc,"auto cannot be used with a function prototype %.*s() at this time",
                    len,fname);
        }
        AstType *type = astMakeFunctionType(rettype, params);
        Ast *fn = astFunction(type,fname,len,params,NULL,NULL,has_var_args);
        fn->kind = AST_FUN_PROTO;
        if (is_program_fn) fn->flags |= AST_FLAG_PROGRAM;
        Ast *same = mapGet(cc->global_env, fn->fname->data);
        if (same && same->overloads && !overload_set) {
            /* a prototype of an overload declared before: keep that one */
            return same;
        }
        if (cc->decl_pub) fn->flags |= AST_FLAG_PUB;
        mapAdd(cc->global_env,fn->fname->data,fn);
        parseOverloadJoin(fn, overload_set);
        if (is_lower_main) mapAdd(cc->global_env, "main", fn);
        if (module_name) mapAdd(cc->global_env, module_name, fn);
        return fn;
    } else {
        /* Neither `{ ... }` (definition) nor `;` (prototype). Almost
         * always a missing brace - call it out specifically so the
         * user doesn't get a downstream "TK_KEYWORD X can only prefix
         * a class" from the rest of the function body being parsed as
         * top-level declarations. */
        if (!tok) {
            cctrlRaiseException(cc,
                "Expected `{` to open the body of `%.*s()` or `;` for a "
                "prototype, got end of input",
                len, fname);
        }
        cctrlRaiseException(cc,
            "Expected `{` to open the body of `%.*s()` or `;` for a "
            "prototype, got `%s`",
            len, fname, lexemeAsWritten(tok));
    }
}

Ast *parseAsmFunctionBinding(Cctrl *cc) {
    Lexeme *tok;
    AoStr *asm_fname, *c_fname;
    AstType *rettype;
    Ast *asm_func;
    int has_var_args = 0, is_inline = 0;

    tok = cctrlTokenGet(cc);
    if (tok->tk_type != TK_IDENT && tok->start[0] != '_') {
        cctrlRaiseException(cc,"ASM function binds must begin with '_' got: %.*s",
                tok->len,tok->start);
    }

    asm_fname = aoStrDupRaw(tok->start, tok->len);
    cctrlNoteHeaderName(cc, tok->start, tok->len);
    /* No existence check here, deliberately: a binding's label is
     * usually EXTERNAL - stdlib headers bind `_extern _MALLOC`-style
     * labels whose bodies live in libktm, and the lib itself binds
     * straight to C symbols (`_extern _opendir`). Only the linker can
     * tell a dangling label from an external one; a parse-time raise
     * broke every AOT compile. A typo'd in-file label still surfaces:
     * the asm block's own errors are reported on their lines. */
    Ast *asm_blk = mapGetLen(cc->asm_functions, asm_fname->data, asm_fname->len);

    rettype = parseDeclSpec(cc);

    if (rettype->kind == AST_TYPE_AUTO) {
        cctrlRaiseException(cc,"auto cannot be used with an assembly binding for function %s(), type cannot be automatically deduced",
                asm_fname->data);
    }

    tok = cctrlTokenGet(cc);
    if (tok->tk_type != TK_IDENT) {
        cctrlRaiseException(cc,"line %d: ASM function requires c function name");
    }
    c_fname = aoStrDupRaw(tok->start,tok->len);
    int name_line = tok->line, name_col = tok->col;

    /* `_extern _SYM T name;`: C data named by its symbol, as written in
     * the assembly (macOS's `___stderrp` as libc's `stderr`); in a module,
     * under the module's name */
    if (tokenPunctIs(cctrlTokenPeek(cc),';')) {
        cctrlTokenGet(cc);
        char *qual = cctrlDeclName(cc, c_fname->data, c_fname->len);
        char *gname = qual ? qual : c_fname->data;
        Ast *gvar = astGVar(rettype, gname, strlen(gname), 0);
        gvar->glabel = asm_fname;
        gvar->line = name_line;
        gvar->col = name_col;
        Ast *decl = astDecl(gvar, NULL);
        decl->flags |= AST_FLAG_EXTERN;
        gvar->flags |= AST_FLAG_EXTERN | AST_FLAG_EXTERN_C;
        mapAdd(cc->global_env, gvar->gname->data, gvar);
        if (qual && cc->decl_pub) setAdd(cc->pub_names, qual);
        return decl;
    }

    cc->localenv = cctrlCreateAstMap(cc->localenv);
    cctrlTokenExpect(cc,'(');

    Vec *params = parseParams(cc,')',&has_var_args,0);

    asm_func = astAsmFunctionBind(
            astMakeFunctionType(rettype, params),
            asm_fname,c_fname,params);
    /* Anchor to the C-name token so jump-to-def on e.g. MAlloc lands
     * on the name inside the prototype. */
    asm_func->line = name_line;
    asm_func->col = name_col;

    /* update the assembly block so it knows the ktm function name */
    if (asm_blk && asm_blk->fname == NULL) {
        asm_blk->fname = c_fname;
    }

    cc->localenv = NULL;
    /* Map a c function to an ASM function; in a module, under the
     * module's name (`c.stat`), pub or private like any declaration */
    char *qual = cctrlDeclName(cc, c_fname->data, c_fname->len);
    mapAdd(cc->asm_funcs, qual ? qual : c_fname->data, asm_func);
    if (qual && cc->decl_pub) setAdd(cc->pub_names, qual);
    cctrlTokenExpect(cc,';');
    if (is_inline) {
        asm_func->flags |= AST_FLAG_INLINE;
    }
    return asm_func;
}

/* `tok` ended a global declarator; on `,` hand the base type to the
 * next parseToplevelDef call so `T a, *b;` parses like a local list. */
/* Whether the file-scope declaration list being continued (`static T a,
 * b;`) is static; set with tmp_gvar_base_type */
static int parse_gvar_list_static = 0;
/* Same as parse_gvar_list_static, for `const T a = 1, b = 2;` */
static int parse_gvar_list_const = 0;

static void parseGlobalDeclListNext(Cctrl *cc, Lexeme *tok, AstType *base_type) {
    assertTokenIsTerminator(cc,tok,PUNCT_TERM_SEMI|PUNCT_TERM_COMMA);
    if (tokenPunctIs(tok,',')) {
        cc->tmp_gvar_base_type = base_type;
    }
}

/* The terminator was already consumed (by parseVariableInitialiser) */
static void parseGlobalDeclListNextPrev(Cctrl *cc, AstType *base_type) {
    cctrlTokenRewind(cc);
    parseGlobalDeclListNext(cc,cctrlTokenGet(cc),base_type);
}

/* A plain method `mtok` of cls, next to its generic methods with type
 * parameters of their own: they share a name with nothing */
static void parseCheckGenericMethodName(Cctrl *cc, AstType *cls, Lexeme *mtok) {
    for (u64 i = 0; cc->generic_methods && i < cc->generic_methods->size; ++i) {
        GenericDef *d = cc->generic_methods->entries[i];
        if (!d->nown || d->owner != cls || (int)d->method->len != mtok->len ||
            memcmp(d->method->data, mtok->start, mtok->len)) {
            continue;
        }
        cctrlRaiseExceptionAt(cc, mtok->line, mtok->col, mtok->len,
                              "`%s.%.*s` is already a method of %s: a method with type parameters "
                              "of its own can't share its name", cls->clsname->data,
                              mtok->len, mtok->start, cls->clsname->data);
    }
}

/* `T User.name(params)`, a method of the class or union `User`: a
 * definition or a prototype. `type_tok` is `User`, the `.` was taken. It
 * is the function `User.name` with a `User *self` first parameter, and
 * only the module (or the standard library) defining `User` can add it. */
static Ast *parseMethodDef(Cctrl *cc, AstType *rettype, Lexeme *type_tok,
                           int is_static) {
    char *tname = type_tok->start;
    int tlen = type_tok->len;
    AstType *cls = NULL;
    char *qual = cctrlQualifyName(cc, tname, tlen);
    /* a method of the generic struct instance being parsed */
    if (cc->inst && cc->inst->cls) cls = cc->inst->cls;
    if (!cls && qual) cls = mapGet(cc->clsdefs, qual);
    if (!cls) cls = mapGetLen(cc->clsdefs, tname, tlen);
    if (!cls) cls = mapGetLen(cc->uniondefs, tname, tlen);
    if (!cls) {
        /* `type P = Point;`: P is Point */
        TypeAlias *alias = qual ? mapGet(cc->type_aliases, qual) : NULL;
        if (!alias) alias = mapGetLen(cc->type_aliases, tname, tlen);
        if (alias) cls = alias->type;
    }
    if (!cls || cls->is_enum || cls->is_intrinsic ||
        (cls->kind != AST_TYPE_CLASS && cls->kind != AST_TYPE_UNION))
    {
        cctrlRewindUntilStrMatch(cc, tname, tlen, NULL);
        cctrlRaiseException(cc, "`%.*s` is not a struct: only a struct or "
                                "union can have methods", tlen, tname);
    }
    int here_std = !cc->cur_module && !cc->decl_from_main;
    AoStr *path = cctrlModulePath(cc, cc->cur_module);
    int top = astLastTopDot(cls->clsname->data, (int)cls->clsname->len);
    char *dot = top >= 0 ? cls->clsname->data + top : NULL;
    int mine = cls->in_std == here_std;
    if (dot) {
        int mod_len = dot - cls->clsname->data;
        mine = mine && path && (int)path->len == mod_len &&
               !memcmp(path->data, cls->clsname->data, mod_len);
    } else {
        /* a program's struct, or the prelude's (its names have no module
         * part), from the prelude itself */
        mine = mine && (!path || cctrlInPrelude(cc));
    }
    /* the prelude's String: its methods are module str's */
    if (!dot && astTypeIsString(cls) && path && path->len == 3 &&
        !memcmp(path->data, "str", 3)) {
        mine = 1;
    }
    /* (reported once, by a module's full pass) */
    if (!mine && !cc->decls_only) {
        cctrlRewindUntilStrMatch(cc, tname, tlen, NULL);
        cctrlRaiseException(cc, "methods of `%s` can only be defined %s",
                cls->clsname->data,
                cls->in_std ? "by the standard library"
                            : "in the module that defines it");
    }

    Lexeme *mtok = cctrlTokenGetRequired(cc);
    if (mtok->tk_type != TK_IDENT) {
        cctrlRaiseException(cc, "Expected a method name after `%.*s.`, got `%s`",
                            tlen, tname, lexemeAsWritten(mtok));
    }
    if (cls->fields && mapGetLen(cls->fields, mtok->start, mtok->len) &&
        !cc->decls_only) {
        cctrlRaiseException(cc, "`%s` has a field named `%.*s`: a method "
                            "cannot have the same name",
                            cls->clsname->data, mtok->len, mtok->start);
    }
    /* an instance of a method with type parameters of its own: its
     * parameters bound, it takes the instance's name, `map<f64>` */
    if (cc->inst && cc->inst->mname && tokenPunctIs(cctrlTokenPeek(cc), '<')) {
        parseGenericSkipParams(cc);
        mtok = lexemeCopy(mtok);
        mtok->start = cc->inst->mname->data;
        mtok->len = cc->inst->mname->len;
    } else if (!cc->inst) {
        parseCheckGenericMethodName(cc, cls, mtok);
    }
    if (!tokenPunctIs(cctrlTokenPeek(cc), '(')) {
        cctrlRaiseException(cc, "Expected `(` after the method name `%.*s.%.*s`",
                            tlen, tname, mtok->len, mtok->start);
    }
    char *name = mprintf("%s.%.*s", cls->clsname->data, mtok->len, mtok->start);
    if (cc->decl_pub && dot) setAdd(cc->pub_names, name);

    cc->method_self = astMakePointerType(cls);
    Ast *fn = parseFunctionOrDef(cc, rettype, name, strlen(name), 0);
    if (fn && is_static) fn->flags |= AST_FLAG_STATIC;
    if (fn) {
        fn->line = mtok->line;
        fn->col = mtok->col;
    }
    return fn;
}

/* M5: the files of a module can use each other's types in any order.
 * Its names pass (LEX_PASS_NAMES) records every top-level declaration's
 * tokens and declares every struct/union/class name, so pointers and
 * signatures can name a type of any file. Its type declarations (struct
 * and union bodies, enums, consts) are then parsed from their tokens,
 * over and over while more of them succeed, so a body can use a later
 * file's type by value, its enum or its const as an array size. What
 * never succeeds is left to the declarations pass, which reports it as
 * before; what did succeed it skips. */
Ast *parseToplevelDef(Cctrl *cc, int *is_global);

typedef struct ParseModDecl {
    u32 module_id;
    Vec *toks;      /* Lexeme *, the whole declaration */
    int is_type;    /* a struct/union body, an enum or a const */
    int replayed;   /* parseModuleTypes is done with it */
} ParseModDecl;

/* In a module, `type name = target...` where target is no type yet but
 * another alias of the module that never resolved (its types are parsed
 * in any order until no more succeed): they refer to each other. */
static void parseCheckAliasCycle(Cctrl *cc, Lexeme *name, Lexeme *target) {
    if (target && target->tk_type == TK_IDENT && target->len == name->len &&
        !memcmp(target->start, name->start, name->len) &&
        !cctrlGetKeyWord(cc, target->start, target->len)) {
        cctrlRaiseExceptionAt(cc, name->line, name->col, name->len,
                "type alias `%.*s` refers to itself", name->len, name->start);
    }
    if (!cc->cur_module || cc->replaying || !target || target->tk_type != TK_IDENT ||
        cctrlGetKeyWord(cc, target->start, target->len)) {
        return;
    }
    for (u64 i = 0; i < cc->mod_type_decls->size; ++i) {
        ParseModDecl *d = cc->mod_type_decls->entries[i];
        if (d->module_id != cc->cur_module || !d->is_type) continue;
        for (u64 j = 0; j + 2 < d->toks->size; ++j) {
            Lexeme *n = d->toks->entries[j + 1];
            if (parseStartsTypeAlias(d->toks->entries[j], n, d->toks->entries[j + 2]) &&
                n->len == target->len && !memcmp(n->start, target->start, n->len)) {
                cctrlRaiseExceptionAt(cc, name->line, name->col, name->len,
                        "type aliases `%.*s` and `%.*s` refer to each other (a cycle)",
                        name->len, name->start, n->len, n->start);
            }
        }
    }
}

/* `type Name = T;` at file scope, `type` already taken: Name is T from
 * here on (in a module: `mod.Name` outside it, if pub). No new type is
 * made, so a Name and a T convert to each other as T does to itself. */
static void parseTypeAlias(Cctrl *cc) {
    Lexeme *name = cctrlTokenGetRequired(cc);
    cctrlCheckPrefixHidden(cc, name);
    char *qual = cctrlDeclName(cc, name->start, name->len);
    char *key = qual ? qual : mprintf("%.*s", name->len, name->start);
    cctrlTokenExpect(cc, '=');
    parseCheckAliasCycle(cc, name, cctrlTokenPeek(cc));
    AstType *type = parseFullType(cc);
    cctrlTokenExpect(cc, ';');

    TypeAlias *had = mapGet(cc->type_aliases, key);
    if (had) {
        /* a module's declarations are parsed again in its full pass */
        if (cc->cur_module && (had->type == type ||
                (had->line == name->line && had->col == name->col &&
                 had->file_id == ast_file_hint))) {
            return;
        }
        cctrlRaiseExceptionAt(cc, name->line, name->col, name->len,
                "type `%s` has already been defined", key);
    }
    if (mapGet(cc->clsdefs, key) || mapGet(cc->uniondefs, key) ||
        mapGet(cc->symbol_table, key)) {
        cctrlRaiseExceptionAt(cc, name->line, name->col, name->len,
                "`%s` is already a type", key);
    }
    TypeAlias *alias = malloc(sizeof(TypeAlias));
    alias->type = type;
    alias->line = name->line;
    alias->col = name->col;
    alias->file_id = ast_file_hint;
    mapAdd(cc->type_aliases, key, alias);
    if (qual) {
        if (cc->decl_pub) setAdd(cc->pub_names, key);
    } else if (cc->decl_from_main && !cc->cur_module) {
        setAdd(cc->main_names, key);
    }
}

/* "file:line:col" of tok: the same declaration in another pass */
static char *parseModDeclKey(Lexeme *tok) {
    LexFile *f = tok->file;
    return mprintf("%s:%d:%d", f && f->filename ? f->filename->data : "",
                   tok->line, tok->col);
}

/* The rest of the top-level declaration that starts with `first`
 * (already taken), appended to `toks` when not NULL: up to a `;`, or the
 * `}` closing a body (and a `;` right after it), never past its file */
static void parseModDeclRest(Cctrl *cc, Lexeme *first, Vec *toks) {
    int depth = 0;
    Lexeme *t = first;
    while (1) {
        if (toks) vecPush(toks, t);
        if (tokenPunctIs(t, '(') || tokenPunctIs(t, '[') ||
            tokenPunctIs(t, '{')) {
            depth++;
        } else if (tokenPunctIs(t, ')') || tokenPunctIs(t, ']') ||
                   tokenPunctIs(t, '}')) {
            depth--;
            if (depth == 0 && tokenPunctIs(t, '}')) {
                Lexeme *peek = cctrlTokenPeek(cc);
                if (peek && tokenPunctIs(peek, ';') && peek->file == first->file) {
                    t = cctrlTokenGet(cc);
                    if (toks) vecPush(toks, t);
                }
                return;
            }
        } else if (depth <= 0 && tokenPunctIs(t, ';')) {
            return;
        }
        Lexeme *peek = cctrlTokenPeek(cc);
        if (!peek || peek->file != first->file ||
            peek->mod_pass != first->mod_pass) {
            return;
        }
        t = cctrlTokenGet(cc);
    }
}


/* ---- generics (v0.9.0) ----
 * A generic declaration is recorded as its tokens when it is met
 * (parseGenericDecl) and parsed again for each instance with its type
 * parameters bound (parseGenericReplay): a struct `Vec<i64>` when the
 * type is first written, its methods' prototypes with it and their
 * bodies at the end of the file (parseGenericFlush), so the instance
 * code is the same as hand-written code for that type. */

/* The k-th token after the one in hand, loading more from the lexer */
static Lexeme *parseLookahead(Cctrl *cc, int k) {
    TokenRingBuffer *r = cc->token_buffer;
    while (r->size < k && cc->lexer_ && !cc->replaying && !cc->generic_replay &&
           r->size < r->capacity - 8) {
        s64 before = r->size;
        cctrLoadNextTokens(cc, 1);
        if (r->size == before) break;
    }
    if (r->size < k) return NULL;
    /* cctrlTokenPeekBy(cc, n) is the (n+1)th token ahead */
    return k == 1 ? cctrlTokenPeek(cc) : cctrlTokenPeekBy(cc, k - 1);
}

typedef struct GenericHead {
    int kind;
    Lexeme *name;
    Lexeme *method;
    Vec *params;     /* AoStr * */
    int is_pub;
    Lexeme *owner;      /* `Owner.name<...>(`: a method of a plain struct */
    Vec *own;           /* `Vec<T>.map<U>(`: the method's own parameters */
} GenericHead;

/* Does the declaration starting at `first` (taken) declare a generic:
 * `Name<A, B>` followed by `(` (a function), `.` (a method of a generic
 * struct) or `{`/`:` after `struct` (a struct)? Its parameters must be
 * names that aren't types. */
static int parseGenericHead(Cctrl *cc, Lexeme *first, GenericHead *h) {
    enum { MAXLA = 48 };
    Lexeme *L[MAXLA];
    int n = 0;
    L[n++] = first;
    while (n < MAXLA) {
        Lexeme *t = parseLookahead(cc, n);
        if (!t) break;
        L[n++] = t;
    }
    int depth = 0;
    memset(h, 0, sizeof(*h));
    for (int i = 0; i + 3 < n; ++i) {
        Lexeme *t = L[i];
        if (tokenPunctIs(t, '(') || tokenPunctIs(t, '[')) { depth++; continue; }
        if (tokenPunctIs(t, ')') || tokenPunctIs(t, ']')) { depth--; continue; }
        if (depth == 0 && (tokenPunctIs(t, '{') || tokenPunctIs(t, ';') ||
                           tokenPunctIs(t, '='))) {
            return 0;
        }
        if (t->tk_type == TK_KEYWORD && t->i64 == KW_PUBLIC) h->is_pub = 1;
        if (depth != 0 || t->tk_type != TK_IDENT || !tokenPunctIs(L[i + 1], '<')) continue;
        Vec *params = vecNew(&vec_unsigned_long_type);
        int j = i + 2, ok = 0;
        while (j + 1 < n) {
            Lexeme *p = L[j];
            if (p->tk_type != TK_IDENT || cctrlTokIsKeyword(cc, p)) break;
            vecPush(params, aoStrDupRaw(p->start, p->len));
            if (tokenPunctIs(L[j + 1], ',')) { j += 2; continue; }
            if (tokenPunctIs(L[j + 1], '>')) { j += 1; ok = 1; }
            break;
        }
        if (!ok || j + 1 >= n) continue;
        Lexeme *after = L[j + 1];
        h->params = params;
        h->name = t;
        if (tokenPunctIs(after, '(')) {
            /* `S.f<V>(...)`: a method with type parameters of its own */
            if (i >= 2 && tokenPunctIs(L[i - 1], '.') && L[i - 2]->tk_type == TK_IDENT) {
                h->owner = L[i - 2];
            }
            h->kind = GEN_FUNC;
            return 1;
        }
        if (tokenPunctIs(after, '.') && j + 3 < n && L[j + 2]->tk_type == TK_IDENT &&
            tokenPunctIs(L[j + 3], '(')) {
            h->kind = GEN_METHOD;
            h->method = L[j + 2];
            return 1;
        }
        /* `Vec<T>.map<U>(`: a generic struct's method with parameters of its own */
        if (tokenPunctIs(after, '.') && j + 4 < n && L[j + 2]->tk_type == TK_IDENT &&
            tokenPunctIs(L[j + 3], '<')) {
            Vec *own = vecNew(&vec_unsigned_long_type);
            int k = j + 4, closed = 0;
            while (k + 1 < n) {
                Lexeme *p = L[k];
                if (p->tk_type != TK_IDENT || cctrlTokIsKeyword(cc, p)) break;
                vecPush(own, aoStrDupRaw(p->start, p->len));
                if (tokenPunctIs(L[k + 1], ',')) { k += 2; continue; }
                if (tokenPunctIs(L[k + 1], '>')) closed = 1;
                break;
            }
            if (closed && k + 2 < n && tokenPunctIs(L[k + 2], '(')) {
                h->kind = GEN_METHOD;
                h->method = L[j + 2];
                h->own = own;
                return 1;
            }
        }
        if ((tokenPunctIs(after, '{') || tokenPunctIs(after, ':')) && i > 0 &&
            L[i - 1]->tk_type == TK_KEYWORD && L[i - 1]->i64 == KW_CLASS) {
            h->kind = GEN_STRUCT;
            return 1;
        }
    }
    return 0;
}

static void parseGenericMethodInstance(Cctrl *cc, GenericDef *md, GenericInst *si);
static int parseTypeHasParam(AstType *t);

/* Generic method `d` (just declared, at `at`) next to the struct's
 * other methods and fields: a generic method with type parameters of
 * its own shares its name with nothing (v1: no overloads of them) */
static void parseGenericMethodClash(Cctrl *cc, GenericDef *d, Lexeme *at) {
    AstType *cls = d->owner;
    char *cname = d->name->data;
    for (u64 i = 0; cc->generic_methods && i < cc->generic_methods->size; ++i) {
        GenericDef *o = cc->generic_methods->entries[i];
        if (o->of != d->of || o->owner != d->owner || !aoStrCmp(o->method, d->method)) continue;
        if (!o->nown && !d->nown) continue;     /* a plain generic struct's methods: as before */
        cctrlRaiseExceptionAt(cc, at->line, at->col, at->len,
                              "`%s.%s` is already a method of %s: a method with type parameters "
                              "of its own can't share its name", cname, d->method->data, cname);
    }
    if (!cls || !d->nown) return;
    if (cls->fields && mapGet(cls->fields, d->method->data)) {
        cctrlRaiseExceptionAt(cc, at->line, at->col, at->len,
                              "`%s` has a field named `%s`: a method cannot have the same name",
                              cname, d->method->data);
    }
    if (mapGet(cc->global_env, mprintf("%s.%s", cname, d->method->data))) {
        cctrlRaiseExceptionAt(cc, at->line, at->col, at->len,
                              "`%s.%s` is already a method of %s: a method with type parameters "
                              "of its own can't share its name", cname, d->method->data, cname);
    }
}

/* A generic declaration starting at `first` (taken): recorded, not
 * parsed. 1 if it was one (all of it is then taken). */
static int parseGenericDecl(Cctrl *cc, Lexeme *first) {
    if (cc->inst || cc->tmp_gvar_base_type) return 0;
    GenericHead h;
    if (!parseGenericHead(cc, first, &h)) return 0;
    Vec *toks = vecNew(&vec_unsigned_long_type);
    parseModDeclRest(cc, first, toks);
    char *key = parseModDeclKey(first);
    if (!cc->generic_seen) cc->generic_seen = setNew(16, &set_cstring_type);
    if (setHas(cc->generic_seen, key)) return 1;   /* another pass of a module */
    setAdd(cc->generic_seen, key);

    GenericDef *d = calloc(1, sizeof(GenericDef));
    d->kind = h.kind;
    d->params = h.params;
    d->toks = toks;
    d->module_id = first->module_id;
    AoStr *mpath = cctrlModulePath(cc, first->module_id);
    d->module_path = mpath ? aoStrDup(mpath) : NULL;
    d->is_pub = h.is_pub;
    d->at = h.name;
    d->file_id = ast_file_hint;
    d->insts = vecNew(&vec_unsigned_long_type);
    if (!cc->generic_structs) cc->generic_structs = mapNew(16, &map_asttype_type);
    if (!cc->generic_funcs) cc->generic_funcs = mapNew(16, &map_asttype_type);
    if (!cc->generic_methods) cc->generic_methods = vecNew(&vec_unsigned_long_type);

    if (h.owner) {
        /* `S.f<V>(...)`: a plain struct's method with type parameters */
        char *oq = cctrlQualifyName(cc, h.owner->start, h.owner->len);
        AstType *cls = oq ? mapGet(cc->clsdefs, oq) : NULL;
        if (!cls) cls = mapGetLen(cc->clsdefs, h.owner->start, h.owner->len);
        if (!cls) {
            TypeAlias *alias = oq ? mapGet(cc->type_aliases, oq) : NULL;
            if (!alias) alias = mapGetLen(cc->type_aliases, h.owner->start, h.owner->len);
            if (alias) cls = alias->type;
        }
        if (!cls || cls->kind != AST_TYPE_CLASS || cls->is_enum || cls->is_intrinsic) {
            cctrlRaiseExceptionAt(cc, h.owner->line, h.owner->col, h.owner->len,
                                  "`%.*s` is not a struct: only a struct can have methods",
                                  h.owner->len, h.owner->start);
        }
        if (cls->generic && !cls->generic_args) {
            cctrlRaiseExceptionAt(cc, h.owner->line, h.owner->col, h.owner->len,
                                  "`%.*s` is generic: write its parameters, as in `%.*s<T>.%.*s<...>(...)`",
                                  h.owner->len, h.owner->start, h.owner->len, h.owner->start,
                                  h.name->len, h.name->start);
        }
        d->kind = GEN_METHOD;
        d->owner = cls;
        d->name = cls->clsname;
        d->method = aoStrDupRaw(h.name->start, h.name->len);
        d->nown = (int)d->params->size;
        parseGenericMethodClash(cc, d, h.name);
        vecPush(cc->generic_methods, d);
        return 1;
    }
    if (h.kind == GEN_METHOD) {
        AstType *tmpl = cctrlGetKeyWord(cc, h.name->start, h.name->len);
        if (!tmpl || !tmpl->generic || tmpl->generic_args) {
            cctrlRewindUntilStrMatch(cc, h.name->start, h.name->len, NULL);
            cctrlRaiseException(cc, "`%.*s<...>.%.*s`: `%.*s` is not a generic struct",
                                h.name->len, h.name->start, h.method->len, h.method->start,
                                h.name->len, h.name->start);
        }
        d->of = tmpl->generic;
        d->name = d->of->name;
        d->method = aoStrDupRaw(h.method->start, h.method->len);
        if (h.own) {
            /* bound after the struct's: `Vec<T>.map<U>` binds T, then U */
            for (u64 i = 0; i < h.own->size; ++i) {
                AoStr *o = h.own->entries[i];
                for (u64 k = 0; k < d->params->size; ++k) {
                    if (aoStrCmp(o, d->params->entries[k])) {
                        cctrlRaiseExceptionAt(cc, h.method->line, h.method->col, h.method->len,
                                              "`%s` is already a type parameter of `%s`: "
                                              "give the method's parameter another name", o->data,
                                              d->name->data);
                    }
                }
            }
            d->params = vecNew(&vec_unsigned_long_type);
            for (u64 i = 0; i < h.params->size; ++i) vecPush(d->params, h.params->entries[i]);
            for (u64 i = 0; i < h.own->size; ++i) vecPush(d->params, h.own->entries[i]);
            d->nown = (int)h.own->size;
        }
        parseGenericMethodClash(cc, d, h.method);
        vecPush(cc->generic_methods, d);
        return 1;
    }
    char *qual = cctrlDeclName(cc, h.name->start, h.name->len);
    d->name = qual ? aoStrDupRaw(qual, strlen(qual)) : aoStrDupRaw(h.name->start, h.name->len);
    if (qual && h.is_pub) setAdd(cc->pub_names, d->name->data);
    if (h.kind == GEN_STRUCT) {
        AstType *t = calloc(1, sizeof(AstType));
        t->kind = AST_TYPE_CLASS;
        t->clsname = d->name;
        t->generic = d;
        t->line = first->line;
        t->col = first->col;
        d->tmpl = t;
        mapAdd(cc->generic_structs, d->name->data, (void *)d);
        mapAdd(cc->clsdefs, d->name->data, t);
    } else {
        /* another generic function of the name: an overload, picked by
         * the arguments at each call (parseGenericOverloadCall) */
        GenericDef *head = mapGet(cc->generic_funcs, d->name->data);
        if (head) {
            GenericDef *tail = head;
            while (tail->next) tail = tail->next;
            tail->next = d;
            head->overloaded = d->overloaded = 1;
        } else {
            mapAdd(cc->generic_funcs, d->name->data, (void *)d);
        }
    }
    return 1;
}

/* `<` ... `>` after a declaration's name in an instance being parsed:
 * the parameters, already bound */
static void parseGenericSkipParams(Cctrl *cc) {
    cctrlTokenExpect(cc, '<');
    Lexeme *t;
    while ((t = cctrlTokenGet(cc)) != NULL && !tokenPunctIs(t, '>'));
}

/* How many errors have been reported so far. Not inlined: parseGenericReplay
 * calls it next to a setjmp, and gcc's -Wclobbered then flags its counter */
__attribute__((noinline)) static u64 parseErrorCount(Cctrl *cc) {
    u64 n = 0;
    for (u64 i = 0; cc->diagnostics && i < cc->diagnostics->size; ++i) {
        CctrlDiagnostic *d = cc->diagnostics->entries[i];
        if (d->severity == CCTRL_ERROR) n++;
    }
    return n;
}

/* "Vec<i64, u8 *>" for a definition named `name` and its arguments */
static AoStr *parseGenericInstName(AoStr *name, Vec *args) {
    AoStr *out = aoStrNew();
    aoStrCatAoStr(out, name);
    aoStrPutChar(out, '<');
    for (u64 i = 0; i < args->size; ++i) {
        if (i) aoStrCat(out, ", ");
        aoStrCat(out, astTypeToString(args->entries[i]));
    }
    aoStrPutChar(out, '>');
    return out;
}

/* Parse definition `d`'s tokens again as instance `in`: its parameters
 * bound to the arguments, the parser's state saved around it. An error
 * in it is reported where it is and where the instance was asked for. */
/* `d` was recorded by another parse whose lexer numbered the modules
 * differently (the LSP's bootstrap, which reads rt and the prelude and
 * what they import, before each document's parse): its tokens get this
 * lexer's number for its module */
static void parseGenericRenumber(Cctrl *cc, GenericDef *d) {
    if (!d->module_path || !cc->lexer_) return;
    AoStr *now_path = cctrlModulePath(cc, d->module_id);
    if (now_path && now_path->len == d->module_path->len &&
        !memcmp(now_path->data, d->module_path->data, now_path->len)) {
        return;
    }
    for (int i = 0; i < cc->lexer_->modules_len; ++i) {
        AoStr *p = cc->lexer_->modules[i]->path;
        if (p->len != d->module_path->len ||
            memcmp(p->data, d->module_path->data, p->len)) {
            continue;
        }
        u32 old_id = d->module_id, new_id = (u32)i + 1;
        for (u64 t = 0; t < d->toks->size; ++t) {
            Lexeme *tok = d->toks->entries[t];
            if (tok->module_id == old_id) tok->module_id = new_id;
        }
        d->module_id = new_id;
        return;
    }
}

static void parseGenericReplay(Cctrl *cc, GenericDef *d, GenericInst *in) {
    Vec *toks = d->toks;
    parseGenericRenumber(cc, d);
    u64 cap = roundUpToNextPowerOf2(toks->size + 1);
    if (cap < 16) cap = 16;
    Lexeme **ring = (Lexeme **)malloc(cap * sizeof(Lexeme *));
    for (u64 i = 0; i < cap; ++i) {
        ring[i] = i < toks->size ? (Lexeme *)toks->entries[i] : lexemeSentinal();
    }
    TokenRingBuffer *replay = calloc(1, sizeof(TokenRingBuffer));
    replay->entries = ring;
    replay->size = toks->size;
    replay->capacity = cap;

    Map *bindings = mapNew(8, &map_asttype_type);
    for (u64 i = 0; i < d->params->size && i < in->args->size; ++i) {
        AoStr *p = d->params->entries[i];
        mapAdd(bindings, p->data, in->args->entries[i]);
    }

    TokenRingBuffer *saved_ring = cc->token_buffer;
    jmp_buf *saved_recovery = cc->current_recovery;
    Map *saved_localenv = cc->localenv;
    List *saved_tmp_locals = cc->tmp_locals;
    Ast *saved_tmp_func = cc->tmp_func;
    AoStr *saved_tmp_fname = cc->tmp_fname;
    AstType *saved_rettype = cc->tmp_rettype;
    Vec *saved_params = cc->tmp_params;
    AoStr *saved_loop_begin = cc->tmp_loop_begin, *saved_loop_end = cc->tmp_loop_end;
    AstType *saved_gvar_base = cc->tmp_gvar_base_type;
    Ast *saved_gvar_decl = cc->tmp_gvar_decl;
    List *saved_lit_writes = cc->lit_writes;
    List *saved_elide_open = cc->elide_open, *saved_elide_done = cc->elide_done;
    Vec *saved_case_list = cc->tmp_case_list;
    Vec *saved_case_strings = cc->tmp_case_strings;
    Ast *saved_default_case = cc->tmp_default_case;
    AstType *saved_method_self = cc->method_self;
    Vec *saved_overload_set = cc->tmp_overload_set;
    List *saved_overload_refs = cc->overload_refs;
    Map *saved_bindings = cc->type_bindings;
    GenericInst *saved_inst = cc->inst;
    u32 saved_module = cc->cur_module;
    int saved_decls_only = cc->decls_only;
    int saved_pub = cc->decl_pub;
    int saved_from_main = cc->decl_from_main;
    int saved_replaying = cc->replaying, saved_greplay = cc->generic_replay;
    int saved_pending_gt = cc->pending_gt;
    s64 saved_lineno = cc->lineno;
    LexFile *saved_tok_file = cc->tok_file;
    Lexeme *saved_pending_token = cc->pending_token;
    int saved_line_hint = ast_line_hint, saved_col_hint = ast_col_hint;
    u32 saved_file_hint = ast_file_hint;
    /* a function parsed in the middle of another restarts the lvar ids */
    u32 saved_lvar_id = astGetLVarId();

    jmp_buf recovery;
    volatile int failed = 0;
    u64 errors_before = parseErrorCount(cc);
    cc->token_buffer = replay;
    cc->current_recovery = &recovery;
    cc->replaying = 0;
    cc->generic_replay = 1;
    cc->pending_gt = 0;
    cc->pending_token = NULL;
    cc->localenv = NULL;
    cc->tmp_locals = NULL;
    cc->tmp_func = NULL;
    cc->tmp_fname = NULL;
    cc->tmp_gvar_base_type = NULL;
    cc->tmp_gvar_decl = NULL;
    cc->tmp_overload_set = NULL;
    cc->overload_refs = NULL;
    cc->method_self = NULL;
    cc->type_bindings = bindings;
    in->outer = saved_inst;
    cc->inst = in;
    if (setjmp(recovery) != 0) {
        failed = 1;
    } else {
        int is_global = 0;
        Ast *ast;
        while ((ast = parseToplevelDef(cc, &is_global)) != NULL) {
            if (!is_global && ast->kind != AST_FUN_PROTO) {
                listAppend(cc->ast_list, ast);
            }
            is_global = 0;
            cc->localenv = NULL;
        }
    }
    cc->token_buffer = saved_ring;
    cc->current_recovery = saved_recovery;
    cc->localenv = saved_localenv;
    cc->tmp_locals = saved_tmp_locals;
    cc->tmp_func = saved_tmp_func;
    cc->tmp_fname = saved_tmp_fname;
    cc->tmp_rettype = saved_rettype;
    cc->tmp_params = saved_params;
    cc->tmp_loop_begin = saved_loop_begin;
    cc->tmp_loop_end = saved_loop_end;
    cc->tmp_gvar_base_type = saved_gvar_base;
    cc->tmp_gvar_decl = saved_gvar_decl;
    cc->lit_writes = saved_lit_writes;
    cc->elide_open = saved_elide_open;
    cc->elide_done = saved_elide_done;
    cc->tmp_case_list = saved_case_list;
    cc->tmp_case_strings = saved_case_strings;
    cc->tmp_default_case = saved_default_case;
    cc->method_self = saved_method_self;
    cc->tmp_overload_set = saved_overload_set;
    cc->overload_refs = saved_overload_refs;
    cc->type_bindings = saved_bindings;
    cc->inst = saved_inst;
    cc->cur_module = saved_module;
    cc->decls_only = saved_decls_only;
    cc->decl_pub = saved_pub;
    cc->decl_from_main = saved_from_main;
    cc->replaying = saved_replaying;
    cc->generic_replay = saved_greplay;
    cc->pending_gt = saved_pending_gt;
    cc->lineno = saved_lineno;
    cc->tok_file = saved_tok_file;
    cc->pending_token = saved_pending_token;
    ast_line_hint = saved_line_hint;
    ast_col_hint = saved_col_hint;
    ast_file_hint = saved_file_hint;
    astSetLVarId(saved_lvar_id);
    if ((failed || parseErrorCount(cc) > errors_before) && in->at) {
        /* where the use is: maybe another module's file than the one
         * being read when the body is made (the main file's end) */
        LexFile *here = cc->tok_file;
        if (in->at_file) cc->tok_file = in->at_file;
        cctrlInfoAt(cc, in->at->line, in->at->col, in->at->len,
                    "in `%s`, made here", in->name->data);
        cc->tok_file = here;
    }
    if (failed) longjmp(*saved_recovery, 1);
}

/* Method `md` of struct instance `si`: its prototype now, its body with
 * the others at the end of the file */
static void parseGenericMethodInstance(Cctrl *cc, GenericDef *md, GenericInst *si) {
    GenericInst *in = calloc(1, sizeof(GenericInst));
    in->def = md;
    in->args = si->args;
    in->name = si->name;
    in->cls = si->cls;
    in->at = si->at;
    in->at_file = si->at_file;
    in->proto_only = 1;
    parseGenericReplay(cc, md, in);
    GenericInst *body = calloc(1, sizeof(GenericInst));
    *body = *in;
    body->proto_only = 0;
    if (!cc->generic_pending) cc->generic_pending = vecNew(&vec_unsigned_long_type);
    vecPush(cc->generic_pending, body);
}

/* Method `name` of generic struct instance `cls`, made now if its
 * generic struct has one of that name and it wasn't made yet: an
 * instance's methods are made when first called, so one that only works
 * for some types (a sort using `<`) doesn't stop the others. The
 * function, or NULL. */
Ast *parseGenericMethod(Cctrl *cc, AstType *cls, char *name, int len) {
    if (!cls || !cls->generic || !cls->generic_args || !cc->generic_methods) return NULL;
    GenericDef *d = cls->generic;
    GenericInst *si = NULL;
    for (u64 i = 0; i < d->insts->size && !si; ++i) {
        GenericInst *in = d->insts->entries[i];
        /* a struct field's type is a copy of the instance type */
        if (in->cls == cls || (in->cls && aoStrCmp(in->cls->clsname, cls->clsname))) si = in;
    }
    if (!si) return NULL;
    for (u64 i = 0; i < cc->generic_methods->size; ++i) {
        GenericDef *md = cc->generic_methods->entries[i];
        if (md->of != d || md->nown || (int)md->method->len != len ||
            memcmp(md->method->data, name, len)) {
            continue;
        }
        char *key = mprintf("%s.%.*s", cls->clsname->data, len, name);
        Ast *fn = mapGet(cc->global_env, key);
        if (fn) return fn;
        parseGenericMethodInstance(cc, md, si);
        return mapGet(cc->global_env, key);
    }
    return NULL;
}

/* `Vec<args>` of generic struct template `tmpl`: the instance type, made
 * the first time (with its methods) */
AstType *parseGenericStructInstance(Cctrl *cc, AstType *tmpl, Vec *args, Lexeme *at) {
    GenericDef *d = tmpl->generic;
    if (args->size != d->params->size) {
        cctrlRaiseException(cc, "`%s` takes %d type argument%s, got %d", d->name->data,
                            (int)d->params->size, d->params->size == 1 ? "" : "s",
                            (int)args->size);
    }
    AoStr *name = parseGenericInstName(d->name, args);
    AstType *cls = mapGet(cc->clsdefs, name->data);
    if (cls) return cls;
    /* `Vec<T>` in a signature being matched (parseGenericInfer): only
     * what it is made of, never laid out */
    for (u64 i = 0; i < args->size; ++i) {
        if (parseTypeHasParam(args->entries[i])) {
            AstType *pat = calloc(1, sizeof(AstType));
            pat->kind = AST_TYPE_CLASS;
            pat->clsname = name;
            pat->generic = d;
            pat->generic_args = args;
            pat->size = 8;
            pat->alignment = 8;
            return pat;
        }
    }
    GenericInst *in = calloc(1, sizeof(GenericInst));
    in->def = d;
    in->args = args;
    in->name = name;
    in->at = at;
    in->at_file = cctrlCurFile(cc);
    parseGenericReplay(cc, d, in);
    cls = mapGet(cc->clsdefs, name->data);
    if (!cls) {
        cctrlRaiseException(cc, "could not make `%s`", name->data);
    }
    cls->generic = d;
    cls->generic_args = args;
    in->cls = cls;
    vecPush(d->insts, in);
    /* its methods are made when first called (parseGenericMethod) */
    return cls;
}

/* `<A, B>` (the `<` next): the type arguments; a `>>` closes this list
 * and the one around it */
Vec *parseGenericArgs(Cctrl *cc) {
    cctrlTokenExpect(cc, '<');
    Vec *args = vecNew(&vec_unsigned_long_type);
    while (1) {
        AstType *t = parseFullType(cc);
        vecPush(args, t);
        if (cc->pending_gt) {
            cc->pending_gt = 0;
            return args;
        }
        Lexeme *tok = cctrlTokenGetRequired(cc);
        if (tokenPunctIs(tok, ',')) continue;
        if (tokenPunctIs(tok, '>')) return args;
        if (tok->tk_type == TK_PUNCT && tok->i64 == TK_SHR) {
            cc->pending_gt = 1;
            return args;
        }
        cctrlRaiseException(cc, "Expected `,` or `>` in a type-argument list, got `%s`",
                            lexemeAsWritten(tok));
    }
}


/* Does type `t` hold a type-parameter stand-in (inference patterns)? */
static int parseTypeHasParam(AstType *t) {
    if (!t) return 0;
    if (t->is_type_param) return 1;
    switch (t->kind) {
        case AST_TYPE_POINTER: case AST_TYPE_ARRAY: return parseTypeHasParam(t->ptr);
        case AST_TYPE_FUNC: {
            if (parseTypeHasParam(t->rettype)) return 1;
            for (u64 i = 0; t->params && i < t->params->size; ++i) {
                Ast *p = t->params->entries[i];
                if (p && parseTypeHasParam(p->type)) return 1;
            }
            return 0;
        }
        case AST_TYPE_CLASS:
            if (t->is_slice) return parseTypeHasParam(t->elem);
            for (u64 i = 0; t->generic_args && i < t->generic_args->size; ++i) {
                if (parseTypeHasParam(t->generic_args->entries[i])) return 1;
            }
            return 0;
        default:
            return 0;
    }
}

/* A generic function `name` (as written, `geo.area` or `area` in its
 * module), or NULL */
GenericDef *parseFindGenericFunc(Cctrl *cc, char *name, int len) {
    if (!cc->generic_funcs) return NULL;
    char *qual = cctrlQualifyName(cc, name, len);
    GenericDef *d = qual ? mapGet(cc->generic_funcs, qual) : NULL;
    if (!d) {
        d = mapGetLen(cc->generic_funcs, name, len);
        /* `mod.name` from outside its module: it must be pub */
        if (d && memchr(name, '.', len)) cctrlCheckModuleAccess(cc, name, len);
    }
    return d;
}

static int parseTypesSame(AstType *a, AstType *b);

/* Function `d` with type arguments `args`: its prototype (made the first
 * time, its body at the end of the file) */
Ast *parseGenericFuncInstance(Cctrl *cc, GenericDef *d, Vec *args, Lexeme *at) {
    if (args->size != d->params->size) {
        cctrlRaiseException(cc, "`%s` takes %d type argument%s, got %d", d->name->data,
                            (int)d->params->size, d->params->size == 1 ? "" : "s",
                            (int)args->size);
    }
    /* the unqualified name: a module's function is qualified when it is
     * declared, as any other */
    char *base = d->name->data;
    char *dot = strrchr(base, '.');
    AoStr *short_name = aoStrDup(dot && d->module_id ? aoStrDupRaw(dot + 1, strlen(dot + 1))
                                                     : d->name);
    AoStr *name = parseGenericInstName(short_name, args);
    AoStr *full = parseGenericInstName(d->name, args);
    Ast *fn = NULL, *before = NULL;
    u64 before_n = 0;
    if (d->overloaded) {
        /* the instances of the overloads share a name: each definition
         * keeps its own */
        for (u64 i = 0; d->made && i < d->made->size; ++i) {
            GenericInst *m = d->made->entries[i];
            int same = m->args->size == args->size;
            for (u64 j = 0; same && j < args->size; ++j) {
                same = parseTypesSame(m->args->entries[j], args->entries[j]);
            }
            if (same && m->fn) return m->fn;
        }
        before = mapGet(cc->global_env, full->data);
        if (!before) before = mapGet(cc->global_env, name->data);
        before_n = before && before->overloads ? before->overloads->size : 0;
    } else {
        fn = mapGet(cc->global_env, full->data);
        if (!fn) fn = mapGet(cc->global_env, name->data);
        if (fn) return fn;
    }
    GenericInst *in = calloc(1, sizeof(GenericInst));
    in->def = d;
    in->args = args;
    in->name = name;
    in->at = at;
    in->at_file = cctrlCurFile(cc);
    in->proto_only = 1;
    parseGenericReplay(cc, d, in);
    fn = mapGet(cc->global_env, full->data);
    if (!fn) fn = mapGet(cc->global_env, name->data);
    if (d->overloaded && before && fn) {
        /* this definition's function joined the name's overloads last */
        if (fn->overloads && fn->overloads->size > before_n) {
            fn = fn->overloads->entries[fn->overloads->size - 1];
        }
    }
    if (!fn) cctrlRaiseException(cc, "could not make `%s`", full->data);
    in->fn = fn;
    if (d->overloaded) {
        if (!d->made) d->made = vecNew(&vec_unsigned_long_type);
        vecPush(d->made, in);
    }
    int pattern = 0;
    for (u64 i = 0; i < args->size; ++i) pattern |= parseTypeHasParam(args->entries[i]);
    if (!pattern) {
        GenericInst *body = calloc(1, sizeof(GenericInst));
        *body = *in;
        body->proto_only = 0;
        if (!cc->generic_pending) cc->generic_pending = vecNew(&vec_unsigned_long_type);
        vecPush(cc->generic_pending, body);
        vecPush(d->insts, in);
    }
    return fn;
}

static int parseTypesSame(AstType *a, AstType *b) {
    return a == b || !strcmp(astTypeToString(a), astTypeToString(b));
}

/* Match pattern `pat` (with stand-ins) against argument type `arg`,
 * binding the stand-ins in `b`. 0 on a conflict. */
static int parseGenericUnify(AstType *pat, AstType *arg, Map *b, char **why) {
    if (!pat || !arg) return 1;
    if (pat->is_type_param) {
        AstType *cur = mapGet(b, pat->clsname->data);
        /* an array argument is its pointer, as a call passes it */
        if (arg->kind == AST_TYPE_ARRAY) arg = astMakePointerType(arg->ptr);
        if (!cur) {
            mapAdd(b, pat->clsname->data, arg);
            return 1;
        }
        if (parseTypesSame(cur, arg)) return 1;
        *why = mprintf("`%s` is `%s` from one argument and `%s` from another",
                       pat->clsname->data, astTypeToString(cur), astTypeToString(arg));
        return 0;
    }
    switch (pat->kind) {
        case AST_TYPE_POINTER:
            if (arg->kind == AST_TYPE_POINTER || arg->kind == AST_TYPE_ARRAY) {
                return parseGenericUnify(pat->ptr, arg->ptr, b, why);
            }
            return 1;
        case AST_TYPE_CLASS:
            if (pat->is_slice) {
                if (astTypeIsSlice(arg)) return parseGenericUnify(pat->elem, arg->elem, b, why);
                if (arg->kind == AST_TYPE_ARRAY) return parseGenericUnify(pat->elem, arg->ptr, b, why);
                return 1;
            }
            if (pat->generic_args && arg->generic == pat->generic && arg->generic_args &&
                arg->generic_args->size == pat->generic_args->size) {
                for (u64 i = 0; i < pat->generic_args->size; ++i) {
                    if (!parseGenericUnify(pat->generic_args->entries[i],
                                           arg->generic_args->entries[i], b, why)) return 0;
                }
            }
            return 1;
        case AST_TYPE_FUNC:
            if (arg->kind != AST_TYPE_FUNC) return 1;
            if (!parseGenericUnify(pat->rettype, arg->rettype, b, why)) return 0;
            for (u64 i = 0; pat->params && arg->params && i < pat->params->size &&
                            i < arg->params->size; ++i) {
                Ast *pp = pat->params->entries[i], *ap = arg->params->entries[i];
                if (pp && ap && !parseGenericUnify(pp->type, ap->type, b, why)) return 0;
            }
            return 1;
        default:
            return 1;
    }
}

/* Parameters `params` from `start` against the arguments argv (parsed
 * without a target), binding the stand-ins in `b`; 0 on a conflict. argv
 * has no arguments for @caller parameters: they are filled in once the
 * instance is known (parseGenericCallWith) */
static int parseGenericUnifyArgs(Vec *params, u64 start, Vec *argv, Map *b, char **why) {
    for (u64 i = start, j = 0; params && i < params->size && argv && j < argv->size; ++i) {
        Ast *p = params->entries[i];
        if (p && (p->flags & AST_FLAG_CALLER)) continue;
        Ast *arg = argv->entries[j++];
        if (!p || !arg || arg->kind == AST_PLACEHOLDER || !arg->type) continue;
        if (p->kind == AST_LVAR && (p->flags & AST_FLAG_VARARGS)) break;
        AstType *pt = p->kind == AST_DEFAULT_PARAM ? p->declvar->type : p->type;
        if (!parseGenericUnify(pt, arg->type, b, why)) return 0;
    }
    return 1;
}

/* The type arguments of a call to generic function `d` with `argv`
 * (parsed without a target), from the argument types; NULL with *why
 * when they can't all be found */
Vec *parseGenericInfer(Cctrl *cc, GenericDef *d, Vec *argv, char **why) {
    /* the signature with stand-ins for the parameters */
    Vec *stand = vecNew(&vec_unsigned_long_type);
    for (u64 i = 0; i < d->params->size; ++i) {
        AstType *t = calloc(1, sizeof(AstType));
        t->kind = AST_TYPE_CLASS;
        t->clsname = d->params->entries[i];
        t->is_type_param = 1;
        t->size = 8;
        t->alignment = 8;
        vecPush(stand, t);
    }
    Ast *pattern = parseGenericFuncInstance(cc, d, stand, NULL);
    Vec *params = pattern->params;
    /* an overload given more arguments than it takes: not this one */
    int takes = 0, varargs = 0;
    for (u64 i = 0; params && i < params->size; ++i) {
        Ast *p = params->entries[i];
        if (!p) continue;
        if ((p->kind == AST_LVAR && (p->flags & AST_FLAG_VARARGS)) || p->kind == AST_VAR_ARGS) varargs = 1;
        else if (!(p->flags & AST_FLAG_CALLER)) takes++;
    }
    if (d->overloaded && !varargs && argv && (int)argv->size > takes) {
        *why = mprintf("it takes %d argument%s, not %d", takes, takes == 1 ? "" : "s", (int)argv->size);
        return NULL;
    }
    Map *b = mapNew(8, &map_asttype_type);
    if (!parseGenericUnifyArgs(params, 0, argv, b, why)) return NULL;
    Vec *args = vecNew(&vec_unsigned_long_type);
    for (u64 i = 0; i < d->params->size; ++i) {
        AoStr *pname = d->params->entries[i];
        AstType *t = mapGet(b, pname->data);
        if (!t) {
            *why = mprintf("nothing says what `%s` is", pname->data);
            return NULL;
        }
        vecPush(args, t);
    }
    return args;
}

/* The generic method with type parameters of its own named `name` of
 * struct `cls` or a struct it derives from, with *owner the struct that
 * has it; NULL if there is none */
GenericDef *parseFindGenericOwnMethod(Cctrl *cc, AstType *cls, char *name, int len,
                                      AstType **owner) {
    for (AstType *t = cls; t && cc->generic_methods; t = t->base_cls) {
        for (u64 i = 0; i < cc->generic_methods->size; ++i) {
            GenericDef *d = cc->generic_methods->entries[i];
            if (!d->nown || (int)d->method->len != len || memcmp(d->method->data, name, len)) {
                continue;
            }
            int mine = d->of ? (t->generic == d->of && t->generic_args != NULL)
                             : (d->owner == t || (t->clsname && d->owner->clsname &&
                                                  aoStrCmp(t->clsname, d->owner->clsname)));
            if (mine) {
                *owner = t;
                return d;
            }
        }
    }
    return NULL;
}

/* Method `d` of struct `cls` (an instance of d's generic struct, or d's
 * plain struct) with its own type arguments `own`: its prototype (made
 * the first time, its body at the end of the file) */
Ast *parseGenericOwnMethodInstance(Cctrl *cc, GenericDef *d, AstType *cls, Vec *own, Lexeme *at) {
    if ((int)own->size != d->nown) {
        cctrlRaiseException(cc, "`%s.%s` takes %d type argument%s, got %d", cls->clsname->data,
                            d->method->data, d->nown, d->nown == 1 ? "" : "s", (int)own->size);
    }
    Vec *args = vecNew(&vec_unsigned_long_type);
    for (u64 i = 0; d->of && cls->generic_args && i < cls->generic_args->size; ++i) {
        vecPush(args, cls->generic_args->entries[i]);
    }
    for (u64 i = 0; i < own->size; ++i) vecPush(args, own->entries[i]);
    AoStr *mname = parseGenericInstName(d->method, own);
    char *key = mprintf("%s.%s", cls->clsname->data, mname->data);
    Ast *fn = mapGet(cc->global_env, key);
    if (!fn) fn = mapGet(cc->global_env, astModuleMangle(key, strlen(key)));
    if (fn) return fn;
    GenericInst *in = calloc(1, sizeof(GenericInst));
    in->def = d;
    in->args = args;
    in->name = parseGenericInstName(cls->clsname, own);
    in->mname = mname;
    in->cls = cls;
    in->at = at;
    in->proto_only = 1;
    parseGenericReplay(cc, d, in);
    fn = mapGet(cc->global_env, key);
    if (!fn) fn = mapGet(cc->global_env, astModuleMangle(key, strlen(key)));
    if (!fn) cctrlRaiseException(cc, "could not make `%s`", key);
    in->fn = fn;
    int pattern = 0;
    for (u64 i = 0; i < own->size; ++i) pattern |= parseTypeHasParam(own->entries[i]);
    if (!pattern) {
        GenericInst *body = calloc(1, sizeof(GenericInst));
        *body = *in;
        body->proto_only = 0;
        if (!cc->generic_pending) cc->generic_pending = vecNew(&vec_unsigned_long_type);
        vecPush(cc->generic_pending, body);
    }
    return fn;
}

/* The own type arguments of a call of method `d` on a `cls` with `argv`
 * (parsed without a target, self not in it), from the argument types;
 * NULL with *why when they can't all be found */
Vec *parseGenericOwnMethodInfer(Cctrl *cc, GenericDef *d, AstType *cls, Vec *argv, char **why) {
    Vec *stand = vecNew(&vec_unsigned_long_type);
    u64 first = d->params->size - d->nown;
    for (u64 i = first; i < d->params->size; ++i) {
        AstType *t = calloc(1, sizeof(AstType));
        t->kind = AST_TYPE_CLASS;
        t->clsname = d->params->entries[i];
        t->is_type_param = 1;
        t->size = 8;
        t->alignment = 8;
        vecPush(stand, t);
    }
    Ast *pattern = parseGenericOwnMethodInstance(cc, d, cls, stand, NULL);
    Map *b = mapNew(8, &map_asttype_type);
    /* params[0] is self: the receiver, already known */
    if (!parseGenericUnifyArgs(pattern->params, 1, argv, b, why)) return NULL;
    Vec *args = vecNew(&vec_unsigned_long_type);
    for (u64 i = first; i < d->params->size; ++i) {
        AoStr *pname = d->params->entries[i];
        AstType *t = mapGet(b, pname->data);
        if (!t) {
            *why = mprintf("nothing says what `%s` is", pname->data);
            return NULL;
        }
        vecPush(args, t);
    }
    return args;
}

/* Parse instance `in`; an error in it ends just that instance. The setjmp
 * has a frame of its own, so the caller's loop counter isn't live across
 * it (a longjmp could otherwise bring back a stale one) */
static void parseGenericReplayGuarded(Cctrl *cc, GenericInst *in) {
    jmp_buf recovery;
    jmp_buf *prev = cc->current_recovery;
    cc->current_recovery = &recovery;
    if (setjmp(recovery) == 0) {
        parseGenericReplay(cc, in->def, in);
    }
    cc->current_recovery = prev;
}

/* The method and function bodies of every instance, after the file; an
 * instance made by one of them is added to the end and parsed too */
/* ---- @json / @yaml: generated encoders and decoders (v0.14.0) ----
 * A @json struct gets, as if written after it in its own module:
 *
 *   void S.toJson(json.Writer *__w);     // its fields, keys pre-escaped
 *   void S.fromJson(json.Reader *__r);   // keys matched by length, then bytes
 *   void S.freeDecoded();                // what fromJson allocated
 *
 * The text is made here from the struct's type, lexed into tokens and
 * replayed like a generic method: the prototypes now, the bodies with
 * the generic instances' at the end of the file (parseGenericFlush), so
 * a struct's methods can call those of a struct declared after it. */

/* The format being generated: "json" or "yaml", and its method names'
 * part ("Json", "Yaml") */
static const char *jg_mod = "json";
static const char *jg_Fmt = "Json";

enum {
    JG_NONE, JG_INT, JG_U64, JG_BOOL, JG_FLOAT, JG_STRING, JG_BYTES, JG_CSTR,
    JG_ENUM, JG_STRUCT, JG_PTR_STRUCT, JG_VEC, JG_SLICE, JG_ARRAY,
    JG_PTR_VAL      /* a pointer to a number, bool, enum, String...: it or null */
};

static int parseJsonIsVec(AstType *t) {
    return t->kind == AST_TYPE_CLASS && t->clsname && t->generic_args &&
           !strncmp(t->clsname->data, "vec.Vec<", 8);
}

static int parseJsonKind(AstType *t) {
    if (!t) return JG_NONE;
    if (astTypeIsString(t)) return JG_STRING;
    if (astTypeIsSlice(t)) {
        AstType *e = t->elem;
        if (e && e->kind == AST_TYPE_CHAR && e->size == 1 && !e->is_bool) return JG_BYTES;
        return JG_SLICE;
    }
    if (t->is_enum) return JG_ENUM;
    if (t->is_bool) return JG_BOOL;
    switch (t->kind) {
        case AST_TYPE_INT:
        case AST_TYPE_CHAR:
            return t->size == 8 && !t->issigned ? JG_U64 : JG_INT;
        case AST_TYPE_FLOAT:
            return JG_FLOAT;
        case AST_TYPE_POINTER: {
            AstType *p = t->ptr;
            if (p && p->kind == AST_TYPE_CHAR && p->size == 1 && !p->is_bool) return JG_CSTR;
            if (p && (astTypeIsString(p) || astTypeIsSlice(p) || p->is_enum || p->is_intrinsic ||
                      parseJsonIsVec(p))) return JG_PTR_VAL;
            if (p && p->kind == AST_TYPE_CLASS) return JG_PTR_STRUCT;
            if (p && (p->kind == AST_TYPE_INT || p->kind == AST_TYPE_CHAR ||
                      p->kind == AST_TYPE_FLOAT)) return JG_PTR_VAL;
            return JG_NONE;
        }
        case AST_TYPE_ARRAY:
            return JG_ARRAY;
        case AST_TYPE_CLASS:
            if (t->is_intrinsic) return JG_INT;
            if (parseJsonIsVec(t)) return JG_VEC;
            return JG_STRUCT;
        default:
            return JG_NONE;
    }
}

static AstType *parseJsonElem(AstType *t, int kind) {
    switch (kind) {
        case JG_VEC:   return t->generic_args->entries[0];
        case JG_SLICE: return t->elem;
        case JG_ARRAY: return t->ptr;
        default:       return NULL;
    }
}

/* The struct `t` (or what a pointer field points to) has @json */
static AstType *parseJsonStructOf(AstType *t, int kind) {
    return kind == JG_PTR_STRUCT ? t->ptr : t;
}

/* Why `t` can't be JSON, NULL if it can */
static char *parseJsonUnsupported(AstType *t) {
    int k = parseJsonKind(t);
    if (k == JG_NONE) {
        return mprintf("a %s can't be %s", astTypeToString(t), jg_mod[0] == 'j' ? "JSON" : "YAML");
    }
    if (k == JG_STRUCT || k == JG_PTR_STRUCT) {
        AstType *s = parseJsonStructOf(t, k);
        if (s->kind == AST_TYPE_UNION) return mprintf("a union can't be %s", jg_mod[0] == 'j' ? "JSON" : "YAML");
        /* a pointer to a struct only declared so far (`struct Note;`):
         * its @json comes with its body, checked before the bodies are
         * made (parseJsonCheckDeferred) */
        if (k == JG_PTR_STRUCT && !s->fields) return "";
        if (!astAttrGet(s->attrs, jg_mod)) {
            return mprintf("it is a %s, which has no @%s: add @%s to %s",
                           astTypeToString(s), jg_mod, jg_mod,
                           s->clsname ? s->clsname->data : "it");
        }
        return NULL;
    }
    if (k == JG_PTR_VAL) return parseJsonUnsupported(t->ptr);
    AstType *e = parseJsonElem(t, k);
    if (e) return parseJsonUnsupported(e);
    return NULL;
}

/* The type as written in the generated text; a module's names are
 * recorded so the lexed text gets them as one token (`shop.Line`) */
static char *parseJsonTypeText(Set *quals, AstType *t) {
    char *s = astTypeToString(t);
    if (strchr(s, '.')) {
        /* `shop.Line *`, `vec.Vec<shop.Line>`: each dotted name */
        char *p = s;
        while (*p) {
            if (isalpha((unsigned char)*p) || *p == '_') {
                char *b = p;
                while (isalnum((unsigned char)*p) || *p == '_' || *p == '.') p++;
                if (memchr(b, '.', p - b)) setAdd(quals, mprintf("%.*s", (int)(p - b), b));
            } else {
                p++;
            }
        }
    }
    return s;
}

static void parseJsonEncode(AoStr *o, Set *quals, char *expr, AstType *t, int depth);
static void parseJsonDecode(AoStr *o, Set *quals, char *lv, AstType *t, int depth);
static void parseJsonFree(AoStr *o, Set *quals, char *lv, AstType *t, int depth);

static void parseJsonEncode(AoStr *o, Set *quals, char *expr, AstType *t, int depth) {
    int k = parseJsonKind(t);
    switch (k) {
        case JG_INT:    aoStrCatPrintf(o, "__w.int(%s);\n", expr); break;
        case JG_U64:    aoStrCatPrintf(o, "__w.uint(%s);\n", expr); break;
        case JG_BOOL:   aoStrCatPrintf(o, "__w.bool(%s);\n", expr); break;
        case JG_FLOAT:  aoStrCatPrintf(o, "__w.float(%s);\n", expr); break;
        case JG_STRING:
        case JG_BYTES:  aoStrCatPrintf(o, "__w.str(%s);\n", expr); break;
        case JG_CSTR:
            aoStrCatPrintf(o, "if (%s) { __w.cstr(%s); } else { __w.nil(); }\n", expr, expr);
            break;
        case JG_ENUM: {
            /* by its member's name, its number when it has none */
            Map *m = t->enum_members;
            aoStrCatPrintf(o, "switch ((i64)%s) {\n", expr);
            for (u64 i = 0; m && m->indexes && i < m->indexes->size; ++i) {
                u64 mi = (u64)(uintptr_t)m->indexes->entries[i];
                Ast *v = (Ast *)m->entries[mi].value;
                char *name = (char *)m->entries[mi].key;
                char *dot = strrchr(name, '.');
                aoStrCatPrintf(o, "case %lld: __w.str(\"%s\"); break;\n",
                            (long long)v->i64, dot ? dot + 1 : name);
            }
            aoStrCatPrintf(o, "default: __w.int((i64)%s); break;\n}\n", expr);
            break;
        }
        case JG_STRUCT:
            aoStrCatPrintf(o, "%s.to%s(__w);\n", expr, jg_Fmt);
            break;
        case JG_PTR_STRUCT:
            aoStrCatPrintf(o, "if (%s) { %s.to%s(__w); } else { __w.nil(); }\n", expr, expr, jg_Fmt);
            break;
        case JG_PTR_VAL:
            aoStrCatPrintf(o, "if (%s) {\n", expr);
            parseJsonEncode(o, quals, mprintf("(*%s)", expr), t->ptr, depth + 1);
            aoStrCatPrintf(o, "} else { __w.nil(); }\n");
            break;
        case JG_VEC:
        case JG_SLICE:
        case JG_ARRAY: {
            AstType *e = parseJsonElem(t, k);
            if (k == JG_ARRAY && e->kind == AST_TYPE_CHAR && e->size == 1 && !e->is_bool) {
                /* a u8 array: the C string in it */
                aoStrCatPrintf(o, "__w.cstr(&%s[0]);\n", expr);
                break;
            }
            char *len = k == JG_ARRAY ? mprintf("%d", t->len) : mprintf("%s.len", expr);
            char *item = mprintf(k == JG_VEC ? "%s.data[__i%d]" : k == JG_SLICE ? "%s.ptr[__i%d]"
                                 : "%s[__i%d]", expr, depth);
            aoStrCatPrintf(o, "__w.beginArray();\nfor (i64 __i%d = 0; __i%d < %s; __i%d++) {\n",
                        depth, depth, len, depth);
            parseJsonEncode(o, quals, item, e, depth + 1);
            aoStrCatPrintf(o, "}\n__w.end();\n");
            break;
        }
    }
}

/* An integer read into `lv`, checked against its type's range */
static void parseJsonDecodeInt(AoStr *o, char *lv, AstType *t) {
    if (t->size >= 8) {
        aoStrCatPrintf(o, "%s = __r.readInt();\n", lv);
        return;
    }
    s64 lo, hi;
    if (t->issigned) {
        lo = -(1LL << (t->size * 8 - 1));
        hi = (1LL << (t->size * 8 - 1)) - 1;
    } else {
        lo = 0;
        hi = (1LL << (t->size * 8)) - 1;
    }
    aoStrCatPrintf(o, "{ i64 __v = __r.readInt();\n"
                   "if (__v < %lld || __v > %lld) { __r.fail(\"out of range for a %s\"); }\n"
                   "%s = __v; }\n", (long long)lo, (long long)hi, astTypeToString(t), lv);
}

static void parseJsonDecode(AoStr *o, Set *quals, char *lv, AstType *t, int depth) {
    int k = parseJsonKind(t);
    switch (k) {
        case JG_INT:
            if (t->is_intrinsic) {
                aoStrCatPrintf(o, "%s = __r.readInt();\n", lv);
            } else {
                parseJsonDecodeInt(o, lv, t);
            }
            break;
        case JG_U64:    aoStrCatPrintf(o, "%s = __r.readUint();\n", lv); break;
        case JG_BOOL:   aoStrCatPrintf(o, "%s = __r.readBool();\n", lv); break;
        case JG_FLOAT:  aoStrCatPrintf(o, "%s = __r.readFloat();\n", lv); break;
        case JG_STRING: aoStrCatPrintf(o, "__r.readString(&%s);\n", lv); break;
        case JG_BYTES:
            aoStrCatPrintf(o, "{ i64 __n = 0; u8 *__p = __r.readBytes(&__n);\n"
                           "%s = __p[0:__n]; }\n", lv);
            break;
        case JG_CSTR:
            aoStrCatPrintf(o, "if (__r.isNull()) { %s = null; } else { %s = __r.readBytes(); }\n",
                        lv, lv);
            break;
        case JG_ENUM: {
            /* by a member's name, or its number */
            Map *m = t->enum_members;
            char *it = t->size == 1 ? "u8" : t->size == 2 ? "u16" : t->size == 4 ? "i32" : "i64";
            aoStrCatPrintf(o, "if (__r.peek() == %s.INT) { *(%s *)&%s = __r.readInt(); } else {\n"
                           "u8[] __s = __r.readStr();\n", jg_mod, it, lv);
            for (u64 i = 0; m && m->indexes && i < m->indexes->size; ++i) {
                u64 mi = (u64)(uintptr_t)m->indexes->entries[i];
                Ast *v = (Ast *)m->entries[mi].value;
                char *name = (char *)m->entries[mi].key;
                char *dot = strrchr(name, '.');
                char *mn = dot ? dot + 1 : name;
                aoStrCatPrintf(o, "%sif (%s.__keyIs(__s, \"%s\", %d)) { *(%s *)&%s = %lld; }\n",
                            i ? "else " : "", jg_mod, mn, (int)strlen(mn), it, lv, (long long)v->i64);
            }
            aoStrCatPrintf(o, "%s{ __r.fail(\"not a member of %s\"); }\n}\n",
                        m && m->indexes && m->indexes->size ? "else " : "",
                        astTypeToString(t));
            break;
        }
        case JG_STRUCT:
            aoStrCatPrintf(o, "%s.from%s(__r);\n", lv, jg_Fmt);
            break;
        case JG_PTR_STRUCT:
            aoStrCatPrintf(o, "if (__r.isNull()) { %s = null; } else {\n"
                           "if (!%s) { %s = %s.__alloc(sizeof(*%s)); }\n"
                           "%s.from%s(__r); }\n", lv, lv, lv, jg_mod, lv, lv, jg_Fmt);
            break;
        case JG_PTR_VAL:
            aoStrCatPrintf(o, "if (__r.isNull()) { %s = null; } else {\n"
                           "if (!%s) { %s = (%s)%s.__alloc(sizeof(*%s)); %s.__zero(%s, sizeof(*%s)); }\n",
                           lv, lv, lv, parseJsonTypeText(quals, t), jg_mod, lv, jg_mod, lv, lv);
            parseJsonDecode(o, quals, mprintf("(*%s)", lv), t->ptr, depth + 1);
            aoStrCatPrintf(o, "}\n");
            break;
        case JG_VEC: {
            AstType *e = parseJsonElem(t, k);
            char *et = parseJsonTypeText(quals, e);
            char *tmp = mprintf("__t%d", depth);
            aoStrCatPrintf(o, "__r.beginArray();\nwhile (__r.nextItem()) {\n"
                           "%s %s;\n%s.__zero(&%s, sizeof(%s));\n", et, tmp, jg_mod, tmp, tmp);
            parseJsonDecode(o, quals, tmp, e, depth + 1);
            aoStrCatPrintf(o, "%s.push(%s);\n}\n", lv, tmp);
            break;
        }
        case JG_SLICE: {
            /* grown in a pointer of its own, the slice made at the end */
            AstType *e = parseJsonElem(t, k);
            char *et = parseJsonTypeText(quals, e);
            int d = depth;
            char *item = mprintf("__p%d[__n%d]", d, d);
            aoStrCatPrintf(o, "{ i64 __c%d = 0; i64 __n%d = 0; %s *__p%d = null;\n"
                           "__r.beginArray();\nwhile (__r.nextItem()) {\n"
                           "if (__n%d == __c%d) { __c%d = __c%d ? __c%d * 2 : 4;\n"
                           "__p%d = %s.__grow(__p%d, __c%d * sizeof(%s)); }\n"
                           "%s.__zero(&%s, sizeof(%s));\n",
                           d, d, et, d, d, d, d, d, d, d, jg_mod, d, d, et, jg_mod, item, et);
            parseJsonDecode(o, quals, item, e, depth + 1);
            aoStrCatPrintf(o, "__n%d++;\n}\n%s = __p%d[0:__n%d]; }\n", d, lv, d, d);
            break;
        }
        case JG_ARRAY: {
            AstType *e = parseJsonElem(t, k);
            if (e->kind == AST_TYPE_CHAR && e->size == 1 && !e->is_bool) {
                aoStrCatPrintf(o, "%s.__intoChars(__r, &%s[0], %d);\n", jg_mod, lv, t->len);
                break;
            }
            char *item = mprintf("%s[__i%d]", lv, depth);
            aoStrCatPrintf(o, "{ i64 __i%d = 0;\n__r.beginArray();\nwhile (__r.nextItem()) {\n"
                           "if (__i%d >= %d) { __r.fail(\"more than %d items\"); }\n",
                        depth, depth, t->len, t->len);
            parseJsonDecode(o, quals, item, e, depth + 1);
            aoStrCatPrintf(o, "__i%d++;\n}\n}\n", depth);
            break;
        }
    }
}

static void parseJsonFree(AoStr *o, Set *quals, char *lv, AstType *t, int depth) {
    int k = parseJsonKind(t);
    switch (k) {
        case JG_STRING:
            aoStrCatPrintf(o, "%s.free();\n", lv);
            break;
        case JG_BYTES:
            aoStrCatPrintf(o, "%s.__free(%s.ptr); %s.__zero(&%s, sizeof(%s));\n", jg_mod, lv, jg_mod, lv, lv);
            break;
        case JG_CSTR:
            aoStrCatPrintf(o, "%s.__free(%s); %s = null;\n", jg_mod, lv, lv);
            break;
        case JG_STRUCT:
            aoStrCatPrintf(o, "%s.freeDecoded();\n", lv);
            break;
        case JG_PTR_STRUCT:
            aoStrCatPrintf(o, "if (%s) { %s.freeDecoded(); %s.__free(%s); %s = null; }\n",
                        lv, lv, jg_mod, lv, lv);
            break;
        case JG_PTR_VAL:
            aoStrCatPrintf(o, "if (%s) {\n", lv);
            parseJsonFree(o, quals, mprintf("(*%s)", lv), t->ptr, depth + 1);
            aoStrCatPrintf(o, "%s.__free(%s); %s = null; }\n", jg_mod, lv, lv);
            break;
        case JG_VEC:
        case JG_SLICE:
        case JG_ARRAY: {
            AstType *e = parseJsonElem(t, k);
            int ek = parseJsonKind(e);
            int owns = ek == JG_STRING || ek == JG_BYTES || ek == JG_CSTR || ek == JG_STRUCT ||
                       ek == JG_PTR_STRUCT || ek == JG_VEC || ek == JG_SLICE || ek == JG_ARRAY ||
                       ek == JG_PTR_VAL;
            if (owns) {
                char *len = k == JG_ARRAY ? mprintf("%d", t->len) : mprintf("%s.len", lv);
                char *item = mprintf(k == JG_VEC ? "%s.data[__i%d]" : k == JG_SLICE ? "%s.ptr[__i%d]"
                                     : "%s[__i%d]", lv, depth);
                aoStrCatPrintf(o, "for (i64 __i%d = 0; __i%d < %s; __i%d++) {\n",
                            depth, depth, len, depth);
                parseJsonFree(o, quals, item, e, depth + 1);
                aoStrCatPrintf(o, "}\n");
            }
            if (k == JG_VEC) aoStrCatPrintf(o, "%s.free();\n", lv);
            if (k == JG_SLICE) {
                aoStrCatPrintf(o, "%s.__free(%s.ptr); %s.__zero(&%s, sizeof(%s));\n", jg_mod, lv, jg_mod, lv, lv);
            }
            break;
        }
        default:
            break;
    }
}

/* The fields a @json struct writes and reads: not @skip, not private */
static int parseJsonFieldUsed(AstType *ft) {
    return ft && !ft->private_module && !astAttrGet(ft->field_attrs, "skip");
}

/* An expression true when field `e` (of type t) is empty: 0, empty,
 * null; NULL for a type that is never empty (a struct) */
static char *parseJsonNonEmpty(char *e, AstType *t) {
    switch (parseJsonKind(t)) {
        case JG_INT: case JG_U64: case JG_BOOL: case JG_FLOAT: case JG_ENUM:
            return mprintf("%s != 0", e);
        case JG_STRING: case JG_BYTES: case JG_VEC: case JG_SLICE:
            return mprintf("%s.len != 0", e);
        case JG_CSTR: case JG_PTR_STRUCT: case JG_PTR_VAL:
            return mprintf("%s != null", e);
        default:
            return NULL;
    }
}

/* The source of S.toJson, S.fromJson and S.freeDecoded */
static AoStr *parseJsonSource(Cctrl *cc, AstType *cls, char *tname, Set *quals, int with_free) {
    AoStr *o = aoStrNew();
    Map *fields = cls->fields;
    char *pub = cc->cur_module ? "pub " : "";

    aoStrCatPrintf(o, "%svoid %s.to%s(%s.Writer *__w) {\n__w.beginObject();\n", pub, tname, jg_Fmt, jg_mod);
    for (u64 i = 0; fields && fields->indexes && i < fields->indexes->size; ++i) {
        u64 fi = (u64)(uintptr_t)fields->indexes->entries[i];
        AstType *ft = fields->entries[fi].value;
        char *fname = (char *)fields->entries[fi].key;
        if (!parseJsonFieldUsed(ft)) continue;
        AstAttr *key = astAttrGet(ft->field_attrs, "key");
        char *e = mprintf("self.%s", fname);
        char *cond = astAttrGet(ft->field_attrs, "omitEmpty") ? parseJsonNonEmpty(e, ft) : NULL;
        if (cond) aoStrCatPrintf(o, "if (%s) {\n", cond);
        aoStrCatPrintf(o, "__w.rawKey(\"%s\");\n", key ? key->arg->data : fname);
        parseJsonEncode(o, quals, e, ft, 0);
        if (cond) aoStrCatPrintf(o, "}\n");
    }
    aoStrCatPrintf(o, "__w.end();\n}\n");

    /* With the Reader's strict flag (json.STRICT / yaml.STRICT) a key with
     * no field and a missing key are errors, as json.decode's reflection
     * path reports them: each field's key is noted as it is seen (__sN) */
    aoStrCatPrintf(o, "%svoid %s.from%s(%s.Reader *__r) {\n", pub, tname, jg_Fmt, jg_mod);
    for (u64 i = 0; fields && fields->indexes && i < fields->indexes->size; ++i) {
        u64 fi = (u64)(uintptr_t)fields->indexes->entries[i];
        if (!parseJsonFieldUsed(fields->entries[fi].value)) continue;
        aoStrCatPrintf(o, "bool __s%d = false;\n", (int)i);
    }
    aoStrCatPrintf(o, "__r.beginObject();\nwhile (__r.nextKey()) {\nu8[] __k = __r.key();\n");
    for (u64 i = 0; fields && fields->indexes && i < fields->indexes->size; ++i) {
        u64 fi = (u64)(uintptr_t)fields->indexes->entries[i];
        AstType *ft = fields->entries[fi].value;
        char *fname = (char *)fields->entries[fi].key;
        if (!parseJsonFieldUsed(ft)) continue;
        AstAttr *key = astAttrGet(ft->field_attrs, "key");
        char *kt = key ? key->arg->data : fname;
        aoStrCatPrintf(o, "if (__k.len == %d && %s.__keyIs(__k, \"%s\", %d)) {\n__s%d = true;\n",
                    (int)strlen(kt), jg_mod, kt, (int)strlen(kt), (int)i);
        parseJsonDecode(o, quals, mprintf("self.%s", fname), ft, 0);
        aoStrCatPrintf(o, "continue;\n}\n");
    }
    aoStrCatPrintf(o, "if (__r.strict) { __r.fail(\"no such field in %s\"); }\n"
                   "__r.skipValue();\n}\nif (__r.strict) {\n", cls->clsname->data);
    for (u64 i = 0; fields && fields->indexes && i < fields->indexes->size; ++i) {
        u64 fi = (u64)(uintptr_t)fields->indexes->entries[i];
        AstType *ft = fields->entries[fi].value;
        char *fname = (char *)fields->entries[fi].key;
        if (!parseJsonFieldUsed(ft)) continue;
        AstAttr *key = astAttrGet(ft->field_attrs, "key");
        aoStrCatPrintf(o, "if (!__s%d) { __r.fail(\"missing\", \"%s\"); }\n",
                    (int)i, key ? key->arg->data : fname);
    }
    aoStrCatPrintf(o, "}\n}\n");

    if (!with_free) return o;
    aoStrCatPrintf(o, "%svoid %s.freeDecoded() {\n", pub, tname);
    for (u64 i = 0; fields && fields->indexes && i < fields->indexes->size; ++i) {
        u64 fi = (u64)(uintptr_t)fields->indexes->entries[i];
        AstType *ft = fields->entries[fi].value;
        char *fname = (char *)fields->entries[fi].key;
        if (!parseJsonFieldUsed(ft)) continue;
        parseJsonFree(o, quals, mprintf("self.%s", fname), ft, 0);
    }
    aoStrCatPrintf(o, "}\n");
    return o;
}

/* `src` as tokens that read as if they were at `at` (its file, module
 * and line): a module's names in `quals` (and json's) one token each,
 * as the lexer makes them after an import */
static Vec *parseJsonLex(Cctrl *cc, char *src, Lexeme *at, Set *quals) {
    Lexer l;
    Lexeme tok;
    Vec *raw = vecNew(&vec_unsigned_long_type);
    lexInit(&l, src, CCF_PRE_PROC);
    l.cc = cc;
    while (lex(&l, &tok)) {
        Lexeme *t = lexemeCopy(&tok);
        t->file = at->file;
        t->module_id = at->module_id;
        t->in_std = at->in_std;
        t->mod_pass = LEX_PASS_NONE;
        t->line = at->line;
        t->col = at->col;
        t->qual_prefix = NULL;
        t->qual_prefix_len = 0;
        t->is_prefix = 0;
        vecPush(raw, t);
    }
    Vec *toks = vecNew(&vec_unsigned_long_type);
    for (u64 i = 0; i < raw->size; ++i) {
        Lexeme *t = raw->entries[i];
        int after_member = i > 0 && (tokenPunctIs(raw->entries[i - 1], '.') ||
                                     ((Lexeme *)raw->entries[i - 1])->tk_type == TK_DEREFERENCE);
        if (t->tk_type == TK_IDENT && !after_member) {
            /* the longest `a.b.c` here that is a module's name */
            u64 best = 0;
            AoStr *name = aoStrDupRaw(t->start, t->len);
            for (u64 j = i + 2; j < raw->size; j += 2) {
                Lexeme *d = raw->entries[j - 1], *n = raw->entries[j];
                if (!tokenPunctIs(d, '.') || n->tk_type != TK_IDENT) break;
                aoStrCatPrintf(name, ".%.*s", n->len, n->start);
                int is_json = !strncmp(name->data, jg_mod, 4) && name->data[4] == '.' &&
                              !strchr(name->data + 5, '.');
                if (is_json || setHas(quals, name->data)) best = j;
            }
            if (best) {
                Lexeme *q = lexemeCopy(t);
                AoStr *text = aoStrDupRaw(t->start, t->len);
                for (u64 j = i + 2; j <= best; j += 2) {
                    Lexeme *n = raw->entries[j];
                    aoStrCatPrintf(text, ".%.*s", n->len, n->start);
                }
                q->qual_prefix = t->start;
                q->qual_prefix_len = t->len;
                q->start = text->data;
                q->len = text->len;
                vecPush(toks, q);
                i = best;
                continue;
            }
        }
        vecPush(toks, t);
    }
    return toks;
}

/* @json or @yaml (`mod`) struct `cls` (complete, at `at`): its methods,
 * made once; freeDecoded with the first of them */
static void parseFormatGenerate(Cctrl *cc, AstType *cls, Lexeme *at, const char *mod) {
    if (!cls->clsname || !cls->fields) return;
    if (!cc->json_done) cc->json_done = setNew(16, &set_cstring_type);
    char *done_key = mprintf("%s:%s", mod, cls->clsname->data);
    if (setHas(cc->json_done, done_key)) return;
    setAdd(cc->json_done, done_key);
    char *free_key = mprintf("free:%s", cls->clsname->data);
    int with_free = !setHas(cc->json_done, free_key);
    setAdd(cc->json_done, free_key);
    jg_mod = mod;
    jg_Fmt = mod[0] == 'j' ? "Json" : "Yaml";

    /* its file imports json (and the generated code uses it) */
    LexImport *imp = at->file ? at->file->imports : NULL;
    for (; imp; imp = imp->next) {
        if (!strcmp(imp->module->path->data, mod)) break;
    }
    if (!imp || !mapGet(cc->clsdefs, mprintf("%s.Writer", mod))) {
        cctrlRaiseExceptionAt(cc, at->line, at->col, at->len,
                "`@%s` on %s needs `import %s;` in its file", mod, cls->clsname->data, mod);
    }
    imp->used = 1;
    Map *fields = cls->fields;
    for (u64 i = 0; fields && fields->indexes && i < fields->indexes->size; ++i) {
        u64 fi = (u64)(uintptr_t)fields->indexes->entries[i];
        AstType *ft = fields->entries[fi].value;
        if (!parseJsonFieldUsed(ft)) continue;
        char *why = parseJsonUnsupported(ft);
        if (why && !*why && parseJsonKind(ft) != JG_PTR_STRUCT) continue;
        if (why && !*why) {
            /* checked when the bodies are made */
            JsonDeferred *jd = calloc(1, sizeof(JsonDeferred));
            jd->pointee = ft->ptr;
            jd->cls = cls;
            jd->field = (char *)fields->entries[fi].key;
            jd->mod = mod;
            jd->line = ft->line ? ft->line : at->line;
            jd->col = ft->line ? ft->col : at->col;
            jd->file = at->file;
            if (!cc->json_deferred) cc->json_deferred = vecNew(&vec_unsigned_long_type);
            vecPush(cc->json_deferred, jd);
            continue;
        }
        if (why) {
            cctrlRaiseExceptionAt(cc, ft->line ? ft->line : at->line,
                    ft->line ? ft->col : at->col, (int)strlen((char *)fields->entries[fi].key),
                    "field `%s` of @%s %s: %s (mark it @skip to leave it out)",
                    (char *)fields->entries[fi].key, mod, cls->clsname->data, why);
        }
    }

    /* `Order` in module shop, `Box` for `Box<i64>`: the method's struct
     * comes from the instance (parseMethodDef) */
    char *full = cls->clsname->data;
    int top = astLastTopDot(full, (int)cls->clsname->len);
    char *tname = top >= 0 ? full + top + 1 : full;
    char *lt = strchr(tname, '<');
    if (lt) tname = mprintf("%.*s", (int)(lt - tname), tname);

    Set *quals = setNew(8, &set_cstring_type);
    AoStr *src = parseJsonSource(cc, cls, tname, quals, with_free);
    if (parse_expand_generated) {
        fprintf(stderr, "// @%s %s\n%s\n", mod, full, src->data);
    }

    GenericDef *d = calloc(1, sizeof(GenericDef));
    d->kind = GEN_METHOD;
    d->name = cls->clsname;
    d->params = vecNew(&vec_unsigned_long_type);
    d->toks = parseJsonLex(cc, src->data, at, quals);
    d->module_id = at->module_id;
    d->at = at;
    d->file_id = ast_file_hint;
    GenericInst *in = calloc(1, sizeof(GenericInst));
    in->def = d;
    in->args = vecNew(&vec_unsigned_long_type);
    in->name = cls->clsname;
    in->cls = cls;
    in->at = at;
    in->at_file = cctrlCurFile(cc);
    in->proto_only = 1;
    parseGenericReplay(cc, d, in);
    GenericInst *body = calloc(1, sizeof(GenericInst));
    *body = *in;
    body->proto_only = 0;
    if (!cc->generic_pending) cc->generic_pending = vecNew(&vec_unsigned_long_type);
    vecPush(cc->generic_pending, body);
}

/* The pointer fields whose struct had no body yet when their struct's
 * methods were made: an error at the field when that struct never got
 * the attribute. The structs with one don't get their bodies made (they
 * would only fail again, less clearly). */
static Set *parseJsonCheckDeferred(Cctrl *cc) {
    Set *failed = NULL;
    for (u64 i = 0; cc->json_deferred && i < cc->json_deferred->size; ++i) {
        JsonDeferred *jd = cc->json_deferred->entries[i];
        AstType *p = jd->pointee;
        if (p->fields && astAttrGet(p->attrs, jd->mod)) continue;
        LexFile *saved = cc->tok_file;
        cc->tok_file = jd->file;
        cctrlErrorAt(cc, jd->line, jd->col, (int)strlen(jd->field),
                "field `%s` of @%s %s: it points to a %s, which has no @%s: add @%s to %s "
                "(mark it @skip to leave it out)", jd->field, jd->mod, jd->cls->clsname->data,
                astTypeToString(p), jd->mod, jd->mod,
                p->clsname ? p->clsname->data : "it");
        cc->tok_file = saved;
        if (!failed) failed = setNew(8, &set_cstring_type);
        setAdd(failed, jd->cls->clsname->data);
    }
    return failed;
}

static void parseJsonGenerate(Cctrl *cc, AstType *cls, Lexeme *at) {
    if (astAttrGet(cls->attrs, "json")) parseFormatGenerate(cc, cls, at, "json");
    if (astAttrGet(cls->attrs, "yaml")) parseFormatGenerate(cc, cls, at, "yaml");
}

void parseGenericFlush(Cctrl *cc) {
    Set *json_failed = parseJsonCheckDeferred(cc);
    for (u64 i = 0; cc->generic_pending && i < cc->generic_pending->size; ++i) {
        GenericInst *in = cc->generic_pending->entries[i];
        if (json_failed && in->cls && in->cls->clsname &&
            setHas(json_failed, in->cls->clsname->data)) continue;
        parseGenericReplayGuarded(cc, in);
    }
}

/* Parse the declarations in `toks` as the declarations pass would, from
 * the tokens instead of the lexer, diagnostics dropped (Cctrl.replaying).
 * 1 if it went through without an error. */
static int parseModReplay(Cctrl *cc, Vec *toks) {
    u64 cap = roundUpToNextPowerOf2(toks->size + 1);
    Lexeme **ring = (Lexeme **)malloc(cap * sizeof(Lexeme *));
    /* padded with sentinels, as for a #define's value: error paths
     * rewind past the ends */
    for (u64 i = 0; i < cap; ++i) {
        ring[i] = i < toks->size ? (Lexeme *)toks->entries[i] : lexemeSentinal();
    }
    TokenRingBuffer replay = {0};
    replay.entries = ring;
    replay.size = toks->size;
    replay.capacity = cap;

    TokenRingBuffer *saved_ring = cc->token_buffer;
    jmp_buf *saved_recovery = cc->current_recovery;
    Map *saved_localenv = cc->localenv;
    List *saved_tmp_locals = cc->tmp_locals;
    AstType *saved_gvar_base = cc->tmp_gvar_base_type;
    Ast *saved_gvar_decl = cc->tmp_gvar_decl;
    u32 saved_module = cc->cur_module;
    int saved_decls_only = cc->decls_only;
    int saved_pub = cc->decl_pub;
    int saved_from_main = cc->decl_from_main;
    s64 saved_lineno = cc->lineno;
    LexFile *saved_tok_file = cc->tok_file;
    List *volatile ast_tail = cc->ast_list ? cc->ast_list->prev : NULL;
    int errors = cc->quiet_errors;
    volatile int ok = 1;

    jmp_buf recovery;
    cc->token_buffer = &replay;
    cc->current_recovery = &recovery;
    cc->replaying = 1;
    cc->localenv = NULL;
    cc->tmp_locals = NULL;
    cc->tmp_gvar_base_type = NULL;
    cc->tmp_gvar_decl = NULL;
    if (setjmp(recovery) != 0) {
        ok = 0;
    } else {
        int is_global = 0;
        Ast *ast;
        while ((ast = parseToplevelDef(cc, &is_global)) != NULL) {
            if (!is_global && ast->kind != AST_FUN_PROTO) {
                listAppend(cc->ast_list, ast);
            }
            is_global = 0;
            cc->localenv = NULL;
        }
    }
    if (cc->quiet_errors != errors) ok = 0;
    if (!ok && cc->ast_list && ast_tail) {
        ast_tail->next = cc->ast_list;
        cc->ast_list->prev = ast_tail;
    }
    cc->replaying = 0;
    cc->token_buffer = saved_ring;
    cc->current_recovery = saved_recovery;
    cc->localenv = saved_localenv;
    cc->tmp_locals = saved_tmp_locals;
    cc->tmp_gvar_base_type = saved_gvar_base;
    cc->tmp_gvar_decl = saved_gvar_decl;
    cc->cur_module = saved_module;
    cc->decls_only = saved_decls_only;
    cc->decl_pub = saved_pub;
    cc->decl_from_main = saved_from_main;
    cc->lineno = saved_lineno;
    cc->tok_file = saved_tok_file;
    free(ring);
    return ok;
}

/* The index after the attribute at toks[i] (`@name` or `@name("x")`) */
static u64 parseSkipAttrToks(Vec *toks, u64 i) {
    i += 2;
    if (i < toks->size && tokenPunctIs(toks->entries[i], '(')) {
        while (i < toks->size && !tokenPunctIs(toks->entries[i], ')')) i++;
        i++;
    }
    return i < toks->size ? i : toks->size;
}

/* The names pass: record the declaration starting with `first`, and
 * declare the struct/union/class it defines (`pub struct Box;`) */
static void parseModuleNamesDecl(Cctrl *cc, Lexeme *first) {
    ParseModDecl *d = malloc(sizeof(ParseModDecl));
    d->module_id = first->module_id;
    d->toks = vecNew(&vec_unsigned_long_type);
    d->is_type = 0;
    d->replayed = 0;
    parseModDeclRest(cc, first, d->toks);
    /* replayed as the declarations pass */
    for (u64 i = 0; i < d->toks->size; ++i) {
        ((Lexeme *)d->toks->entries[i])->mod_pass = LEX_PASS_DECLS;
    }

    u64 i = 0;
    while (i < d->toks->size) {
        Lexeme *t = d->toks->entries[i];
        if (tokenPunctIs(t, '@')) {
            i = parseSkipAttrToks(d->toks, i);
            continue;
        }
        if (t->tk_type != TK_KEYWORD ||
            (t->i64 != KW_PUBLIC && t->i64 != KW_PRIVATE &&
             t->i64 != KW_STATIC && t->i64 != KW_THREADLOCAL)) break;
        i++;
    }
    Lexeme *kw = i < d->toks->size ? d->toks->entries[i] : NULL;
    if (kw && kw->tk_type == TK_KEYWORD &&
        (kw->i64 == KW_CLASS || kw->i64 == KW_UNION) &&
        i + 1 < d->toks->size &&
        ((Lexeme *)d->toks->entries[i + 1])->tk_type == TK_IDENT)
    {
        /* `[pub] struct Name;`: the name now, the body later (and its
         * attributes with the body) */
        Vec *fwd = vecNew(&vec_unsigned_long_type);
        for (u64 j = 0; j <= i + 1; ++j) {
            if (tokenPunctIs(d->toks->entries[j], '@')) {
                j = parseSkipAttrToks(d->toks, j) - 1;
                continue;
            }
            vecPush(fwd, d->toks->entries[j]);
        }
        Lexeme *semi = lexemeCopy(d->toks->entries[i + 1]);
        semi->tk_type = TK_PUNCT;
        semi->i64 = ';';
        semi->start = ";";
        semi->len = 1;
        vecPush(fwd, semi);
        parseModReplay(cc, fwd);
        vecRelease(fwd);
        for (u64 j = i + 2; j < d->toks->size; ++j) {
            if (tokenPunctIs(d->toks->entries[j], '{')) {
                d->is_type = 1;
                break;
            }
        }
    } else if (kw && kw->tk_type == TK_KEYWORD &&
               (kw->i64 == KW_ENUM || kw->i64 == KW_CONST)) {
        d->is_type = 1;
    } else if (kw && i + 2 < d->toks->size &&
               parseStartsTypeAlias(kw, d->toks->entries[i + 1], d->toks->entries[i + 2])) {
        /* parsed with the module's types, in any order */
        d->is_type = 1;
    }
    vecPush(cc->mod_type_decls, d);
}

/* Module `module_id`'s names pass is over: parse its type declarations
 * from their tokens while more of them succeed. The declarations pass
 * skips the ones that did. */
static void parseModuleTypes(Cctrl *cc, u32 module_id) {
    int progress = 1;
    while (progress) {
        progress = 0;
        for (u64 i = 0; i < cc->mod_type_decls->size; ++i) {
            ParseModDecl *d = cc->mod_type_decls->entries[i];
            if (d->module_id != module_id || d->replayed || !d->is_type) continue;
            if (parseModReplay(cc, d->toks)) {
                setAdd(cc->mod_decls_done, parseModDeclKey(d->toks->entries[0]));
                d->replayed = 1;
                progress = 1;
            }
        }
    }
    for (u64 i = 0; i < cc->mod_type_decls->size; ++i) {
        ParseModDecl *d = cc->mod_type_decls->entries[i];
        if (d->module_id == module_id) d->replayed = 1;
    }
}

/* The `(` just gotten starts `(fn(...) R)`: put it back for
 * parseBaseDeclSpec. Otherwise it stays gotten. */
static int parseGotParenFnType(Cctrl *cc) {
    cctrlTokenRewind(cc);
    if (parseFnStartsType(cc)) {
        return 1;
    }
    cctrlTokenGet(cc);
    return 0;
}
/* `threadlocal` seen on the declaration being parsed (and where), and
 * whether a `threadlocal T a, b;` list continues into the next declarator */
static int parse_decl_tls = 0;
static s64 parse_tls_line = 0, parse_tls_col = 0;
static int parse_gvar_list_tls = 0;

static void parseSawThreadLocal(Lexeme *tok) {
    parse_decl_tls = 1;
    parse_tls_line = tok->line;
    parse_tls_col = tok->col;
}

static Ast *parseToplevelDefImpl(Cctrl *cc, int *is_global);

/* A `threadlocal` declaration: the global it declared gets one copy per
 * thread. Anything else under the keyword is an error. */
static void parseMarkThreadLocal(Cctrl *cc, Ast *ast, int run_time_init) {
    int is_gvar_init = ast && ast->kind == AST_BINOP &&
                       ast->binop == AST_BIN_OP_ASSIGN &&
                       ast->left && ast->left->kind == AST_GVAR;
    if (is_gvar_init || (run_time_init && ast && ast->kind == AST_DECL)) {
        cctrlRaiseExceptionAt(cc, parse_tls_line, parse_tls_col, 11,
            "a threadlocal global's initial value must be a constant: "
            "every thread starts from it");
    }
    if (ast && ast->kind == AST_DECL && ast->declvar &&
        ast->declvar->kind == AST_GVAR)
    {
        ast->flags |= AST_FLAG_TLS;
        ast->declvar->flags |= AST_FLAG_TLS;
        return;
    }
    cctrlRaiseExceptionAt(cc, parse_tls_line, parse_tls_col, 11,
        "`threadlocal` only goes on a global variable");
}

Ast *parseToplevelDef(Cctrl *cc, int *is_global) {
    parse_decl_tls = cc->tmp_gvar_base_type ? parse_gvar_list_tls : 0;
    u64 inits = parse_init_count;
    Ast *ast = parseToplevelDefImpl(cc, is_global);
    int tls = parse_decl_tls;
    parse_decl_tls = 0;
    parse_gvar_list_tls = cc->tmp_gvar_base_type ? tls : 0;
    if (tls) parseMarkThreadLocal(cc, ast, parse_init_count != inits);
    return ast;
}

static Ast *parseToplevelDefImpl(Cctrl *cc, int *is_global) {
    Ast *variable, *asm_block, *asm_func, *extern_func, *ast;
    AstType *type = NULL, *base_type = NULL;
    Lexeme *tok, *name, *peek;
    int is_static = 0;
    int is_const = 0;
    int saw_private = 0;
    cc->decl_pub = 0;

    while (1) {
        if ((tok = cctrlTokenGet(cc)) == NULL) {
            if (cc->tmp_gvar_base_type) {
                cc->tmp_gvar_base_type = NULL;
                cctrlRaiseException(cc,"Unexpected end of input, expected a declaration after `,`");
            }
            return NULL;
        }
        /* A module's names pass (see parseModuleNamesDecl) */
        if (tok->mod_pass == LEX_PASS_NAMES) {
            parseModuleNamesDecl(cc, tok);
            continue;
        }
        if (tok->mod_pass == LEX_PASS_DECLS && !cc->replaying && !cc->inst &&
            !cc->tmp_gvar_base_type) {
            /* by the module's path, not its id: ids are the lexer's, and
             * the LSP parses with a new lexer in a Cctrl that already
             * read the stdlib, so an id there can be another module's */
            Lexer *lx = cc->lexer_;
            char *mod_key = lx && tok->module_id <= (u32)lx->modules_len
                ? lx->modules[tok->module_id - 1]->path->data
                : mprintf("%u", tok->module_id);
            if (!setHas(cc->mod_types_done, mod_key)) {
                setAdd(cc->mod_types_done, mod_key);
                parseModuleTypes(cc, tok->module_id);
            }
            char *key = parseModDeclKey(tok);
            if (setHas(cc->mod_decls_done, key)) {
                parseModDeclRest(cc, tok, NULL);
                continue;
            }
        }
        /* Which module this declaration is in, and its pass */
        cc->cur_module = tok->module_id;
        cc->decls_only = cc->inst ? cc->inst->proto_only
                                  : tok->mod_pass == LEX_PASS_DECLS;
        if (!cc->tmp_gvar_base_type) {
            cc->decl_from_main = tok->module_id == 0 && !tok->in_std;
        }
        /* a generic struct, method or function: recorded for its
         * instances (parseGenericDecl) */
        if (!cc->tmp_gvar_base_type && parseGenericDecl(cc, tok)) {
            continue;
        }

        if (!cc->tmp_gvar_base_type) {
            parseRejectAtomicDecl(cc, tok, cctrlTokenPeek(cc));
        }

        /* An empty declaration: a stray `;` as in `I64 x = 1;;` or
         * `U0 F() {};`. Skip it, it would be taken for the type of the
         * next declaration and swallow it. */
        if (tokenPunctIs(tok, ';') && !cc->tmp_gvar_base_type) {
            continue;
        }

        /* `@json @yaml [pub] struct Name { ... };`: the struct's
         * attributes, taken by parseClassOrUnion */
        if (tokenPunctIs(tok, '@') && !cc->tmp_gvar_base_type) {
            if (cc->pending_attrs || is_static || is_const || cc->decl_pub || saw_private) {
                cctrlRaiseExceptionAt(cc, tok->line, tok->col, 1,
                        "a struct's attributes come first, together: "
                        "@json @yaml pub struct Name { ... };");
            }
            Lexeme *at = tok;
            Vec *attrs = parseAttrs(cc, tok, 0);
            int k = 0;
            Lexeme *next = cctrlTokenPeekBy(cc, k);
            while (next && next->tk_type == TK_KEYWORD &&
                   (next->i64 == KW_PUBLIC || next->i64 == KW_PRIVATE)) {
                next = cctrlTokenPeekBy(cc, ++k);
            }
            if (!next || next->tk_type != TK_KEYWORD || next->i64 != KW_CLASS) {
                AstAttr *a = attrs->entries[0];
                cctrlRaiseExceptionAt(cc, at->line, at->col, a->name->len + 1,
                        "`@%s` goes on a struct: @%s struct Name { ... };",
                        a->name->data, a->name->data);
            }
            cc->pending_attrs = attrs;
            continue;
        }

        if (!cc->tmp_gvar_base_type &&
            parseStartsTypeAlias(tok, cctrlTokenPeek(cc), cctrlTokenPeekBy(cc, 1))) {
            if (is_static || is_const) {
                cctrlRaiseException(cc, "a type alias can't be static or const");
            }
            parseTypeAlias(cc);
            cc->decl_pub = 0;
            saw_private = 0;
            continue;
        }

        if (cc->tmp_gvar_base_type) {
            /* Next declarator of `T a, b;` - the base type carries
             * over, any `*`s belong to this declarator alone, and so
             * does `static`. */
            base_type = cc->tmp_gvar_base_type;
            cc->tmp_gvar_base_type = NULL;
            is_static = parse_gvar_list_static;
            is_const = parse_gvar_list_const;
            cctrlTokenRewind(cc);
            type = parsePointerType(cc,base_type);
        } else if (tok->tk_type == TK_KEYWORD) {
            switch (tok->i64) {
                case KW_ASM_EXTERN: {
                    if ((asm_func = parseAsmFunctionBinding(cc)) != NULL) {
                        return asm_func;
                    }
                    cctrlRaiseException(cc,"Floating \"_extern\" keyword, expected \"_extern '_ASM_FUNC'\""); 
                }

                case KW_ASM: {
                    peek = cctrlTokenPeek(cc);
                    if (tokenPunctIs(peek,'{')) {
                        asm_block = prsAsm(cc, 0);
                        /* a module's declarations pass only learns its
                         * functions; the full pass emits the block */
                        if (cc->decls_only) continue;
                        listAppend(cc->asm_blocks,asm_block);
                        return asm_block;
                    }
                    cctrlRaiseException(cc,"Floating \"asm\" keyword, expected \"asm {\" got - %.*s",
                            peek->len, peek->start);
                }

                case KW_EXTERN: {
                    tok = cctrlTokenGetRequired(cc);
                    /* `extern threadlocal T name;` */
                    if (tok->tk_type == TK_KEYWORD && tok->i64 == KW_THREADLOCAL) {
                        parseSawThreadLocal(tok);
                        tok = cctrlTokenGetRequired(cc);
                    }
                    if (tok->tk_type == TK_STR && !strncmp(tok->start,"c",1)) {
                        /* extern "c" func(...) - C-ABI function decl. */
                        type = parseDeclSpec(cc);
                        name = cctrlTokenGet(cc);
                        if (name && name->tk_type == TK_IDENT &&
                            !tokenPunctIs(cctrlTokenPeek(cc),'('))
                        {
                            /* `extern "c" T name;`: C data (environ),
                             * known by its C name everywhere */
                            type = parseArrayDimensions(cc, type);
                            cctrlTokenExpect(cc,';');
                            Ast *gvar = astGVar(type, name->start, name->len, 0);
                            /* Mach-O spells a C symbol with a leading `_` */
                            if (cc->target == TARGET_AARCH64_APPLE_DARWIN ||
                                cc->target == TARGET_X86_64_APPLE_DARWIN) {
                                gvar->glabel = aoStrPrintf("_%.*s", name->len, name->start);
                            }
                            Ast *decl = astDecl(gvar, NULL);
                            decl->flags |= AST_FLAG_EXTERN;
                            gvar->flags |= AST_FLAG_EXTERN | AST_FLAG_EXTERN_C;
                            mapAdd(cc->global_env, gvar->gname->data, gvar);
                            return decl;
                        }
                        cctrlTokenExpect(cc,'(');
                        extern_func = parseExternFunctionProto(cc,type,
                                name->start,name->len);
                        return extern_func;
                    }
                    /* `extern Type name;` data forward
                     * declaration. The next token was the start of the
                     * type; put it back so parseFullType sees it. */
                    cctrlTokenRewind(cc);
                    type = parseFullType(cc);
                    name = cctrlTokenGet(cc);
                    /* `extern T (*name)(params);`: a function pointer
                     * global defined elsewhere (a library's), parsed as
                     * the global definition parses it */
                    if (name && tokenPunctIs(name,'(')) {
                        Lexeme *fnptr_name = parseFnPtrDeclName(cc);
                        char *fname;
                        int flen;
                        cc->localenv = cctrlCreateAstMap(cc->localenv);
                        type = parseFunctionPointerType(cc,&fname,&flen,type);
                        cc->localenv = NULL;
                        name = fnptr_name;
                        if (name->tk_type != TK_IDENT) {
                            /* reported by parseFunctionPointerType */
                            cctrlTerminate(cc);
                        }
                    }
                    if (name->tk_type != TK_IDENT) {
                        cctrlRaiseException(cc,
                            "`extern <Type>` must be followed by an "
                            "identifier, got `%s`",
                            lexemeAsWritten(name));
                    }
                    type = parseArrayDimensions(cc, type);
                    cctrlTokenExpect(cc,';');
                    cctrlNoteHeaderName(cc, name->start, name->len);
                    /* a ktm global defined elsewhere: a module's is named
                     * as its other globals are, a program's gets the
                     * program's symbol; C data is `extern "c"` */
                    char *ext_name = name->start;
                    int ext_len = name->len;
                    if (cc->cur_module) {
                        char *qual = cctrlDeclName(cc, name->start, name->len);
                        if (qual) {
                            ext_name = qual;
                            ext_len = strlen(qual);
                        }
                    }
                    Ast *gvar = astGVar(type, ext_name, ext_len, 0);
                    parseProgramGlobal(cc, gvar);
                    Ast *decl = astDecl(gvar, NULL);
                    decl->flags |= AST_FLAG_EXTERN;
                    gvar->flags |= AST_FLAG_EXTERN;
                    mapAdd(cc->global_env, gvar->gname->data, gvar);
                    return decl;
                }
                case KW_INLINE: {
                    if (is_const) {
                        cctrlRaiseException(cc,"const is only allowed on declarations");
                    }
                    peek = cctrlTokenPeek(cc);
                    if (!peek) {
                        cctrlRaiseException(cc,"Unexpected end of input after `inline`");
                    }
                    if (!cctrlGetKeyWord(cc, peek->start, peek->len)) {
                        cctrlRaiseException(cc,"Expected return type declaration got: '%.*s'",
                                peek->len,peek->start);
                    }
                    type = parseFullType(cc);
                    name = cctrlTokenGetRequired(cc);

                    /* `inline` is only a hint: compile the function as an
                     * ordinary one so it can be called normally, recursed
                     * into, have its address taken and use default
                     * arguments. */
                    ast = parseFunctionOrDef(cc,type,name->start,name->len,0);
                    ast->line = name->line;
                    ast->col = name->col;
                    return ast;
                }
                case KW_PUBLIC:
                    if (saw_private) {
                        cctrlRaiseException(cc, "a declaration is either `pub` or `private`, not both");
                    }
                    cc->decl_pub = 1;
                    continue;
                /* `private` says what leaving out `pub` means */
                case KW_PRIVATE:
                    if (cc->decl_pub) {
                        cctrlRaiseException(cc, "a declaration is either `pub` or `private`, not both");
                    }
                    saw_private = 1;
                    continue;
                case KW_CONST:
                    is_const = 1;
                    continue;
                case KW_THREADLOCAL:
                    parseSawThreadLocal(tok);
                    continue;

                case KW_DEFER:
                    cctrlRaiseExceptionAt(cc, tok->line, tok->col, tok->len,
                        "`defer` is only allowed inside a function");
                    cctrlTerminate(cc);

                case KW_STATIC:
                    /* `static threadlocal T x;` */
                    peek = cctrlTokenPeek(cc);
                    if (peek && peek->tk_type == TK_KEYWORD &&
                        peek->i64 == KW_THREADLOCAL) {
                        parseSawThreadLocal(cctrlTokenGet(cc));
                    }
                    base_type = parseBaseDeclSpec(cc);
                    if (base_type == NULL) {
                        cctrlRaiseException(cc,"Expected type declaration");
                    }
                    type = parsePointerType(cc,base_type);
                    /* A local symbol: see AST_FLAG_STATIC; a variable
                     * gets a local label (astGVar) */
                    is_static = 1;
                    break;
                
                case KW_CLASS:
                    parseClassDef(cc,NULL);
                    cctrlTokenExpect(cc,';');
                    /* `pub`/`private` were this declaration's, not the next one's */
                    cc->decl_pub = 0;
                    saw_private = 0;
                    continue;

                case KW_UNION:
                    parseUnionDef(cc);
                    cctrlTokenExpect(cc,';');
                    /* `pub`/`private` were this declaration's, not the next one's */
                    cc->decl_pub = 0;
                    saw_private = 0;
                    continue;

                case KW_ENUM:
                    parseEnumDef(cc);
                    cctrlTokenExpect(cc,';');
                    /* `pub`/`private` were this declaration's, not the next one's */
                    cc->decl_pub = 0;
                    saw_private = 0;
                    continue;

                case KW_U0:
                case KW_BOOL:
                case KW_I8:
                case KW_U8:
                case KW_I16:
                case KW_U16:
                case KW_I32:
                case KW_U32:
                case KW_I64:
                case KW_U64:
                case KW_F32:
                case KW_F64:
                case KW_AUTO:
                    cctrlTokenRewind(cc);
                    base_type = parseBaseDeclSpec(cc);
                    type = parsePointerType(cc,base_type);
                    break;

                case KW_IF:
                    cc->tmp_locals = listNew();
                    *is_global = 1;
                    return parseIfStatement(cc);

                case KW_WHILE:
                    cc->tmp_locals = listNew();
                    *is_global = 1;
                    return parseWhileStatement(cc);

                case KW_DO:
                    cc->tmp_locals = listNew();
                    *is_global = 1;
                    return parseDoWhileStatement(cc);

                case KW_FOR:
                    cc->tmp_locals = listNew();
                    *is_global = 1;
                    return parseForStatement(cc);

                case KW_SIZEOF:
                case KW_ALIGNOF:
                case KW_TYPEOF:
                    /* Floating `sizeof(x);` / `typeof(x);` - in the
                     * REPL these are expressions to evaluate + echo,
                     * same as `2+2;`. */
                    if (cc->flags & CCTRL_REPL) {
                        cctrlTokenRewind(cc);
                        ast = parseExpr(cc,16);
                        cctrlTokenExpect(cc,';');
                        *is_global = 1;
                        return ast;
                    }
                    cctrlRaiseException(cc,"Unexpected floating keyword: %.*s",
                            tok->len,tok->start);

                default:
                    cctrlRaiseException(cc,"Unexpected floating keyword: %.*s",
                            tok->len,tok->start);
            }
        } else if (tok->tk_type == TK_IDENT &&
                   parseIsFallthrough(cc, tok, cctrlTokenPeek(cc))) {
            parseFallthrough(cc, tok);
        } else if (tok->tk_type == TK_IDENT) {
            /* top level function call, unless the name is a type: then
             * `i64 (*fp)(i64);` declares a function pointer. `fn` is a
             * contextual keyword (ktm syntax v0.2): old code uses it as
             * a plain identifier, so check whether THIS `fn` starts a
             * type before treating it as a call or a variable. */
            peek = cctrlTokenPeek(cc);
            int fn_is_type = 0;
            if (tokenIsFnIdent(tok)) {
                cctrlTokenRewind(cc);
                fn_is_type = parseFnStartsType(cc);
                cctrlTokenGet(cc);
            }
            if (!fn_is_type && tokenPunctIs(peek,'(') &&
                (mapGetLen(cc->global_env, tok->start, tok->len) ||
                 !cctrlTokIsKeyword(cc, tok))) {
                cctrlTokenGet(cc);
                ast = parseCallArguments(cc,tok->start,tok->len);
                cctrlTokenExpect(cc,';');
                *is_global = 1;
                return ast;
            } else if (!fn_is_type &&
                       (variable = mapGetLen(cc->global_env,tok->start, tok->len))) {
                cctrlTokenRewind(cc);
                ast = parseExpr(cc,16);
                cctrlTokenExpect(cc,';');
                *is_global = 1;
                return ast;
            } else {
                cctrlTokenRewind(cc);
                base_type = parseBaseDeclSpec(cc);
                if (base_type == NULL) {
                    cctrlRaiseException(cc,"Undefined type: %.*s",tok->len,tok->start);
                }
                type = parsePointerType(cc,base_type);
            }
        } else if (tok->tk_type == TK_CHAR_CONST) {
            ast = parseFloatingCharConst(cc,tok);
            *is_global = 1;
            return ast;
        } else if (tok->tk_type == TK_STR) {
            cctrlTokenRewind(cc);
            ast = parsePrintStatement(cc);
            *is_global = 1;
            return ast;
        } else if (tok->tk_type == TK_I64) {
            if (cc->flags & CCTRL_REPL) {
                /* REPL: `2+2;` is an expression to evaluate + echo. */
                cctrlTokenRewind(cc);
                ast = parseExpr(cc,16);
                cctrlTokenExpect(cc,';');
                *is_global = 1;
                return ast;
            }
            cctrlRaiseException(cc,"Floating integer constant '%ld' cannot be used in this context", tok->i64);
        } else if (tok->tk_type == TK_F64) {
            if (cc->flags & CCTRL_REPL) {
                cctrlTokenRewind(cc);
                ast = parseExpr(cc,16);
                cctrlTokenExpect(cc,';');
                *is_global = 1;
                return ast;
            }
            cctrlRaiseException(cc,"Floating float constant '%f' cannot be used in this context", tok->f64);
        } else if (tokenPunctIs(tok,'(') && parseGotParenFnType(cc)) {
            /* `(fn(i64) i64) *slot;`: a pointer to a function pointer */
            base_type = parseBaseDeclSpec(cc);
            type = parsePointerType(cc,base_type);
        } else if (tokenPunctIs(tok,TK_PLUS_PLUS) ||
                   tokenPunctIs(tok,TK_MINUS_MINUS))
        {
            /* `++x;` / `--x;` - a side-effecting statement, legal at
             * the top level in scripting mode. */
            cctrlTokenRewind(cc);
            ast = parseExpr(cc,16);
            cctrlTokenExpect(cc,';');
            *is_global = 1;
            return ast;
        } else if ((cc->flags & CCTRL_REPL) &&
                   (tokenPunctIs(tok,'(') || tokenPunctIs(tok,'-') ||
                    tokenPunctIs(tok,'+') || tokenPunctIs(tok,'~') ||
                    tokenPunctIs(tok,'!') || tokenPunctIs(tok,'*') ||
                    tokenPunctIs(tok,'&')))
        {
            /* REPL: unary/parenthesised expression at the top level. */
            cctrlTokenRewind(cc);
            ast = parseExpr(cc,16);
            cctrlTokenExpect(cc,';');
            *is_global = 1;
            return ast;
        }

        /* End of input after the type, `I64` or `static I64 *`. */
        name = cctrlTokenGetRequired(cc);
        cctrlCheckPrefixHidden(cc, name);
        parseCheckNotReserved(cc, name);
        /* An instance being parsed: `Vec<T>.push` / `max<T>(`, its
         * parameters bound; a function takes the instance's name */
        if (cc->inst && name->tk_type == TK_IDENT &&
            tokenPunctIs(cctrlTokenPeek(cc), '<')) {
            parseGenericSkipParams(cc);
            if (cc->inst->def->kind == GEN_FUNC) {
                name = lexemeCopy(name);
                name->start = cc->inst->name->data;
                name->len = cc->inst->name->len;
            }
        }
        /* `T User.name(...)`: a method */
        if (type && name->tk_type == TK_IDENT &&
            tokenPunctIs(cctrlTokenPeek(cc), '.'))
        {
            if (is_const) {
                cctrlRaiseException(cc,"const is only allowed on declarations");
            }
            cctrlTokenGet(cc);
            return parseMethodDef(cc, type, name, is_static);
        }
        /* A module's declaration is named `a.b.name` */
        if (cc->cur_module && name->tk_type == TK_IDENT) {
            char *qual = cctrlDeclName(cc, name->start, name->len);
            if (qual) {
                name = lexemeCopy(name);
                name->start = qual;
                name->len = strlen(qual);
                if (cc->decl_pub) setAdd(cc->pub_names, qual);
            }
        } else if (cc->decl_from_main && name->tk_type == TK_IDENT) {
            setAdd(cc->main_names, mprintf("%.*s", name->len, name->start));
        }
        parse_gvar_list_static = is_static;
        parse_gvar_list_const = is_const;

        /* A keyword after a type: `I64 class X`. Without a type (a
         * stray punct before `class`) it is reported below. */
        if (name->tk_type == TK_KEYWORD && type != NULL) {
            switch (name->i64) {
                case KW_CLASS:
                    if (!astIsIntType(type)) {
                        /* Reported without unwinding, and the class is
                         * defined as an ordinary one, so its uses don't
                         * cascade into more errors. */
                        AoStr *msg = cctrlMessagePrintF(cc, CCTRL_ERROR,
                                "Can only make intrinsic types from integer types, got %s",
                                astTypeToString(type));
                        cctrlDiagPush(cc, cctrlMakeDiag(cc, CCTRL_ERROR, msg, NULL));
                        type = NULL;
                    }
                    parseClassDef(cc,type);
                    cctrlTokenExpect(cc,';');
                    continue;
                case KW_CONST:
                    cctrlRaiseException(cc,"const is only allowed on declarations");
                default: {
                    cctrlRaiseException(cc,"%s can only prefix a class",lexemeToString(name));
                }
            }
        }

        /* Global function pointer `T (*name)(params)`: a global variable
         * of function type. parseParams needs a scope for any nested
         * function pointer params, as in parseAsmFunctionBinding. */
        int is_fnptr = 0;
        if (tokenPunctIs(name,'(') && type != NULL) {
            /* `T (*Name(params))(fn_params)`: a function returning a
             * function pointer, not a variable */
            int stars = 0;
            while (tokenPunctIs(cctrlTokenPeek(cc), '*')) {
                cctrlTokenGet(cc);
                stars++;
            }
            Lexeme *fn_name = cctrlTokenGet(cc);
            int is_function = stars > 0 && fn_name &&
                              fn_name->tk_type == TK_IDENT &&
                              tokenPunctIs(cctrlTokenPeek(cc), '(');
            if (!is_function) {
                /* a function pointer variable: give the tokens back */
                if (fn_name) cctrlTokenRewind(cc);
                for (int i = 0; i < stars; ++i) cctrlTokenRewind(cc);
            } else {
                if (is_const) {
                    cctrlRaiseException(cc,"const is only allowed on declarations");
                }
                Ast *fn = parseFunctionOrDefImpl(cc, type, fn_name->start,
                                                 fn_name->len, 0, stars);
                if (fn && is_static) fn->flags |= AST_FLAG_STATIC;
                if (fn) {
                    fn->line = fn_name->line;
                    fn->col = fn_name->col;
                }
                return fn;
            }
            is_fnptr = 1;
            Lexeme *fnptr_name = parseFnPtrDeclName(cc);
            char *fname;
            int flen;
            cc->localenv = cctrlCreateAstMap(cc->localenv);
            type = parseFunctionPointerType(cc,&fname,&flen,type);
            cc->localenv = NULL;
            name = fnptr_name;
        }

        if (name->tk_type != TK_IDENT) {
            /* A function pointer without a name was reported by
             * parseFunctionPointerType: skip the declaration. */
            if (is_fnptr) {
                cctrlTerminate(cc);
            }
            /* Point at `name`, not at the token after it (on the next
             * line for `I64 a, ;`). */
            cctrlTokenRewind(cc);
            cctrlRaiseException(cc,"Identifier expected: got %s",lexemeToString(name));
        }

        /* Every legitimate route to the declaration code below parsed a
         * type first. Reaching here without one means the input opened
         * with a punct we don't treat as a statement (`*p = ...;` at
         * the top level, say) - raise instead of dereferencing NULL. */
        if (type == NULL) {
            cctrlRaiseException(cc,
                    "Expected a type declaration before `%.*s`",
                    name->len, name->start);
        }

        type = parseArrayDimensions(cc,type);
        tok = cctrlTokenPeek(cc);

        /* A module's declarations-only pass: a global is declared (as
         * an extern) so every file of the module can use it; the full
         * pass defines it. Its initialiser is skipped. */
        if (cc->decls_only && !tokenPunctIs(tok, '(')) {
            int depth = 0;
            Lexeme *t = NULL;
            /* A const integer's value is known here already, so another
             * file (or a program using a prebuilt module) can use it in
             * a constant expression */
            s64 const_val = 0;
            int const_ok = 0;
            if (is_const && astIsIntType(type) && tokenPunctIs(tok, '=')) {
                cctrlTokenGet(cc);
                Ast *init = parseExpr(cc, 16);
                const_ok = init != NULL;
                if (init) const_val = evalIntConstExprOrErr(init, &const_ok);
                t = cctrlTokenGet(cc);
                if (!tokenPunctIs(t, ';') && !tokenPunctIs(t, ',')) {
                    cctrlRaiseException(cc, "Expected `;` after `%.*s`",
                                        name->len, name->start);
                }
            }
            while (!t && (t = cctrlTokenGet(cc)) != NULL) {
                if (tokenPunctIs(t,'(') || tokenPunctIs(t,'[') ||
                    tokenPunctIs(t,'{')) {
                    depth++;
                } else if (tokenPunctIs(t,')') || tokenPunctIs(t,']') ||
                           tokenPunctIs(t,'}')) {
                    depth--;
                } else if (depth == 0 && (tokenPunctIs(t,';') ||
                                          tokenPunctIs(t,','))) {
                    break;
                }
                t = NULL;
            }
            Ast *gvar = astGVar(type, name->start, name->len, 0);
            gvar->flags |= AST_FLAG_EXTERN;
            if (is_const) gvar->flags |= AST_FLAG_CONST;
            if (const_ok) {
                gvar->deref_symbol = const_val;
                gvar->flags |= AST_FLAG_CONST_VALUE;
            }
            Ast *decl = astDecl(gvar, NULL);
            decl->flags |= AST_FLAG_EXTERN;
            if (!is_static) {
                mapAdd(cc->global_env, gvar->gname->data, gvar);
            }
            if (t && tokenPunctIs(t, ',')) {
                cc->tmp_gvar_base_type = base_type;
            }
            return decl;
        }

        /* Global class/struct/union with an aggregate initialiser:
         *   Color Black = {0,0,0};
         * Route through parseVariableInitialiser like arrays do: its
         * parseVariableAssignment already lowers `{...}` into an ARRAY_INIT
         * declinit that the data emitters write out. The scalar `=` branch
         * below parses the RHS with parseExpr, which can't handle `{`.
         * (peek(1) is the token after `=`, i.e. the `{`.) */
        if (tokenPunctIs(tok,'=') &&
            (type->kind == AST_TYPE_CLASS || type->kind == AST_TYPE_UNION) &&
            !type->is_intrinsic &&
            tokenPunctIs(cctrlTokenPeekBy(cc,1),'{'))
        {
            variable = astGVar(type,name->start,name->len,is_static);
            parseProgramGlobal(cc, variable);
            if (is_const) variable->flags |= AST_FLAG_CONST;
            mapAdd(cc->global_env,variable->gname->data,variable);
            cc->tmp_gvar_decl = variable;
            ast = parseVariableInitialiser(cc,variable,
                                           PUNCT_TERM_COMMA|PUNCT_TERM_SEMI);
            parseGlobalDeclListNextPrev(cc,base_type);
            return ast;
        }

        if (tokenPunctIs(tok,'=') && type->kind != AST_TYPE_ARRAY) {
            variable = astGVar(type,name->start,name->len,is_static);
            parseProgramGlobal(cc, variable);
            Ast *ast_decl = astDecl(variable,NULL);

            listAppend(cc->ast_list,ast_decl);
            mapAdd(cc->global_env,variable->gname->data,variable);
            cc->tmp_gvar_decl = variable;

            /* `auto foo = <expr>;` at file scope: the variable's type
             * isn't known until the initialiser is parsed, and building
             * the assignment with an unresolved `auto` LHS makes the
             * type-checker reject it. Consume the `=`, parse just the
             * RHS, infer the type, then build the (now well-typed)
             * assignment by hand. */
            if (type->kind == AST_TYPE_AUTO) {
                cctrlTokenGet(cc);                  /* consume '=' */
                Ast *rhs = parseExpr(cc,16);
                if (!rhs || !rhs->type) {
                    cctrlRaiseException(cc,
                            "auto cannot be used without an initialiser");
                }
                if (is_const) variable->flags |= AST_FLAG_CONST;
                variable->type = rhs->type;
                if (rhs->kind == AST_STRING) {
                    ast_decl->declinit = rhs;
                    parseGlobalDeclListNext(cc,cctrlTokenGet(cc),base_type);
                    return ast_decl;
                }
                Ast *folded = parseConstGlobalInit(cc, variable, rhs);
                if (folded) {
                    ast_decl->declinit = folded;
                    parseGlobalDeclListNext(cc,cctrlTokenGet(cc),base_type);
                    return ast_decl;
                }
                int is_err = 0;
                Ast *assign = astBinaryOp(AST_BIN_OP_ASSIGN, variable,
                                          rhs, &is_err);
                *is_global = 1;
                parseGlobalDeclListNext(cc,cctrlTokenGet(cc),base_type);
                return assign;
            }

            Ast *ast_expr;
            if (is_fnptr) {
                /* The token before `=` is the `)` closing the params, not
                 * the name, so build the assignment from the RHS alone. */
                Lexeme *eq = cctrlTokenGet(cc);     /* consume '=' */
                int eq_line = eq->line, eq_col = eq->col;
                Ast *rhs = parseExpr(cc,16);
                parseResolveOverloadRef(cc, type, rhs);
                if (astFnPtrMismatch(type, rhs->type)) {
                    parseRaiseFnConvert(cc, type, rhs->type);
                }
                if (!astTypeCheck(type,rhs,AST_BIN_OP_ASSIGN)) {
                    typeCheckWarn(cc,eq_line,eq_col,variable,rhs);
                }
                int is_err = 0;
                ast_expr = astBinaryOp(AST_BIN_OP_ASSIGN,variable,rhs,&is_err);
                if (is_const) variable->flags |= AST_FLAG_CONST;
            } else {
                cctrlTokenRewind(cc);
                /* `variable` must not carry AST_FLAG_CONST yet: this
                 * re-parses `name = rhs` as a real assignment
                 * expression, and the LHS is `variable` itself. */
                ast_expr = parseExpr(cc,16); // parseVariableInitialiser(cc,variable,PUNCT_TERM_COMMA|PUNCT_TERM_SEMI);
                if (is_const) variable->flags |= AST_FLAG_CONST;
            }

            if (is_const && astIsIntType(variable->type)) {
                int const_ok = 1;
                s64 const_val = evalIntConstExprOrErr(ast_expr->right, &const_ok);
                if (const_ok) {
                    variable->deref_symbol = const_val;
                    variable->flags |= AST_FLAG_CONST_VALUE;
                }
            }

            Ast *folded = NULL;
            if (ast_expr->right->kind != AST_STRING) {
                folded = parseConstGlobalInit(cc, variable, ast_expr->right);
            }
            if (folded) {
                ast_decl->declinit = folded;
                parseGlobalDeclListNext(cc,cctrlTokenGet(cc),base_type);
                return ast_decl;
            } else if (ast_expr->right->kind != AST_STRING) {
                *is_global = 1;
            } else {
                ast_decl->declinit = ast_expr->right;
                parseGlobalDeclListNext(cc,cctrlTokenGet(cc),base_type);
                return ast_decl;
            }
            parseGlobalDeclListNext(cc,cctrlTokenGet(cc),base_type);
            return ast_expr;
        } else if (type->kind == AST_TYPE_ARRAY) {
            if (is_const && !tokenPunctIs(tok,'=')) {
                cctrlRaiseException(cc,"const '%.*s' needs an initializer",
                        name->len, name->start);
            }
            variable = astGVar(type,name->start,name->len,is_static);
            parseProgramGlobal(cc, variable);
            if (is_const) variable->flags |= AST_FLAG_CONST;
            mapAdd(cc->global_env,variable->gname->data,variable);
            cc->tmp_gvar_decl = variable;
            ast = parseVariableInitialiser(cc,variable,PUNCT_TERM_COMMA|PUNCT_TERM_SEMI);
            if (type->kind == AST_TYPE_AUTO) {
                parseAssignAuto(cc,ast);
            }
            parseGlobalDeclListNextPrev(cc,base_type);
            return ast;
        }

        if (tokenPunctIs(tok, '(')) {
            if (is_const) {
                cctrlRaiseException(cc,"const is only allowed on declarations");
            }
            Ast *fn = parseFunctionOrDef(cc,type,name->start,name->len,0);
            /* `static` on the prototype or the definition makes it local */
            if (fn && is_static) {
                fn->flags |= AST_FLAG_STATIC;
            }
            /* Anchor to the name token - covers prototypes too, and
             * corrects the in-function anchor (a token get+rewind
             * between name and call left the hint on the `(`). */
            if (fn) {
                fn->line = name->line;
                fn->col = name->col;
            }
            return fn;
        }

        if (tokenPunctIs(tok,';') || tokenPunctIs(tok, ',')) {
            if (is_const) {
                cctrlRaiseException(cc,"const '%.*s' needs an initializer",
                        name->len, name->start);
            }
            parseGlobalDeclListNext(cc,cctrlTokenGet(cc),base_type);
            variable = astGVar(type,name->start,name->len,is_static);
            parseProgramGlobal(cc, variable);
            mapAdd(cc->global_env,variable->gname->data,variable);
            return astDecl(variable,NULL);
        }
        cctrlRaiseException(cc,"Cannot handle '%s'",lexemeToString(tok));
    }
}

/* The module `libc` only declares the C library: `extern "c"` functions
 * and data (known by their C names, as an extern "c" of any module is), structs,
 * unions, enums and constants. Its code would be the program's, under
 * the name of C's. Checked in its full pass only. */
static void parseCheckCModuleItem(Cctrl *cc, Ast *ast) {
    if (!cc->cur_module || cc->decls_only || cc->replaying ||
        !cctrlInCModule(cc)) {
        return;
    }
    if (ast->kind == AST_EXTERN_FUNC || ast->kind == AST_ASM_FUNC_BIND) return;
    if (ast->kind == AST_DECL && ast->declvar &&
        (ast->declvar->flags & (AST_FLAG_CONST | AST_FLAG_EXTERN_C))) {
        return;
    }
    /* a constant with its value, as the REPL and the LSP read it */
    if (ast->kind == AST_BINOP && ast->left && ast->left->kind == AST_GVAR &&
        (ast->left->flags & AST_FLAG_CONST)) {
        return;
    }
    const char *what = "a statement";
    switch (ast->kind) {
        case AST_FUNC:      what = "a function with a body"; break;
        case AST_FUN_PROTO: what = "a ktm function's prototype"; break;
        case AST_DECL:
        case AST_GVAR:      what = "a variable"; break;
        default: break;
    }
    cctrlRaiseExceptionAt(cc, ast->line, ast->col, 1,
        "module libc only declares C: extern \"c\" functions and data, structs, "
        "unions, enums and constants, not %s", what);
}

/* M2: each module whose globals have run-time initialisers gets a
 * function `a.b._init` (its symbol `_K1a1b5_init`) that runs them once,
 * however often it is called. The program's entry calls the `_init` of
 * every module that has one, its own source's or a prebuilt one's (its
 * .ktmlib marker says `init`), in the order the modules were loaded (a
 * module's imports before it), before the program's own initialisers.
 * A -lib build only makes the functions: its importers call them. */
/* parse_tree_inits as one static function run once, whose call starts
 * the `_init` of every module the tree's library compiles */
static void parseTreeInits(Cctrl *cc) {
    Lexer *l = cc->lexer_;
    if (!parse_tree_inits || listEmpty(parse_tree_inits)) return;
    char *done_sym = "__ktmTreeInitDone";
    Ast *done = astGVar(ast_int_type, done_sym, strlen(done_sym), 1);
    listAppend(cc->ast_list, astDecl(done, NULL));
    List *stmts = listNew();
    int err = 0;
    listAppend(stmts, astIf(done, astReturn(NULL, ast_void_type), NULL));
    listAppend(stmts, astBinaryOp(AST_BIN_OP_ASSIGN, done, astI64Type(1), &err));
    listForEach(parse_tree_inits) listAppend(stmts, it->value);
    listAppend(stmts, astReturn(NULL, ast_void_type));
    char *sym = "__ktmTreeInit";
    AstType *fn_type = astMakeFunctionType(ast_void_type, astVecNew());
    Ast *fn = astFunction(fn_type, sym, strlen(sym), astVecNew(), astCompountStatement(stmts),
                          listNew(), 0);
    fn->flags |= AST_FLAG_STATIC;
    listAppend(cc->ast_list, fn);
    for (int i = 0; i < l->load_order_len; ++i) {
        LexModule *m = l->modules[l->load_order[i] - 1];
        if (m->prebuilt) continue;
        List *with = listNew();
        listAppend(with, astFunctionCall(ast_void_type, sym, strlen(sym), astVecNew()));
        if (m->init_stmts) listForEach(m->init_stmts) listAppend(with, it->value);
        m->init_stmts = with;
    }
    parse_tree_inits = NULL;
}

static void parseModuleInits(Cctrl *cc) {
    Lexer *l = cc->lexer_;
    if (!l || l->load_order_len == 0) return;
    int library = l->building_module || l->building_tree;
    if (l->building_tree) parseTreeInits(cc);
    List *calls = listNew();
    for (int i = 0; i < l->load_order_len; ++i) {
        LexModule *m = l->modules[l->load_order[i] - 1];
        if (!m->prebuilt && m->init_stmts && !listEmpty(m->init_stmts)) {
            AoStr *done_name = aoStrPrintf("%s.__initDone", m->path->data);
            char *done_sym = astModuleMangle(done_name->data, done_name->len);
            Ast *done = astGVar(ast_int_type, done_sym, strlen(done_sym), 1);
            listAppend(cc->ast_list, astDecl(done, NULL));

            List *stmts = listNew();
            listAppend(stmts, astIf(done, astReturn(NULL, ast_void_type), NULL));
            int err = 0;
            listAppend(stmts, astBinaryOp(AST_BIN_OP_ASSIGN, done, astI64Type(1), &err));
            listForEach(m->init_stmts) listAppend(stmts, it->value);
            listAppend(stmts, astReturn(NULL, ast_void_type));

            AoStr *init_name = aoStrPrintf("%s._init", m->path->data);
            char *sym = astModuleMangle(init_name->data, init_name->len);
            AstType *fn_type = astMakeFunctionType(ast_void_type, astVecNew());
            Ast *fn = astFunction(fn_type, sym, strlen(sym), astVecNew(),
                                  astCompountStatement(stmts),
                                  m->init_locals ? m->init_locals : listNew(), 0);
            /* the module's file, not the one parsed last (reports) */
            listForEach(m->init_stmts) {
                Ast *st = it->value;
                if (st && st->file_id) { fn->file_id = st->file_id; break; }
            }
            listAppend(cc->ast_list, fn);
            m->has_init = 1;
            char *real = realpath(m->dir->data, NULL);
            if (!cc->init_module_dirs) cc->init_module_dirs = setNew(8, &set_cstring_type);
            setAdd(cc->init_module_dirs, real ? real : mprintf("%s", m->dir->data));
        }
        if (m->has_init && !library) {
            AoStr *init_name = aoStrPrintf("%s._init", m->path->data);
            char *sym = astModuleMangle(init_name->data, init_name->len);
            listAppend(calls, astFunctionCall(ast_void_type, sym, strlen(sym),
                                              astVecNew()));
        }
    }
    if (listEmpty(calls)) return;
    /* before the program's own initialisers */
    listForEach(cc->initalisers) listAppend(calls, it->value);
    cc->initalisers = calls;
}

void parseToAst(Cctrl *cc) {
    Ast *ast;
    Lexeme *tok;
    int is_global = 0;

    /* The previous parse can leave these dangling: the loop below
     * only resets them when it keeps iterating, not when it breaks at
     * EOF. A stale tmp_locals is fatal for the next parse (the REPL) -
     * the first global statement would listMergeAppend (and free!) a
     * list that may already have been merged and recycled. */
    cc->tmp_locals = NULL;
    cc->localenv = NULL;
    cc->tmp_gvar_decl = NULL;
    cc->tmp_gvar_base_type = NULL;

    /* Top-level recovery point. cctrlRaiseException longjmps here
     * once a CctrlDiagnostic is queued; we wipe function-scoped state
     * and skip tokens until the next plausible decl boundary, then
     * loop. parseCompoundStatementInternal installs its own
     * (finer-grained) recovery point for statements inside a
     * function body and restores ours on return. */
    jmp_buf recovery;
    jmp_buf *prev_recovery = cc->current_recovery;
    cc->current_recovery = &recovery;

    while (1) {
        /* Snapshot the tails of the lists a top-level decl can
         * append to. Mirrors the per-statement snapshot in
         * parseCompoundStatementInternal: if the decl longjmps
         * partway, we truncate any half-built additions so the
         * accumulated program list stays consistent. */
        List *volatile ast_tail = cc->ast_list ? cc->ast_list->prev : NULL;
        List *volatile init_tail = cc->initalisers ? cc->initalisers->prev
                                                   : NULL;
        List *volatile init_locals_tail = cc->initaliser_locals
                                          ? cc->initaliser_locals->prev : NULL;

        if (setjmp(recovery) != 0) {
            cc->overload_refs = NULL;
            cc->tmp_overload_set = NULL;
            cc->tmp_locals = NULL;
            cc->localenv = NULL;
            cc->tmp_func = NULL;
            cc->tmp_rettype = NULL;
            cc->tmp_loop_begin = NULL;
            cc->tmp_loop_end = NULL;
            cc->tmp_gvar_base_type = NULL;
            /* The lists below roll back the half-built AST_DECL; the
             * variable's global_env entry must go with it, or later
             * statements can reference storage that no longer exists. */
            if (cc->tmp_gvar_decl) {
                mapRemove(cc->global_env, cc->tmp_gvar_decl->gname->data);
                cc->tmp_gvar_decl = NULL;
            }
            if (cc->ast_list && ast_tail) {
                ast_tail->next = cc->ast_list;
                cc->ast_list->prev = ast_tail;
            }
            if (cc->initalisers && init_tail) {
                init_tail->next = cc->initalisers;
                cc->initalisers->prev = init_tail;
            }
            if (cc->initaliser_locals && init_locals_tail) {
                init_locals_tail->next = cc->initaliser_locals;
                cc->initaliser_locals->prev = init_locals_tail;
            }
            cctrlSyncToplevel(cc);
            tok = cctrlTokenPeek(cc);
            if (!tok) break;
            continue;
        }

        Lexeme *item_first = cctrlTokenPeek(cc);
        s64 item_line = item_first ? item_first->line : 0;
        s64 item_col = item_first ? item_first->col : 0;
        ast = parseToplevelDef(cc, &is_global);
        cc->tmp_gvar_decl = NULL; /* decl completed - nothing to roll back */
        parseCheckOverloadRefs(cc);
        if (ast == NULL) break;
        parseCheckCModuleItem(cc, ast);
        /* The old file-scope code (ktm syntax v0.2 has none): a statement
         * there, not a global's initialiser; the REPL's input is that */
        if (is_global && !(cc->flags & CCTRL_REPL) &&
            !(ast->kind == AST_BINOP && ast->left && ast->left->kind == AST_GVAR) &&
            ast->kind != AST_DECL) {
            cctrlErrorAt(cc, item_line ? item_line : ast->line,
                item_line ? item_col : ast->col, 1,
                "code at file scope: put it in `main` (only declarations go there)");
        }
        if (is_global) {
            parseAddInitialiser(cc, ast);
            LexModule *m = parseInitModule(cc);
            if (!listEmpty(cc->tmp_locals)) {
                if (m) {
                    if (!m->init_locals) m->init_locals = listNew();
                    listMergeAppend(m->init_locals, cc->tmp_locals);
                } else {
                    listMergeAppend(cc->initaliser_locals,cc->tmp_locals);
                }
            }
        } else if (ast->kind != AST_FUN_PROTO) {
            listAppend(cc->ast_list,ast);
        }
        is_global = 0;
        tok = cctrlTokenPeek(cc);
        /* A dangling `T a,` at EOF: let parseToplevelDef report it */
        if (!tok && !cc->tmp_gvar_base_type) break;
        cc->tmp_locals = NULL;
        cc->localenv = NULL;
    }

    cc->current_recovery = prev_recovery;
    parseGenericFlush(cc);
    if (!(cc->flags & CCTRL_REPL)) parseModuleInits(cc);
}
