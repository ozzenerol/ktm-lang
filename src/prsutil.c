#include <ctype.h>
#include <string.h>
#include "aostr.h"
#include "ast.h"
#include "cctrl.h"
#include "lexer.h"
#include "prsutil.h"
#include "util.h"

int align(int n, int m) {
    int rem = n % m;
    if (rem == 0) {
        return n;
    }
    return n - rem + m;
}

inline int parseIsFloatOrInt(Ast *ast) {
    return astIsIntType(ast->type) || 
           astIsFloatType(ast->type);
}

inline int parseIsClassOrUnion(int kind) {
    return kind == AST_TYPE_CLASS || 
           kind == AST_TYPE_UNION;
}

inline int parseIsFunction(Ast *ast) {
    if (ast) {
        switch (ast->kind) {
            case AST_FUNC:
            case AST_FUN_PROTO:
            case AST_ASM_FUNC_BIND:
            case AST_ASM_FUNCDEF:
            case AST_EXTERN_FUNC:
                return 1;
            default:
                return 0;
        }
    }
    return 0;
}

int parseIsFunctionCall(Ast *ast) {
    return ast && (ast->kind == AST_FUNCALL || 
           ast->kind == AST_FUNPTR_CALL || 
           ast->kind == AST_ASM_FUNCALL);
}

void assertIsFloat(Ast *ast, s64 lineno) {
    if (ast && !astIsFloatType(ast->type)) {
        loggerPanic("line %ld: Expected float type got %s\n",
                lineno,astTypeToString(ast->type));
    }
}

void assertIsInt(Ast *ast, s64 lineno) {
    if (ast && !astIsIntType(ast->type)) {
        loggerPanic("line %ld: Expected int type got %s\n",
                lineno, astTypeToString(ast->type));
    }
}

void assertIsFloatOrInt(Ast *ast, s64 lineno) {
    if (!parseIsFloatOrInt(ast)) {
        loggerPanic("line %ld: Expected float got %s\n",
                lineno, astTypeToString(ast->type));
    }
}

void assertIsPointer(Ast *ast, s64 lineno) {
    if (!ast || ast->type->kind != AST_TYPE_POINTER) {
        loggerPanic("line %ld: Expected float got %s\n",
                lineno,astTypeToString(ast->type));
    }
}

char *assertionTerminatorMessage(Cctrl *cc, Lexeme *tok,
                                 s64 terminator_flags)
{
    if ((terminator_flags & PUNCT_TERM_SEMI) &&
        (terminator_flags & PUNCT_TERM_COMMA)) {
        return "perhaps you meant ';' or ','?";
    } else if ((terminator_flags & PUNCT_TERM_SEMI)) {
        return "perhaps you meant ';' ?";
    } else if ((terminator_flags & PUNCT_TERM_COMMA)) {
        return "perhaps you meant ',' ?";
    } else if (terminator_flags & PUNCT_TERM_RPAREN) {
        return "perhaps you meant ')'";
    } else {
        cctrlIce(cc,"Expected terminating token with flags: 0x%lX, got: %s",
                terminator_flags, lexemeToString(tok));
    }
}

/* Check if one of the characters matches and the flag wants that character to
 * terminate */
void assertTokenIsTerminator(Cctrl *cc, Lexeme *tok, s64 terminator_flags) {
    if (tok == NULL) {
        cctrlRaiseException(cc,"Unexpected end of input");
    }

    if ((tok->i64 == ';' && (terminator_flags & PUNCT_TERM_SEMI)) ||
        (tok->i64 == ')' && (terminator_flags & PUNCT_TERM_RPAREN)) ||
        (tok->i64 == ',' && (terminator_flags & PUNCT_TERM_COMMA))) {
        return;
    }

    char *suggestion = assertionTerminatorMessage(cc, tok, terminator_flags);

    /* tok has already been consumed by the caller, so `peek` is
     * pointing one past it. Rewind once so the diagnostic
     * renderer sees `tok` as the current position - otherwise
     * cctrlMessagePrintF captures the *next* token's line/col and
     * the underline lands on the wrong thing. */
    cctrlTokenRewind(cc);
    AoStr *error_msg = cctrlMessagePrintF(cc,CCTRL_ERROR,
            "Unexpected %s `%s` %s",
            lexemeTypeToString(tok->tk_type),
            lexemeAsWritten(tok),
            suggestion);
    /* Positioned on tok too, while still rewound. */
    CctrlDiagnostic *err_d = cctrlMakeDiag(cc, CCTRL_ERROR, error_msg, NULL);
    /* Re-consume tok so the buffer head is back where the caller
     * left it before we start hunting for the hint. */
    cctrlTokenGet(cc);

    /* Build a follow-up note pointing at the *previous* line. The
     * statement that almost certainly forgot its terminator and let
     * the next statement collide with our parser. */
    CctrlDiagnostic *info_d = cctrlInfoAtPreviousLine(cc, tok,
            "perhaps a missing `;` at the end of this line?");

    /* Push diagnostics in source order: the error fires first
     * (rendered at tok's actual position), then the info hint. */ 
    cctrlDiagPush(cc, err_d);
    if (info_d) cctrlDiagPush(cc, info_d);
    cctrlTerminate(cc);
}

