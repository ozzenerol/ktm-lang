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
#include "prslib.h"
#include "prsasm.h"
#include "prsutil.h"
#include "util.h"

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

/* Start the range-loop temporaries from `___tmp1___` again, for a second
 * parse of the same input (-transpile checks it with a compile first) */
void parseResetRangeLoopIdx(void) {
    range_loop_idx = 0;
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
 * type - or `type` takes no list: a scalar, an intrinsic class, an
 * unsized array. */
static s64 parseDeclElidedCount(AstType *type, Ast *init) {
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
        if (tokenPunctIs(tok_name,'}')) {
            break;
        } else if (cctrlTokIsKeyword(cc, tok_name)) {
            base_type = parseBaseDeclSpec(cc);
        } else if (tokenIsFnIdent(tok_name) && parseFnLooksLikeType(cc)) {
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
                            cctrlRaiseException(cc,"%s %s has no fields, expected a body or a field name in class %s",
                                    is_union ? "union" : "class",
                                    nested->clsname ? nested->clsname->data : "<anonymous>",
                                    cls_str);
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
                    cctrlRaiseException(cc,"Unexpected keyword: `%.*s` while parsing class %s",
                            tok_name->len,
                            tok_name->start,
                            cls_str);
            }
        } else {
            cctrlRaiseException(cc,"Unexpected type `%.*s` while parsing class %s",
                    tok_name->len, tok_name->start, cls_str);
        }

        while (1) {
            next_type = parsePointerType(cc,base_type);
            tok_name = cctrlTokenGet(cc);
            if (!tok_name) {
                /* Nothing left to recover into; report it once. */
                cc->current_recovery = outer_recovery;
                cctrlRaiseException(cc, "Unexpected end of input while parsing class %s",
                        cls_str);
            }
            if (tok_name->tk_type != TK_IDENT && !tokenPunctIs(tok_name, '(')) {
                /* Back onto the bad token so the diagnostic points at it
                 * and the recovery skips from there. */
                cctrlTokenRewind(cc);
                cctrlRaiseException(cc, "Unexpected character `%.*s` while parsing class %s",
                        tok_name->len,tok_name->start,cls_str);
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
                cctrlRaiseException(cc, "Unexpected end of input while parsing class %s",
                        cls_str);
            } else {
                cctrlTokenRewind(cc);
                cctrlRaiseException(cc, "Unexpected token '%.*s' while parsing class %s, perhaps you meant to terminate the field with `;`?",
                        tok->len, tok->start, cls_str);
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
        if (cc->flags & CCTRL_SAVE_ANONYMOUS) {
            mapAdd(fields_dict,astAnnonymousLabel(),base_class);
        }
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
            if (cc->flags & CCTRL_SAVE_ANONYMOUS) {
                mapAdd(fields_dict,astAnnonymousLabel(),field);
            }
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
            if (cc->flags & CCTRL_SAVE_ANONYMOUS) {
                mapAdd(fields_dict,astAnnonymousLabel(),field);
            }
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
    cc->localenv = cctrlCreateAstMap(cc->localenv);

    if (tok == NULL) {
        cctrlRaiseException(cc,
                "Unexpected end of input in class/union definition");
    }
    /* Provenance of the tag token, for jump-to-definition. */
    int tag_line = 0, tag_col = 0;
    u32 tag_file = 0;
    if (tok->tk_type == TK_IDENT) {
        /* A module's type is named `a.b.Name` */
        char *qual = cctrlQualifyName(cc, tok->start, tok->len);
        tag = qual ? aoStrDupRaw(qual, strlen(qual))
                   : aoStrDupRaw(tok->start,tok->len);
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
    if (intrinsic_base) ref->issigned = intrinsic_base->issigned;
    if (!is_class) ref->kind = AST_TYPE_UNION;
    ref->line = tag_line;
    ref->col = tag_col;
    ref->file_id = tag_file;
    ref->in_std = !cc->cur_module && !cc->decl_from_main;
    if (tag) {
        mapAdd(env,tag->data,ref);
    }
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
    char *qual = cctrlQualifyName(cc, tok->start, tok->len);
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
        /* AST_TYPE_FUNC: a function pointer, `I64 (*fp)(I64) = &Fn`.
         * The transpiler keeps them as stores: it would print the folded
         * `(U8 *)&arr + 16` as C's `&arr + 16`. */
        Ast *target = NULL;
        if (cc->flags & CCTRL_TRANSPILING) return NULL;
        s64 off = 0;
        if (astIsIntType(rhs->type)) {
            /* `U8 *p = NULL`, `0x1000` */
            s64 v = evalIntConstExprOrErr(rhs, &ok);
            if (!ok) return NULL;
            lit = astI64Type(v);
        } else if (parseAddrConst(rhs, &target, &off)) {
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
        listAppend(cc->initalisers, assign);
    }
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

    if (var->type->kind == AST_TYPE_AUTO) {
        var->type = init->type;
    }

    /* A value the `=` operator rejects (`I64 z = p` with a class `p`) is
     * the same error as the assignment `z = p`, and the only diagnostic;
     * one it accepts with a different type gets the warning. */
    int is_err = 0;
    if (!(cc->flags & CCTRL_TRANSPILING) && init->kind != AST_COMPOUND_STMT) {
        astBinaryOp(AST_BIN_OP_ASSIGN,var,init,&is_err);
    }
    if (is_err) {
        parseCreateBinaryOp(cc,AST_BIN_OP_ASSIGN,var,init);
    } else if (!astTypeCheck(var->type,init,AST_BIN_OP_ASSIGN)) {
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
        return parseVariableAssignment(cc,var,terminator_flags,
                                       tok->line,tok->col);
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
    cctrlCheckPrefixHidden(cc, varname);
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
static Ast *parseRequiredExpr(Cctrl *cc, char *what) {
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

static Ast *parseIfClause(Cctrl *cc, char *term_out) {
    Ast *clause;
    Lexeme *tok = cctrlTokenPeek(cc);
    if (tok && (cctrlTokIsKeyword(cc, tok) ||
                (tokenIsFnIdent(tok) && parseFnLooksLikeType(cc)))) {
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
    else if (tokenPunctIs(t, ')')) *term_out = ')';
    else {
        /* Point at `t`, not at the token after it */
        if (t) cctrlTokenRewind(cc);
        cctrlRaiseException(cc,
            "Expected ';' or ')' in `if (...)`, got `%.*s`",
            t ? t->len : 0, t ? t->start : "");
    }
    return clause;
}

Ast *parseIfStatement(Cctrl *cc) {
    cctrlTokenExpect(cc,'(');
    Lexeme open = parseConsumedToken(cc);

    /* C++17 `if (init; cond)`: zero or more `;`-separated init statements
     * followed by the condition. The init declarations and a
     * declaration-condition are scoped to the if (and its bodies), so we
     * open a child environment and, when present, desugar to a block. */
    cc->localenv = cctrlCreateAstMap(cc->localenv);
    List *pre = listNew();
    Ast *volatile cond_clause = NULL;
    ParseCondGuard guard;
    jmp_buf cond_recovery;
    parseCondGuardInit(cc, &guard, &open, 1);
    if (setjmp(cond_recovery) != 0) {
        /* Reported; the error stops the compile, parse on for the body */
        parseCondGuardRecover(cc, &guard);
        cond_clause = astI64Type(1);
    } else {
        parseCondGuardArm(cc, &guard, &cond_recovery);
        while (1) {
            char term = 0;
            Ast *clause = parseIfClause(cc, &term);
            if (term == ')') {
                if (!clause) {
                    /* `if ()`: the `)` was the last token read */
                    cctrlRaiseException(cc,
                            "Expected the `if` condition, got `)`");
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

    Lexeme *peek = cctrlTokenPeek(cc);
    if (peek == NULL) {
        cctrlRaiseException(cc, "Unexpected end of input");
    }
    if (!parseValidPostControlFlowToken(peek)) {
        cctrlRaiseException(cc,"Unexpected %s `%s` while parsing if body",
                lexemeTypeToString(peek->tk_type), lexemeAsWritten(peek));
    }

    Ast *then = parseStatement(cc);
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
        els = parseStatement(cc);
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
static Ast *parseRangeLoopBody(Cctrl *cc, Ast *iterator) {
    Ast *forbody = parseStatement(cc);
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

    cctrlTokenExpect(cc,')');
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
    cctrlTokenExpect(cc,')');
    Ast *forbody = parseRangeLoopBody(cc,iterator);
    return astFor(counter,cond,step,forbody,NULL,NULL,NULL);
}

Ast *parseRangeLoop(Cctrl *cc, Ast *iteratee) {
    cctrlTokenGet(cc);
    Ast *container = parseExpr(cc,16);

    if (container->kind == AST_LVAR && (container->flags & AST_FLAG_VARARGS)) {
        /* `for (auto v : nums)` over a `T ...nums` parameter */
        AoStr *len_name = aoStrPrintf("%s#len", container->lname->data);
        Ast *count = cctrlGetVar(cc, len_name->data, len_name->len);
        if (iteratee->type->kind == AST_TYPE_AUTO) {
            iteratee->type = container->type->ptr;
        }
        return parseCreateForRange(cc, iteratee, count, container);
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
        (tokenIsFnIdent(tok) && parseFnLooksLikeType(cc))) {
        AstType *type = parseDeclSpec(cc);
        tok = cctrlTokenGetRequired(cc);
        if (tok->tk_type != TK_IDENT) {
            cctrlRaiseException(cc,"expected Identifier got: %s",
                    lexemeToString(tok));
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
            if (cc->tmp_locals) {
                listAppend(cc->tmp_locals, init_var);
            }
            Ast *ast = parseVariableInitialiser(cc,init_var,PUNCT_TERM_SEMI|PUNCT_TERM_COMMA);
            if (type->kind == AST_TYPE_AUTO) {
                parseAssignAuto(cc,ast);
            }
            if (is_const && ast->kind == AST_DECL && ast->declinit &&
                astIsIntType(init_var->type))
            {
                int const_ok = 1;
                s64 const_val = evalIntConstExprOrErr(ast->declinit, &const_ok);
                if (const_ok) {
                    init_var->deref_symbol = const_val;
                    init_var->flags |= AST_FLAG_CONST_VALUE;
                }
            }
            return ast;
        } else if (is_const) {
            cctrlRaiseException(cc,"const '%s' needs an initializer",
                    init_var->lname->data);
        }
    } else if (is_const) {
        cctrlRaiseException(cc,"const is only allowed on declarations");
    }
    return parseStatement(cc);
}

Ast *parseForStatement(Cctrl *cc) {
    Ast *volatile forinit, *volatile forcond, *volatile forstep;
    Ast *forbody;
    AoStr *for_begin, *for_end, *for_middle,
          *prev_begin, *prev_end;
    cctrlTokenExpect(cc,'(');

    prev_begin = cc->tmp_loop_begin;
    prev_end = cc->tmp_loop_end;

    for_begin = astMakeLabel();
    for_middle = astMakeLabel();
    for_end = astMakeLabel();

    cc->tmp_loop_begin = for_middle;
    cc->tmp_loop_end = for_end;

    cc->localenv = cctrlCreateAstMap(cc->localenv);
    Lexeme open = parseConsumedToken(cc);
    ParseCondGuard guard;
    jmp_buf cond_recovery;
    parseCondGuardInit(cc, &guard, &open, 1);
    if (setjmp(cond_recovery) != 0) {
        parseCondGuardRecover(cc, &guard);
        forinit = forcond = forstep = NULL;
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
            return forinit;
        }

        forcond = parseOptExpr(cc);
        if (tokenPunctIs(cctrlTokenPeek(cc), ')')) {
            forstep = NULL;
        } else {
            forstep = parseExpr(cc,16);
        }
        cctrlTokenExpect(cc,')');
        parseCondGuardDone(cc, &guard);
    }

    Lexeme *peek = cctrlTokenPeek(cc);
    if (peek == NULL) {
        cctrlRaiseException(cc, "Unexpected end of input");
    }
    if (!parseValidPostControlFlowToken(peek)) {
        cctrlRaiseException(cc,"Unexpected %s `%s` while parsing for loop body", 
                lexemeTypeToString(peek->tk_type), lexemeAsWritten(peek));
    }
    forbody = parseStatement(cc);
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
    cctrlTokenExpect(cc,'(');

    prev_begin = cc->tmp_loop_begin;
    prev_end = cc->tmp_loop_end;

    while_begin = astMakeLabel();
    while_end = astMakeLabel();
    cc->tmp_loop_begin = while_begin;
    cc->tmp_loop_end = while_end;

    cc->localenv = cctrlCreateAstMap(cc->localenv);
    Lexeme open = parseConsumedToken(cc);
    ParseCondGuard guard;
    jmp_buf cond_recovery;
    parseCondGuardInit(cc, &guard, &open, 0);
    if (setjmp(cond_recovery) != 0) {
        parseCondGuardRecover(cc, &guard);
        whilecond = astI64Type(0);
    } else {
        parseCondGuardArm(cc, &guard, &cond_recovery);
        whilecond = parseRequiredExpr(cc, "the `while` condition");
        cctrlTokenExpect(cc,')');
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

    whilebody = parseStatement(cc);
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


    whilebody = parseStatement(cc);
    
    tok = cctrlTokenGetRequired(cc);

    if (tok->tk_type != TK_KEYWORD || tok->i64 != KW_WHILE) {
        cctrlTokenRewind(cc);
        cctrlRaiseException(cc,"expected `while` after do block, got `%s`",
                lexemeAsWritten(tok));
    }

    cctrlTokenExpect(cc, '(');
    Lexeme open = parseConsumedToken(cc);
    ParseCondGuard guard;
    jmp_buf cond_recovery;
    parseCondGuardInit(cc, &guard, &open, 0);
    if (setjmp(cond_recovery) != 0) {
        parseCondGuardRecover(cc, &guard);
        whilecond = astI64Type(0);
    } else {
        parseCondGuardArm(cc, &guard, &cond_recovery);
        whilecond = parseRequiredExpr(cc, "the `while` condition");
        cctrlTokenExpect(cc,')');
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
    cctrlTokenExpect(cc,';');
    cctrlTokenRewind(cc);
    Lexeme semi_tok = *cctrlTokenGet(cc);
    /* A best attempt at trying to get the return type of a function */
    if (cc->tmp_rettype->kind == AST_TYPE_AUTO) {
        cc->tmp_rettype = parseReturnAuto(cc,retval);
    }

    Ast *maybe_fn = findFunctionDecl(cc,cc->tmp_fname->data,cc->tmp_fname->len);

    if (retval) check = retval->type;
    else        check = ast_void_type;

    if (check->kind == AST_TYPE_VOID && maybe_fn->type->rettype->kind == AST_TYPE_VOID) {
        if (maybe_fn->flags & AST_FLAG_INLINE && !(cc->flags & CCTRL_TRANSPILING)) {
            /* `return Inc();`: keep the call, there is no value to set */
            return retval ? retval : astDecl(maybe_fn->inline_ret,NULL);
        }
        return astReturn(retval,cc->tmp_rettype);
    }

    AstType *ok = astTypeCheck(cc->tmp_rettype,retval,'\0');
    if (!ok && astIsEnumType(cc->tmp_rettype)) {
        cctrlRaiseExceptionAt(cc,ret_tok.line,ret_tok.col,ret_tok.len,
                "Cannot convert `%s` to enum `%s` without a cast",
                astTypeToString(check), cc->tmp_rettype->clsname->data);
    } else if (!ok) {
        Ast *func = mapGet(cc->global_env, cc->tmp_fname->data);
        typeCheckReturnTypeWarn(cc,func,check,retval,&ret_tok,&semi_tok);
    }
    if (maybe_fn->flags & AST_FLAG_INLINE && !(cc->flags & CCTRL_TRANSPILING)) {
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
    if (cc->flags & CCTRL_TRANSPILING) {
        cc->current_recovery = NULL;
        cctrlRaiseExceptionAt(cc, tline, tcol, tlen,
            "`catch (Error e)` is not supported with -transpile");
    }

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
    listAppend(stmts, parseCallWithAddrOf("KTM_ErrorTake", var));
    listAppend(stmts, astDefer(parseCallWithAddrOf("KTM_ErrorDrop", var)));
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
        if (!(cc->flags & CCTRL_TRANSPILING)) {
            List *stmts = listNew();
            listAppend(stmts, astFunctionCall(ast_void_type, "KTM_ErrorForget",
                                              15, astVecNew()));
            if (catch_body) listAppend(stmts, catch_body);
            catch_body = astCompountStatement(stmts);
        }
    }
    return astTry(try_body, catch_body);
}

/* The file a throw is in, as a string literal: the path it was compiled
 * from, as diagnostics show it. */
static Ast *parseThrowFile(Cctrl *cc) {
    char *path = "";
    if (cc->lexer_ && cc->lexer_->cur_file && cc->lexer_->cur_file->filename) {
        path = cc->lexer_->cur_file->filename->data;
    }
    AoStr *esc = aoStrNew();
    s64 real_len = 1;
    for (char *p = path; *p; ++p, ++real_len) {
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
    int open_line = cc->lineno, open_col = 0;
    cctrlTokenRewind(cc);
    Lexeme *open = cctrlTokenGet(cc);
    if (open) open_col = open->col;
    if (tokenPunctIs(cctrlTokenPeek(cc), ')')) {
        /* `throw();`: reported as a missing expression */
        parseRequiredExpr(cc, "the value to `throw`");
    }
    Ast *file = parseThrowFile(cc);
    Ast *code = NULL;
    Ast *call = parseThrowCall(cc, file, line, &code);
    if (call && (cc->flags & CCTRL_TRANSPILING)) {
        cc->current_recovery = NULL;
        cctrlRaiseExceptionAt(cc, open_line, open_col, 1,
            "`throw` with a message or an Error is not supported with -transpile");
    }
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

Ast *parseCaseLabel(Cctrl *cc, Lexeme *tok) {
    if (cc->tmp_case_list == NULL) {
        cctrlRaiseException(cc,"unexpected 'case' found");
    }
    Ast *case_, *prev, *case_expr = NULL;
    Lexeme *peek;
    AoStr *label;
    int begining,end;
    int ok = 1;

    peek = cctrlTokenPeek(cc);
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
        begining = evalIntConstExprOrErr(case_expr, &ok);
        if (!ok) {
            if (cc->flags & CCTRL_PASTE_DEFINES && case_expr->kind == AST_LVAR) {
                label = case_expr->lname;
            } else {
                parseRaiseCaseException(cc,case_expr);
            }
        } else {
            label = astMakeLabel();
        }
    }

    tok = cctrlTokenPeek(cc);

    /* We're not doing label to label for transpilation */
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

    if (!ok && cc->flags & CCTRL_PASTE_DEFINES) {
        case_ = astCase(label,begining,end,stmts);
        case_->type = ast_void_type;
    } else {
        case_ = astCase(label,begining,end,stmts);
        assertUniqueSwitchCaseLabels(cc,cc->tmp_case_list,case_);
    }

    vecPush(cc->tmp_case_list,case_);

    do {
        Ast *stmt = parseStatement(cc);
        if (!stmt) {
            /* `case 1: ;` - the empty statement */
            peek = cctrlTokenPeek(cc);
            if (!peek || tokenPunctIs(peek,'}')) break;
            continue;
        }
        if (stmt->kind != AST_CASE && stmt->kind != AST_DEFAULT) {
            listAppend(stmts,stmt);
        }
        if (stmt->kind == AST_COMPOUND_STMT || stmt->kind == AST_CASE ||
                stmt->kind == AST_DEFAULT || stmt->kind == AST_BREAK || stmt->kind == AST_RETURN
                || stmt->kind == AST_GOTO) break;
        peek = cctrlTokenPeek(cc);
   
        /* @Bug - Something is afoot, this ensures we don't go on forever
         * parsing if there isn't a break and the case is a fall through...
         * feels as though we could go on and just ommit the case. Which would
         * avoid the call to `astCasesCompress()`? */
        if (tokenPunctIs(peek,'}')) break;
    } while (1);

    return case_;
}

Ast *parseDefaultStatement(Cctrl *cc) {
    cctrlTokenExpect(cc,':');
    if (cc->tmp_default_case) {
        cctrlRaiseException(cc,"Duplicate default case");
    }
    Lexeme *peek;
    List *stmts = listNew();
    AoStr *default_label = astMakeLabel();
    /* set here so this is non-null for the next call to parseStatement */
    cc->tmp_default_case = astDefault(default_label,stmts);

    do {
        Ast *stmt = parseStatement(cc);
        if (!stmt) {
            /* `default: ;` - the empty statement */
            peek = cctrlTokenPeek(cc);
            if (!peek || tokenPunctIs(peek,'}')) break;
            continue;
        }
        if (stmt->kind != AST_CASE) {
            listAppend(stmts,stmt);
        }
        if (stmt->kind == AST_COMPOUND_STMT || stmt->kind == AST_CASE || stmt->kind == AST_BREAK || stmt->kind == AST_RETURN
                || stmt->kind == AST_GOTO) break;
        peek = cctrlTokenPeek(cc);
        if (tokenPunctIs(peek,'}')) break;
    } while (1);

    return cc->tmp_default_case;
}

Ast *parseSwitchStatement(Cctrl *cc) {
    Ast *volatile cond;
    Ast *tmp, *original_default_label;
    Lexeme *peek;
    Vec *original_cases;
    AoStr *end_label,*tmp_name,*original_break;
    volatile int switch_bounds_checked = 1;
    volatile char terminating_char = ')';

    peek = cctrlTokenPeek(cc);

    if (!tokenPunctIs(peek,'[') && !tokenPunctIs(peek, '(')) {
        cctrlRaiseException(cc,"Switch '(' or '[' expected got: %s",
                lexemeToString(peek));
    }

    /* Walk past and set the expected terminting character */
    if (peek->i64 == '[') {
        switch_bounds_checked = 0;
        terminating_char = ']';
    }

    cctrlTokenGet(cc);
    Lexeme open = parseConsumedToken(cc);
    ParseCondGuard guard;
    jmp_buf cond_recovery;
    parseCondGuardInit(cc, &guard, &open, 0);
    if (setjmp(cond_recovery) != 0) {
        parseCondGuardRecover(cc, &guard);
        cond = astI64Type(0);
    } else {
        parseCondGuardArm(cc, &guard, &cond_recovery);
        cond = parseRequiredExpr(cc, "the `switch` value");
        if (!astIsIntOrIntrinsic(cond->type)) {
            cctrlRaiseException(cc,"Switch can only have int's at this time");
        }
        cctrlTokenExpect(cc,terminating_char);
        parseCondGuardDone(cc, &guard);
    }

    original_break = cc->tmp_loop_end;
    original_default_label = cc->tmp_default_case;
    original_cases = cc->tmp_case_list;

    cc->tmp_case_list = astVecNew();
    cc->tmp_default_case = NULL;

    end_label = astMakeLabel();

    /* this is the current label */
    cc->tmp_loop_end = end_label;

    tmp_name = astMakeTmpName();

    tmp = astLVar(cond->type, tmp_name->data, tmp_name->len);
    listAppend(cc->tmp_locals,tmp);

    parseStatement(cc);
    aoStrRelease(tmp_name);

    Ast *switch_ast = astSwitch(
        cond,
        cc->tmp_case_list,
        cc->tmp_default_case,
        end_label,
        switch_bounds_checked
    );

    cc->tmp_loop_end = original_break;
    cc->tmp_case_list = original_cases;
    cc->tmp_default_case = original_default_label;
    return switch_ast;
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
            case KW_SWITCH:   return parseSwitchStatement(cc);
            case KW_CASE:     return parseCaseLabel(cc,tok);
            case KW_DEFAULT:  return parseDefaultStatement(cc);
            case KW_BREAK:    return parseBreakStatement(cc);
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
        return parseFloatingCharConst(cc,tok);
    }

    if (tok->tk_type == TK_STR) {
        cctrlTokenRewind(cc);
        /* HACK in ktm printf */
        return parsePrintStatement(cc);
    }

    /* Hacked in goto label ;) */
    peek = cctrlTokenPeek(cc);
    if (tok->tk_type == TK_IDENT && tokenPunctIs(peek,':')) {
        if (cc->defer_depth) {
            cctrlRaiseExceptionAt(cc, tok->line, tok->col, tok->len,
                "a label can't be inside a deferred statement");
        }
        label = createFunctionLevelGotoLabel(cc,tok);
        ret = astLabel(label);
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
    if (cc->flags & CCTRL_TRANSPILING) {
        /* The transpiler doesn't print recovered errors: fail now */
        cc->current_recovery = NULL;
        cctrlRaiseExceptionAt(cc, line, col, len,
            "`defer` is not supported with -transpile");
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

Ast *parseDeclOrStatement(Cctrl *cc) {
    Lexeme *tok = cctrlTokenPeek(cc);
    if (!tok) {
        return NULL;
    }
    if ((cctrlTokIsKeyword(cc, tok) &&
         !parseTypeNameFollowedByDot(cc)) ||
        (tok->tk_type == TK_KEYWORD && tok->i64 == KW_CONST) ||
        (tokenIsFnIdent(tok) && parseFnLooksLikeType(cc))) {
        return parseDecl(cc);
    }
    return parseStatement(cc);
}

void parseCompoundStatementInternal(Cctrl *cc, Ast *body) {
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

    while (tok && !tokenPunctIs(tok, '}')) {
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

        if (tok->tk_type == TK_KEYWORD && tok->i64 == KW_DEFER) {
            listAppend(body->stms, parseDeferStatement(cc));
            tok = cctrlTokenPeek(cc);
            continue;
        }

        is_const = 0;
        if (tok->tk_type == TK_KEYWORD && tok->i64 == KW_CONST) {
            is_const = 1;
            cctrlTokenGet(cc);
            tok = cctrlTokenPeek(cc);
        }

        if ((cctrlTokIsKeyword(cc, tok) &&
             !parseTypeNameFollowedByDot(cc)) ||
            (tokenIsFnIdent(tok) && parseFnLooksLikeType(cc))) {
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
                    cctrlCheckPrefixHidden(cc, varname);
                    if (varname->tk_type != TK_IDENT) {
                        cctrlTokenRewind(cc);
                        cctrlRaiseException(cc,"Expected type declaration with identifer got '%.*s' - should be"ESC_BLUE" '%s "ESC_RESET ESC_BOLD"<var_name>'",
                                peek->len, peek->start, astTypeToString(next_type));
                        break;
                    }
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
            if ((stmt = parseStatement(cc)) != NULL) {
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
    cctrlTokenExpect(cc,'}');
    cctrlTokenPeek(cc);
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
    /* `while(1)` / `for(;1;)` / `for(;;)` style infinite loops. */
    return c && c->kind == AST_LITERAL && c->type &&
           c->type->kind == AST_TYPE_INT && c->i64 != 0;
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
            listAppend(cc->asm_blocks, asm_block);

            Ast *asm_func = astAsmFunctionBind(
                    astMakeFunctionType(rettype, params),
                    asm_fname,asm_fname,params);

            /* REPL/LSP: a reparse redefines; shadow rather than abort. */
            if (!mapAddOrErr(cc->asm_functions, asm_fname->data, asm_func)) {
                if (cc->flags & CCTRL_REPL) {
                    mapRemove(cc->asm_functions, asm_fname->data);
                    mapAdd(cc->asm_functions, asm_fname->data, asm_func);
                } else {
                    cctrlIce(cc, "Already defined assembly function: %s", asm_fname->data);
                }
            }

            if (!mapAddOrErr(cc->global_env, fname_duped->data, asm_func)) {
                if (cc->flags & CCTRL_REPL) {
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

    /* Upgrade a prototype to an actual function */
    func = mapGetLen(cc->global_env, fname, len);
    if (!func) {
        cc->tmp_params = params;
        cc->tmp_rettype = rettype;
        fn_type = astMakeFunctionType(cc->tmp_rettype, params);
        func = astFunction(fn_type,fname,len,params,NULL,locals,
                has_var_args);
        mapAdd(cc->global_env, func->fname->data, func);
    } else {
        switch (func->kind) {
            case AST_EXTERN_FUNC:
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
                    func = astFunction(fn_type,fname,len,params,NULL,locals,
                            has_var_args);
                    mapAdd(cc->global_env, func->fname->data, func);
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
    parseCompoundStatementInternal(cc, func_body);
    parseCheckGotoIntoTry(cc, func_body);
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
     * and `asm { }` bodies are opaque, so neither is checked. */
    AstType *rt = func->type ? func->type->rettype : NULL;
    if (rt && rt->kind != AST_TYPE_VOID && !(func->flags & AST_FLAG_INLINE) &&
        !has_asm && astStmtFallsThrough(func_body))
    {
        cctrlWarningAt(cc, name_line, name_col, len,
            "control may reach the end of non-void function '%s' "
            "without returning a value",
            astModuleDemangle(fname, len));
    }

    cc->localenv = NULL;
    cc->tmp_locals = NULL;
    cc->tmp_rettype = NULL;
    cc->tmp_params = NULL;
    cc->tmp_fname = NULL;
    cc->tmp_func = prev_func;
    return func;
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
    char *qual = cctrlQualifyName(cc, fname, len);
    if (qual) {
        mapAdd(cc->global_env, qual, func);
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

/* Do two declarations of a function agree on a type? Integers match
 * by size and signedness (an `I64 class` like CDate is its integer),
 * classes and unions by name, an array parameter is its pointer and
 * `auto` matches anything. */
static int parseFnTypesMatch(AstType *a, AstType *b) {
    if (a == b) return 1;
    if (!a || !b) return 0;
    if (a->kind == AST_TYPE_AUTO || b->kind == AST_TYPE_AUTO) return 1;
    if (a->kind == AST_TYPE_ARRAY) a = astMakePointerType(a->ptr);
    if (b->kind == AST_TYPE_ARRAY) b = astMakePointerType(b->ptr);

    int a_int = a->kind == AST_TYPE_INT || a->kind == AST_TYPE_CHAR;
    int b_int = b->kind == AST_TYPE_INT || b->kind == AST_TYPE_CHAR;
    if ((a_int || astIsIntrinsicClass(a)) && (b_int || astIsIntrinsicClass(b))) {
        if (a->size != b->size) return 0;
        return !a_int || !b_int || a->issigned == b->issigned;
    }
    if (a->kind != b->kind) return 0;

    switch (a->kind) {
        case AST_TYPE_FLOAT:
            return a->size == b->size;
        case AST_TYPE_POINTER:
            return parseFnTypesMatch(a->ptr, b->ptr);
        case AST_TYPE_CLASS:
        case AST_TYPE_UNION:
            if (!a->clsname || !b->clsname) return 0;
            return aoStrCmp(a->clsname, b->clsname);
        case AST_TYPE_FUNC: {
            if (!parseFnTypesMatch(a->rettype, b->rettype)) return 0;
            u64 na = a->params ? a->params->size : 0;
            u64 nb = b->params ? b->params->size : 0;
            if (na != nb) return 0;
            for (u64 i = 0; i < na; ++i) {
                Ast *pa = a->params->entries[i];
                Ast *pb = b->params->entries[i];
                if (!pa || !pb) return pa == pb;
                if ((pa->kind == AST_VAR_ARGS) != (pb->kind == AST_VAR_ARGS)) {
                    return 0;
                }
                if ((pa->flags & AST_FLAG_VARARGS) != (pb->flags & AST_FLAG_VARARGS)) {
                    return 0;
                }
                if (pa->kind == AST_VAR_ARGS) continue;
                if (!parseFnTypesMatch(pa->type, pb->type)) return 0;
            }
            return 1;
        }
        default:
            return 1;
    }
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
    if (parseFnTypesMatch(type, prev->type) &&
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
    if (!parseFnTypesMatch(type, prev->type) ||
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
    if (is_lower_main) {
        Ast *other = mapGetLen(cc->global_env, "Main", 4);
        if (other && other->kind == AST_FUNC) {
            cctrlRaiseException(cc, "both `main` and `Main` are defined: "
                                    "a program has one entry point");
        }
        fname = "Main";
    }
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
    if (tokenPunctIs(tok, '{') || tokenPunctIs(tok, ';')) {
        parseCheckFunctionRedeclaration(cc, rettype, fname, len, params,
                                        has_var_args, name_tok_line,
                                        name_tok_col);
        parseInheritDefaultParams(cc, rettype, fname, len, params,
                                  has_var_args);
    }
    if (tokenPunctIs(tok, '{') && !skipped_body) {
        Ast *fn = parseFunctionDef(cc,rettype,fname,len,params,
                                   has_var_args,is_inline,
                                   name_tok_line,name_tok_col);
        if (fn) {
            fn->line = name_line;
            fn->col = name_col;
            fn->file_id = name_file;
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
        mapAdd(cc->global_env,fn->fname->data,fn);
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
    /* Map a c function to an ASM function */
    mapAdd(cc->asm_funcs,c_fname->data,asm_func);
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

/* `T User.name(params)`, a method of the class or union `User`: a
 * definition or a prototype. `type_tok` is `User`, the `.` was taken. It
 * is the function `User.name` with a `User *self` first parameter, and
 * only the module (or the standard library) defining `User` can add it. */
static Ast *parseMethodDef(Cctrl *cc, AstType *rettype, Lexeme *type_tok,
                           int is_static) {
    char *tname = type_tok->start;
    int tlen = type_tok->len;
    AstType *cls = NULL;
    if (cc->flags & CCTRL_TRANSPILING) {
        cc->current_recovery = NULL;
        cctrlRaiseException(cc, "methods are not supported with -transpile");
    }
    char *qual = cctrlQualifyName(cc, tname, tlen);
    if (qual) cls = mapGet(cc->clsdefs, qual);
    if (!cls) cls = mapGetLen(cc->clsdefs, tname, tlen);
    if (!cls) cls = mapGetLen(cc->uniondefs, tname, tlen);
    if (!cls || cls->is_enum || cls->is_intrinsic ||
        (cls->kind != AST_TYPE_CLASS && cls->kind != AST_TYPE_UNION))
    {
        cctrlRewindUntilStrMatch(cc, tname, tlen, NULL);
        cctrlRaiseException(cc, "`%.*s` is not a struct: only a struct or "
                                "union can have methods", tlen, tname);
    }
    int here_std = !cc->cur_module && !cc->decl_from_main;
    AoStr *path = cctrlModulePath(cc, cc->cur_module);
    char *dot = NULL;
    for (int i = (int)cls->clsname->len - 1; i >= 0 && !dot; --i) {
        if (cls->clsname->data[i] == '.') dot = cls->clsname->data + i;
    }
    int mine = cls->in_std == here_std;
    if (dot) {
        int mod_len = dot - cls->clsname->data;
        mine = mine && path && (int)path->len == mod_len &&
               !memcmp(path->data, cls->clsname->data, mod_len);
    } else {
        mine = mine && !path;
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
        if (t->tk_type != TK_KEYWORD ||
            (t->i64 != KW_PUBLIC && t->i64 != KW_PRIVATE &&
             t->i64 != KW_STATIC && t->i64 != KW_ATOMIC)) break;
        i++;
    }
    Lexeme *kw = i < d->toks->size ? d->toks->entries[i] : NULL;
    if (kw && kw->tk_type == TK_KEYWORD &&
        (kw->i64 == KW_CLASS || kw->i64 == KW_UNION) &&
        i + 1 < d->toks->size &&
        ((Lexeme *)d->toks->entries[i + 1])->tk_type == TK_IDENT)
    {
        /* `[pub] struct Name;`: the name now, the body later */
        Vec *fwd = vecNew(&vec_unsigned_long_type);
        for (u64 j = 0; j <= i + 1; ++j) vecPush(fwd, d->toks->entries[j]);
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

Ast *parseToplevelDef(Cctrl *cc, int *is_global) {
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
        if (tok->mod_pass == LEX_PASS_DECLS && !cc->replaying &&
            !cc->tmp_gvar_base_type) {
            char *mod_key = mprintf("%u", tok->module_id);
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
        cc->decls_only = tok->mod_pass == LEX_PASS_DECLS;
        if (!cc->tmp_gvar_base_type) {
            cc->decl_from_main = tok->module_id == 0 && !tok->in_std;
        }

        /* An empty declaration: a stray `;` as in `I64 x = 1;;` or
         * `U0 F() {};`. Skip it, it would be taken for the type of the
         * next declaration and swallow it. */
        if (tokenPunctIs(tok, ';') && !cc->tmp_gvar_base_type) {
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
                        listAppend(cc->asm_blocks,asm_block);
                        return asm_block;
                    }
                    cctrlRaiseException(cc,"Floating \"asm\" keyword, expected \"asm {\" got - %.*s",
                            peek->len, peek->start);
                }

                case KW_EXTERN: {
                    tok = cctrlTokenGetRequired(cc);
                    if (tok->tk_type == TK_STR && !strncmp(tok->start,"c",1)) {
                        /* extern "c" func(...) - C-ABI function decl. */
                        type = parseDeclSpec(cc);
                        name = cctrlTokenGet(cc);
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
                    Ast *gvar = astGVar(type, name->start, name->len, 0);
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
                     * arguments. The transpiler keeps the flag so it can
                     * re-emit the keyword. */
                    int is_inline = !!(cc->flags & CCTRL_TRANSPILING);
                    ast = parseFunctionOrDef(cc,type,name->start,name->len,is_inline);
                    if (is_inline) {
                        ast->flags |= AST_FLAG_INLINE;
                    }
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
                case KW_ATOMIC:
                    continue;

                case KW_CONST:
                    is_const = 1;
                    continue;

                case KW_DEFER:
                    cctrlRaiseExceptionAt(cc, tok->line, tok->col, tok->len,
                        "`defer` is only allowed inside a function");
                    cctrlTerminate(cc);

                case KW_STATIC:
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
                    continue;

                case KW_UNION:
                    parseUnionDef(cc);
                    cctrlTokenExpect(cc,';');
                    continue;

                case KW_ENUM:
                    parseEnumDef(cc);
                    cctrlTokenExpect(cc,';');
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
            char *qual = cctrlQualifyName(cc, name->start, name->len);
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
            mapAdd(cc->global_env,variable->gname->data,variable);
            return astDecl(variable,NULL);
        }
        cctrlRaiseException(cc,"Cannot handle '%s'",lexemeToString(tok));
    }
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

        ast = parseToplevelDef(cc, &is_global);
        cc->tmp_gvar_decl = NULL; /* decl completed - nothing to roll back */
        if (ast == NULL) break;
        /* A module built into a library (`ktm -lib`) has no code that
         * runs before main: a run-time initialiser would never run */
        if (is_global && cc->cur_module && cc->lexer_ &&
            cc->lexer_->building_module) {
            is_global = 0;
            if (ast->kind == AST_BINOP && ast->left &&
                ast->left->kind == AST_GVAR) {
                char *name = strrchr(ast->left->gname->data, '.');
                cctrlRaiseExceptionAt(cc, ast->line, ast->col, 1,
                    "`%s` needs a constant initialiser in a module built with -lib "
                    "(its library has no code that runs before main)",
                    name ? name + 1 : ast->left->gname->data);
            } else {
                cctrlRaiseExceptionAt(cc, ast->line, ast->col, 1,
                    "a statement at the top of a module built with -lib would never run "
                    "(its library has no code that runs before main)");
            }
        }
        if (is_global) {
            listAppend(cc->initalisers,ast);
            if (!listEmpty(cc->tmp_locals)) {
                listMergeAppend(cc->initaliser_locals,cc->tmp_locals);
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
}