void assertTokenIsTerminatorWithMsg(Cctrl *cc, Lexeme *tok,
                                    s64 terminator_flags,
                                    const char *fmt, ...)
{
    if (tok == NULL) {
        cctrlRaiseException(cc,"Unexpected end of input");
    }
    if ((tok->i64 == ';' && (terminator_flags & PUNCT_TERM_SEMI)) ||
        (tok->i64 == ')' && (terminator_flags & PUNCT_TERM_RPAREN)) ||
        (tok->i64 == ',' && (terminator_flags & PUNCT_TERM_COMMA))) {
        return;
    }

    va_list ap;
    va_start(ap,fmt);
    char *msg = mprintVa(fmt, ap, NULL);
    va_end(ap);

    cctrlRewindUntilPunctMatch(cc,tok->i64,NULL);
    char *token_msg = assertionTerminatorMessage(cc,tok,terminator_flags);
    cctrlRaiseException(cc,"Unexpected %s `%s` %s - %s",
                        lexemeTypeToString(tok->tk_type),
                        lexemeAsWritten(tok),
                        msg,
                        token_msg);
}

AstType *parseGetType(Cctrl *cc, Lexeme *tok) {
    if (!tok) {
        return NULL;
    }
    if (tok->tk_type != TK_IDENT && tok->tk_type != TK_KEYWORD) {
        return NULL;
    }
    return cctrlGetKeyWord(cc,tok->start,tok->len);
}

int parseIsKeyword(Lexeme *tok, Cctrl *cc) {
    return cctrlTokIsKeyword(cc, tok) || NULL;
}

int evalClassRef(Ast *ast, int offset) {
    if (ast->kind == AST_CLASS_REF)
        return evalClassRef(ast->cls, ast->type->offset + offset);
    return evalIntConstExpr(ast) + offset;
}

int astIsArithmetic(Ast *ast, int is_float) {
    if (astIsBinOp(ast)) {
        switch (ast->binop) {
            case AST_BIN_OP_MUL:
            case AST_BIN_OP_DIV:
            case AST_BIN_OP_ADD:
            case AST_BIN_OP_SUB:
                return 1;
            case AST_BIN_OP_MOD:
            case AST_BIN_OP_SHL:
            case AST_BIN_OP_SHR:
            case AST_BIN_OP_BIT_AND:
            case AST_BIN_OP_BIT_XOR:
            case AST_BIN_OP_BIT_OR:
                return !is_float;
            default:
                return 0;
        }
    } 
    return 0;
}

int astCanEval(Ast *ast, int *_ok, int is_float) {
    if (ast->left && ast->right) {
        return astCanEval(ast->left, _ok,is_float) && 
               astCanEval(ast->right, _ok,is_float);
    } else if (astIsArithmetic(ast, is_float)) {
        return 1;
    } else if (ast->kind == AST_LITERAL && (astIsIntOrIntrinsic(ast->type) || astIsFloatType(ast->type))) {
        return 1;
    } else {
        *_ok = 0;
        return 0;
    }
}

/* Fold a postfix cast to an integer type the way it runs: keep the low
 * `size` bytes, sign- or zero-extended (`300(U8)` is 44, `200(I8)` -56).
 * Casts to pointers and to I64/U64 leave the value as is. */
static s64 evalIntCast(s64 value, AstType *to) {
    if (astIsIntType(to) && to->size > 0 && to->size < 8) {
        u64 mask = (1ULL << (to->size * 8)) - 1;
        value &= mask;
        if (to->issigned && (value & (s64)(mask ^ (mask >> 1)))) {
            value |= ~mask;
        }
    }
    return value;
}

static int evalIsTrue(Ast *ast, int *_ok);

double evalFloatExprOrErr(Ast *ast, int *_ok) {
#define eval evalFloatExprOrErr
    switch (ast->kind) {
        case AST_TERNARY:
            /* Only the chosen branch is evaluated, as at runtime. */
            return evalIsTrue(ast->cond, _ok) ? eval(ast->then, _ok)
                                              : eval(ast->els, _ok);
        case AST_CAST: {
            double value;
            if (!astIsFloatType(ast->type)) {
                value = (double)evalIntConstExprOrErr(ast, _ok);
            } else if (astIsFloatType(ast->operand->type)) {
                value = eval(ast->operand, _ok);
            } else {
                s64 i = evalIntConstExprOrErr(ast->operand, _ok);
                value = astIsU64Type(ast->operand->type) ? (double)(u64)i
                                                         : (double)i;
            }
            return ast->type->size == 4 ? (double)(float)value : value;
        }
        case AST_LITERAL: {
            if (astIsFloatType(ast->type)) {
                return ast->f64;
            } else if (astIsIntType(ast->type)) {
                return (double)ast->i64;
            } else {
                *_ok = 0;
                return 0;
            }
        }
        case AST_UNOP: {
            switch (ast->unop) {
                case AST_UN_OP_PLUS:  return +eval(ast->operand, _ok);
                case AST_UN_OP_MINUS: return -eval(ast->operand, _ok);
                case AST_UN_OP_LOG_NOT:
                    return (double)evalIntConstExprOrErr(ast, _ok);
                default: {
                    *_ok = 0;
                    return 0;
                }
            }
        }
        case AST_BINOP: {
            switch (ast->binop) {
                case AST_BIN_OP_MUL: return eval(ast->left, _ok) * eval(ast->right, _ok);
                case AST_BIN_OP_DIV: return eval(ast->left, _ok) / eval(ast->right, _ok);
                case AST_BIN_OP_ADD: return eval(ast->left, _ok) + eval(ast->right, _ok);
                case AST_BIN_OP_SUB: return eval(ast->left, _ok) - eval(ast->right, _ok);
                case AST_BIN_OP_LT: return eval(ast->left, _ok) < eval(ast->right, _ok);
                case AST_BIN_OP_LE: return eval(ast->left, _ok) <= eval(ast->right, _ok);
                case AST_BIN_OP_GT: return eval(ast->left, _ok) > eval(ast->right, _ok);
                case AST_BIN_OP_GE: return eval(ast->left, _ok) >= eval(ast->right, _ok);
                case AST_BIN_OP_EQ: return eval(ast->left, _ok) == eval(ast->right, _ok);
                case AST_BIN_OP_NE: return eval(ast->left, _ok) != eval(ast->right, _ok);
                /* 0 or 1, from the integer folder: `(0.5 && 1) * 1.5` */
                case AST_BIN_OP_LOG_AND:
                case AST_BIN_OP_LOG_OR:
                    return (double)evalIntConstExprOrErr(ast, _ok);
                default: {
                    *_ok = 0;
                    return 0;
                }
            }
        }
        default: {
            *_ok = 0;
            return 0;
        }
    }
#undef eval
}

double evalFloatExpr(Ast *ast) {
    int ok = 1;
    double result = evalFloatExprOrErr(ast,&ok);
    if (!ok) {
        loggerPanic("Expected float expression: %s\n", astToString(ast));
    }
    return result;
}

s64 evalIntArithmeticOrErr(Ast *ast, int *_ok) {
#define eval evalIntArithmeticOrErr
    if (!astCanEval(ast,_ok,0)) {
        *_ok = 0;
        return 0.0;
    }

    switch (ast->kind) {
        case AST_LITERAL: {
            if (astIsIntOrIntrinsic(ast->type)) {
                return ast->i64;
            } else if (astIsFloatType(ast->type)) {
                return (long)ast->f64;
            } else {
                *_ok = 0;
                return 0;
            }
        }

        case AST_BINOP:
            switch (ast->binop) {
                case AST_BIN_OP_MUL: return eval(ast->left, _ok) * eval(ast->right, _ok);
                case AST_BIN_OP_DIV: return eval(ast->left, _ok) / eval(ast->right, _ok);
                case AST_BIN_OP_MOD: return eval(ast->left, _ok) % eval(ast->right, _ok);
                case AST_BIN_OP_ADD: return eval(ast->left, _ok) + eval(ast->right, _ok);
                case AST_BIN_OP_SUB: return eval(ast->left, _ok) - eval(ast->right, _ok);
                case AST_BIN_OP_SHL: return eval(ast->left, _ok) << eval(ast->right, _ok);
                case AST_BIN_OP_SHR: return eval(ast->left, _ok) >> eval(ast->right, _ok);
                case AST_BIN_OP_BIT_AND: return eval(ast->left, _ok) & eval(ast->right, _ok);
                case AST_BIN_OP_BIT_XOR: return eval(ast->left, _ok) ^ eval(ast->right, _ok);
                case AST_BIN_OP_BIT_OR: return eval(ast->left, _ok) | eval(ast->right, _ok);
                default: {
                    *_ok = 0;
                    return 0;
                }
            }
            break;
        default: {
            *_ok = 0;
            return 0;
        }
    }
#undef eval
}

s64 evalOneIntExprOrErr(Ast *LHS, Ast *RHS, AstBinOp op, int *_ok) {
    if (LHS->kind == AST_LITERAL && RHS->kind == AST_LITERAL) {
        s64 left =  astIsIntType(LHS->type) ? LHS->i64 : (ssize_t)LHS->f64;
        s64 right = astIsIntType(RHS->type) ? RHS->i64 : (ssize_t)LHS->f64;
        s64 result = 0;
        switch (op) {
            case AST_BIN_OP_ADD:  result = left + right; break;
            case AST_BIN_OP_SUB:  result = left - right; break;
            case AST_BIN_OP_MUL:  result = left * right; break;
            case AST_BIN_OP_DIV:  result = left / right; break;
            case AST_BIN_OP_MOD:  result = left % right; break;
            case AST_BIN_OP_BIT_XOR:  result = left ^ right; break;
            case AST_BIN_OP_BIT_AND:  result = left & right; break;
            case AST_BIN_OP_BIT_OR:   result = left | right; break;
            case AST_BIN_OP_SHL:  result = left << right; break;
            case AST_BIN_OP_SHR: result = left >> right; break;
            default:
                loggerPanic("Invalid operator: '%s'\n",
                        astBinOpKindToString(op));
        }
        *_ok = 1;
        return result;
    }
    *_ok = 0;
    return 0;
}

/* A U64 operand makes `/`, `%`, `>>` and the ordered comparisons unsigned,
 * matching the IR lowering so folded values equal the runtime ones. */
static int evalIsUnsignedBinOp(Ast *ast) {
    return astIsU64Type(ast->type) ||
           astIsU64Type(ast->left->type) || astIsU64Type(ast->right->type);
}

/* An operand of &&, || or ! is true when it isn't 0, compared as a
 * float if it is one: `0.5 && 1` is 1, not `0 && 1`. */
static int evalIsTrue(Ast *ast, int *_ok) {
    if (astIsFloatType(ast->type)) {
        return evalFloatExprOrErr(ast, _ok) != 0.0;
    }
    return evalIntConstExprOrErr(ast, _ok) != 0;
}

/* `/` and `%` only fold when both operands are constants and the
 * divisor isn't 0: `i % n` must not divide the placeholder 0s of two
 * variables, and `5 % 0` is left to the caller, which reports it
 * (parseCreateBinaryOp does) instead of ktm dying with a SIGFPE.
 * I64_MIN / -1 wraps the way the result is kept, it doesn't trap. */
static s64 evalIntDivMod(Ast *ast, int *_ok) {
    s64 left = evalIntConstExprOrErr(ast->left, _ok);
    s64 right = evalIntConstExprOrErr(ast->right, _ok);
    int is_div = ast->binop == AST_BIN_OP_DIV;
    if (!*_ok || right == 0) {
        *_ok = 0;
        return 0;
    }
    if (evalIsUnsignedBinOp(ast)) {
        return is_div ? (s64)((u64)left / (u64)right)
                      : (s64)((u64)left % (u64)right);
    }
    if (right == -1) {
        return is_div ? (s64)(0 - (u64)left) : 0;
    }
    return is_div ? left / right : left % right;
}

s64 evalIntConstExprOrErr(Ast *ast, int *_ok) {
    switch (ast->kind) {
        case AST_TERNARY:
            /* Only the chosen branch is evaluated, as at runtime. */
            return evalIsTrue(ast->cond, _ok)
                 ? evalIntConstExprOrErr(ast->then, _ok)
                 : evalIntConstExprOrErr(ast->els, _ok);
        case AST_CAST: {
            /* To Bool: 0 or 1, as in C (0.5(Bool) and 0x100(Bool) are 1) */
            if (ast->type && ast->type->is_bool) {
                if (astIsFloatType(ast->operand->type)) {
                    return evalFloatExprOrErr(ast->operand, _ok) != 0.0;
                }
                return evalIntConstExprOrErr(ast->operand, _ok) != 0;
            }
            s64 value = astIsFloatType(ast->operand->type)
                ? (s64)evalFloatExprOrErr(ast->operand, _ok)
                : evalIntConstExprOrErr(ast->operand, _ok);
            return evalIntCast(value, ast->type);
        }
        case AST_LITERAL: {
            if (astIsIntOrIntrinsic(ast->type)) {
                return ast->i64;
            } else if (astIsFloatType(ast->type)) {
                return (long)ast->f64;
            } else {
                *_ok = 0;
                return 0;
            }
        }
        case AST_GVAR:
        case AST_LVAR: {
            /* A `const` integer whose own initialiser folded to a
             * constant: see AST_FLAG_CONST_VALUE's comment in ast.h.
             * A `const` with a runtime initialiser is not constant
             * (it reaches here and falls through to *_ok = 0, the same
             * as any other variable). */
            if (ast->flags & AST_FLAG_CONST_VALUE) {
                return ast->deref_symbol;
            }
            *_ok = 0;
            return 0;
        }
        case AST_UNOP: {
            switch (ast->unop) {
                case AST_UN_OP_PLUS: return +(evalIntConstExprOrErr(ast->operand, _ok));
                case AST_UN_OP_MINUS: return -(evalIntConstExprOrErr(ast->operand, _ok));
                case AST_UN_OP_LOG_NOT: return !evalIsTrue(ast->operand, _ok);
                case AST_UN_OP_BIT_NOT: return ~(evalIntConstExprOrErr(ast->operand, _ok));
                case AST_UN_OP_ADDR_OF:
                    if (ast->operand->kind == AST_CLASS_REF) {
                        return evalClassRef(ast->operand, 0);
                    }
                    break;
                default: {
                    *_ok = 0;
                    return 0;
                }
            }
            break;
        }

        case AST_BINOP:
            /* A comparison is an integer but compares its operands as
             * floats if either is one: `1.5 > 1` is 1, not `1 > 1`. */
            if (astIsFloatType(ast->left->type) ||
                astIsFloatType(ast->right->type)) {
                switch (ast->binop) {
                    case AST_BIN_OP_LT: case AST_BIN_OP_LE:
                    case AST_BIN_OP_GT: case AST_BIN_OP_GE:
                    case AST_BIN_OP_EQ: case AST_BIN_OP_NE:
                        return (s64)evalFloatExprOrErr(ast, _ok);
                    default:
                        break;
                }
            }
            switch (ast->binop) {
                case AST_BIN_OP_MUL:
                    return evalIntConstExprOrErr(ast->left, _ok) *
                        evalIntConstExprOrErr(ast->right, _ok);
                case AST_BIN_OP_DIV:
                case AST_BIN_OP_MOD:
                    return evalIntDivMod(ast, _ok);
                case AST_BIN_OP_ADD:
                    return evalIntConstExprOrErr(ast->left, _ok) +
                        evalIntConstExprOrErr(ast->right, _ok);
                case AST_BIN_OP_SUB:
                    return evalIntConstExprOrErr(ast->left, _ok) -
                        evalIntConstExprOrErr(ast->right, _ok);
                case AST_BIN_OP_SHL:
                    return evalIntConstExprOrErr(ast->left, _ok) <<
                        evalIntConstExprOrErr(ast->right, _ok);
                case AST_BIN_OP_SHR:
                    if (evalIsUnsignedBinOp(ast)) {
                        return (u64)evalIntConstExprOrErr(ast->left, _ok) >>
                            (u64)evalIntConstExprOrErr(ast->right, _ok);
                    }
                    return evalIntConstExprOrErr(ast->left, _ok) >>
                        evalIntConstExprOrErr(ast->right, _ok);
                case AST_BIN_OP_LT:
                    if (evalIsUnsignedBinOp(ast)) {
                        return (u64)evalIntConstExprOrErr(ast->left, _ok) <
                            (u64)evalIntConstExprOrErr(ast->right, _ok);
                    }
                    return evalIntConstExprOrErr(ast->left, _ok) <
                        evalIntConstExprOrErr(ast->right, _ok);
                case AST_BIN_OP_LE:
                    if (evalIsUnsignedBinOp(ast)) {
                        return (u64)evalIntConstExprOrErr(ast->left, _ok) <=
                            (u64)evalIntConstExprOrErr(ast->right, _ok);
                    }
                    return evalIntConstExprOrErr(ast->left, _ok) <=
                        evalIntConstExprOrErr(ast->right, _ok);
                case AST_BIN_OP_GT:
                    if (evalIsUnsignedBinOp(ast)) {
                        return (u64)evalIntConstExprOrErr(ast->left, _ok) >
                            (u64)evalIntConstExprOrErr(ast->right, _ok);
                    }
                    return evalIntConstExprOrErr(ast->left, _ok) >
                        evalIntConstExprOrErr(ast->right, _ok);
                case AST_BIN_OP_GE:
                    if (evalIsUnsignedBinOp(ast)) {
                        return (u64)evalIntConstExprOrErr(ast->left, _ok) >=
                            (u64)evalIntConstExprOrErr(ast->right, _ok);
                    }
                    return evalIntConstExprOrErr(ast->left, _ok) >=
                        evalIntConstExprOrErr(ast->right, _ok);
                case AST_BIN_OP_EQ:
                    return evalIntConstExprOrErr(ast->left, _ok) ==
                        evalIntConstExprOrErr(ast->right, _ok);
                case AST_BIN_OP_NE:
                    return evalIntConstExprOrErr(ast->left, _ok) !=
                        evalIntConstExprOrErr(ast->right, _ok);
                case AST_BIN_OP_BIT_AND:
                    return evalIntConstExprOrErr(ast->left, _ok) &
                        evalIntConstExprOrErr(ast->right, _ok);
                case AST_BIN_OP_BIT_XOR:
                    return evalIntConstExprOrErr(ast->left, _ok) ^
                        evalIntConstExprOrErr(ast->right, _ok);
                case AST_BIN_OP_BIT_OR:
                    return evalIntConstExprOrErr(ast->left, _ok) |
                        evalIntConstExprOrErr(ast->right, _ok);
                case AST_BIN_OP_LOG_AND:
                    return evalIsTrue(ast->left, _ok) &&
                        evalIsTrue(ast->right, _ok);
                case AST_BIN_OP_LOG_OR:
                    return evalIsTrue(ast->left, _ok) ||
                        evalIsTrue(ast->right, _ok);
                default: {
                    *_ok = 0;
                    return 0;
                }
            }
            break;
        default: {
            *_ok = 0;
            return 0;
        }
    }
    *_ok = 0;
    return 0;
}

s64 evalIntConstExpr(Ast *ast) {
    int ok = 1;
    s64 res = evalIntConstExprOrErr(ast,&ok);
    if (!ok) {
        loggerPanic("Expected integer expression: %s\n", astToString(ast));
    }
    return res;
}

int assertLValue(Ast *ast) {
    switch (ast->kind) {
        case AST_LVAR:
        case AST_GVAR:
        case AST_CLASS_REF:
        case AST_FUNPTR:
        case AST_DEFAULT_PARAM:
        case AST_CAST:
        case AST_UNOP:
            return 1;

        default:
            return 0;
    }
}

/* `ast` is not an lvalue: a plain diagnostic, unless it is an enum
 * member (`Name.member = x`), which gets a message naming it. Member
 * literals are interned per name, so pointer identity finds which one.
 * Reported at `cc`'s current position without unwinding. */
void parseRaiseNotLValue(Cctrl *cc, Ast *ast) {
    if (ast->kind == AST_LITERAL && ast->type &&
        ast->type->kind == AST_TYPE_CLASS && ast->type->is_enum &&
        ast->type->enum_members)
    {
        MapIter it;
        mapIterInit(ast->type->enum_members, &it);
        while (mapIterNext(&it)) {
            if (it.node->value == ast) {
                cctrlRaiseException(cc,"Cannot assign to enum member `%s.%s`",
                        ast->type->clsname ? ast->type->clsname->data
                                           : "<anonymous>",
                        (char *)it.node->key);
            }
        }
    }
    char *ast_str = astLValueToString(ast,0);
    cctrlRaiseException(cc,"Expected an L-Value however got a %s - `%s`",
            astKindToHumanReadable(ast), ast_str);
}

/* `ast` is about to be written to (`=`, a compound assignment, `++`,
 * `--`): error out if it is a `const` variable or parameter. */
void assertNotConstAssign(Cctrl *cc, Ast *ast) {
    /* `s.len = n`: a slice's fields are set together, by assigning a
     * whole slice */
    /* a String's too, but to its own methods (module str's) */
    if (ast->kind == AST_CLASS_REF && ast->cls && ast->cls->type &&
        astTypeIsString(ast->cls->type)) {
        AoStr *path = cctrlModulePath(cc, cc->cur_module);
        if (!path || path->len != 3 || memcmp(path->data, "str", 3)) {
            cctrlRewindUntilStrMatch(cc, ast->field, strlen(ast->field), NULL);
            cctrlRaiseException(cc, "a String's `.%s` is read-only: change it with "
                                "its methods", ast->field);
        }
    }
    if (ast->kind == AST_CLASS_REF && ast->cls && ast->cls->type &&
        astTypeIsSlice(ast->cls->type)) {
        cctrlRewindUntilStrMatch(cc, ast->field, strlen(ast->field), NULL);
        cctrlRaiseException(cc, "a slice's `.%s` is read-only: assign the whole "
                            "slice instead", ast->field);
    }
    if (ast->kind == AST_LVAR && (ast->flags & AST_FLAG_VARARGS)) {
        cctrlRaiseException(cc,"cannot assign to the variadic parameter '%s'",
                            ast->lname->data);
    }
    if ((ast->kind == AST_LVAR || ast->kind == AST_GVAR) &&
        (ast->flags & AST_FLAG_CONST))
    {
        char *name = ast->kind == AST_LVAR ? ast->lname->data : ast->gname->data;
        cctrlRaiseException(cc,"cannot assign to const '%s'", name);
    }
}

/* `case_` (at `case_tok`, its value written as `value_expr`) takes a
 * value an earlier case of the switch has: reported by the value, or an
 * enum's member, without unwinding */
void assertUniqueSwitchCaseLabels(Cctrl *cc, Vec *case_vector, Ast *case_,
                                  Ast *value_expr, Lexeme *case_tok)
{
    for (u64 i = 0; i < case_vector->size; ++i) {
        Ast *cur = case_vector->entries[i];
        if (case_->case_end < cur->case_begin ||
            cur->case_end < case_->case_begin) {
            continue;
        }
        s64 value = case_->case_begin > cur->case_begin ? case_->case_begin
                                                        : cur->case_begin;
        char *shown = mprintf("%lld", (long long)value);
        AstType *ty = value_expr ? value_expr->type : NULL;
        if (ty && ty->is_enum && ty->enum_members) {
            MapIter it;
            mapIterInit(ty->enum_members, &it);
            while (mapIterNext(&it)) {
                Ast *member = it.node->value;
                if (member && member->kind == AST_LITERAL && member->i64 == value) {
                    shown = mprintf("%s.%s", ty->clsname ? ty->clsname->data
                                                         : "<anonymous>",
                                    (char *)it.node->key);
                    break;
                }
            }
        }
        char *msg = mprintf("duplicate case value %s", shown);
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
        return;
    }
}

/* `expected = actual` (or a compound assignment, or an initialiser) with
 * types that don't go together. Underlines from the operator at
 * `op_line`:`op_col` to the end of the statement on that line; the
 * operator's position is passed in because by the time the right hand side
 * is parsed it may be far behind the current token. */
void typeCheckWarn(Cctrl *cc, int op_line, int op_col, Ast *expected,
                   Ast *actual)
{
    AoStr *expected_type = astTypeToColorAoStr(expected->type);
    AoStr *actual_type = astTypeToColorAoStr(actual->type);
    int severity = (cc->flags & CCTRL_WERROR) ? CCTRL_ERROR : CCTRL_WARN;

    s64 len = 1;
    if (cc->lexer_ && op_col > 0) {
        char *line_buffer = lexerReportLineIn(cctrlCurFile(cc), op_line);
        s64 start = op_col - 1, end = start;
        if (start < (s64)strlen(line_buffer)) {
            while (line_buffer[end] && line_buffer[end] != ';') end++;
            if (line_buffer[end] == ';') end++;
            while (end > start + 1 && isspace((unsigned char)line_buffer[end - 1])) {
                end--;
            }
            len = end - start;
        }
    }

    char *msg = mprintf("Incompatible types '%s' is not assignable to type '%s'",
                        actual_type->data, expected_type->data);
    AoStr *bold = aoStrNew();
    aoStrCatColoured(bold, ESC_BOLD, msg);
    AoStr *buf = cctrlCreateErrorLineAt(cc, op_line, op_col, len, bold->data,
                                        severity, NULL);
    CctrlDiagnostic *d = cctrlMakeDiag(cc, severity, buf, NULL);
    d->line = d->end_line = op_line;
    d->col = op_col;
    d->end_col = op_col + len;
    cctrlDiagPush(cc, d);

    aoStrRelease(bold);
    aoStrRelease(expected_type);
    aoStrRelease(actual_type);
    if (severity == CCTRL_ERROR) {
        cctrlTerminate(cc);
    }
}

/* An array can't be assigned to, incremented or decremented, as in C:
 * `arr = p`, `arr += 1`, `arr++`. Reported at the operator without
 * unwinding (the error stops the compile once parsing ends). Returns 1
 * if `lhs` is an array. */
int assertNotArrayAssign(Cctrl *cc, Ast *lhs, int op_line, int op_col,
                         int op_len)
{
    if (!lhs || !lhs->type || lhs->type->kind != AST_TYPE_ARRAY) {
        return 0;
    }
    char *name = astLValueToString(lhs,0);
    char *msg = mprintf("Cannot assign to array `%s`, assign to its elements or use a pointer",
                        name);
    AoStr *bold = aoStrNew();
    aoStrCatColoured(bold, ESC_BOLD, msg);
    AoStr *buf = cctrlCreateErrorLineAt(cc, op_line, op_col, op_len, bold->data,
                                        CCTRL_ERROR, NULL);
    aoStrRelease(bold);
    CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, NULL);
    d->line = d->end_line = op_line;
    d->col = op_col;
    d->end_col = op_col + op_len;
    cctrlDiagPush(cc, d);
    return 1;
}

/* `f++` / `--f` on a function pointer: it points at code, so there is
 * nothing to step over (like `f + 1`, a type error). Reported at the
 * operator without unwinding. Returns 1 if `lhs` is a function. */
int assertNotFnIncDec(Cctrl *cc, Ast *lhs, int op_line, int op_col,
                      int op_len)
{
    if (!lhs || !lhs->type || lhs->type->kind != AST_TYPE_FUNC) return 0;
    char *name = astLValueToString(lhs,0);
    char *msg = mprintf("Cannot increment or decrement function pointer `%s`",
                        name);
    AoStr *bold = aoStrNew();
    aoStrCatColoured(bold, ESC_BOLD, msg);
    AoStr *buf = cctrlCreateErrorLineAt(cc, op_line, op_col, op_len, bold->data,
                                        CCTRL_ERROR, NULL);
    aoStrRelease(bold);
    CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, NULL);
    d->line = d->end_line = op_line;
    d->col = op_col;
    d->end_col = op_col + op_len;
    cctrlDiagPush(cc, d);
    return 1;
}

/* A scoped enum is like C++'s `enum class`: `++`, `--` and a compound
 * assignment (`+=`, `|=`, ...) are all errors, not just an integer
 * assigned to it without a cast. Plain `c = Color.green` and
 * `c = (Color)(c + 1)` stay fine. Reported at the operator without
 * unwinding. Returns 1 if `lhs` is an enum. */
int assertNotEnumArithAssign(Cctrl *cc, Ast *lhs, int op_line, int op_col,
                             int op_len)
{
    if (!lhs || !lhs->type || lhs->type->kind != AST_TYPE_CLASS ||
        !lhs->type->is_enum)
    {
        return 0;
    }
    char *name = astLValueToString(lhs,0);
    char *msg = mprintf(
            "Cannot do arithmetic assignment on enum `%s`, cast it: `%s`",
            lhs->type->clsname ? lhs->type->clsname->data : "<anonymous>",
            name);
    AoStr *bold = aoStrNew();
    aoStrCatColoured(bold, ESC_BOLD, msg);
    AoStr *buf = cctrlCreateErrorLineAt(cc, op_line, op_col, op_len, bold->data,
                                        CCTRL_ERROR, NULL);
    aoStrRelease(bold);
    CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, NULL);
    d->line = d->end_line = op_line;
    d->col = op_col;
    d->end_col = op_col + op_len;
    cctrlDiagPush(cc, d);
    return 1;
}

/* `return retval;` in a function whose return type `retval` doesn't
 * suit. `ret_tok` is the `return` and `semi_tok` the `;`: when they are on
 * the same line the expression is shown as written, from after the
 * `return` to the `;`, and underlined with it; otherwise it is printed
 * from the AST at the `return`. */
void typeCheckReturnTypeWarn(Cctrl *cc, Ast *maybe_func, 
                             AstType *check, Ast *retval,
                             Lexeme *ret_tok, Lexeme *semi_tok)
{
    char *fstring = NULL;
    if (maybe_func) {
        fstring = astFunctionToString(maybe_func);
    } else {
        fstring = astFunctionNameToString(cc->tmp_rettype,
                cc->tmp_fname->data,cc->tmp_fname->len);
    }

    char *expected = astTypeToColorString(cc->tmp_rettype);
    AoStr *got = astTypeToColorAoStr(check);
    AoStr *ast_str = NULL;
    s64 len = ret_tok->len;

    if (cc->lexer_ && semi_tok && semi_tok->line == ret_tok->line &&
        semi_tok->col > ret_tok->col + ret_tok->len)
    {
        char *line_buffer = lexerReportLineIn(cctrlCurFile(cc), ret_tok->line);
        s64 start = ret_tok->col - 1 + ret_tok->len;
        s64 end = semi_tok->col - 1;
        if (end <= (s64)strlen(line_buffer)) {
            while (start < end && isspace((unsigned char)line_buffer[start])) start++;
            while (end > start && isspace((unsigned char)line_buffer[end - 1])) end--;
            if (end > start) {
                ast_str = aoStrNew();
                aoStrCatLen(ast_str, line_buffer + start, end - start);
                len = end - (ret_tok->col - 1);
            }
        }
    }
    if (!ast_str) {
        ast_str = astLValueToAoStr(retval,0);
    }

    /* cctrlWarningAt makes it bold (when colours are on) */
    if (!retval) {
        /* `return;` in a function returning a value: there is no value
         * to print (this said `'(null)' of type 'U0'`) */
        char *msg = mprintf("%s `return` with no value, expected a value of type '%s'",
                            fstring, expected);
        cctrlWarningAt(cc, ret_tok->line, ret_tok->col, ret_tok->len, "%s", msg);
        return;
    }
    char *msg = mprintf("%s unexpected return value '%s' of type '%s' expected '%s'",
                        fstring,
                        ast_str->data,
                        got->data,
                        expected);

    cctrlWarningAt(cc, ret_tok->line, ret_tok->col, len, "%s", msg);
}
