#ifndef PRS_UTIL
#define PRS_UTIL

#include "ast.h"
#include "cctrl.h"
#include "lexer.h"

#define PUNCT_TERM_COMMA  (1<<0) // ';'
#define PUNCT_TERM_SEMI   (1<<1) // ','
#define PUNCT_TERM_RPAREN (1<<2) // ')'
#define PUNCT_TERM_LPAREN (1<<3) // '('
#define PUNCT_TERM_ARROW  (1<<4) // '->'
#define PUNCT_TERM_DOT    (1<<5) // '.'
#define PUNCT_TERM_LSQR   (1<<6) // '['
#define PUNCT_TERM_RSQR   (1<<7) // ']'

int align(int n, int m);


/* Is the lexeme both of type TK_PUNCT and does 'ch' match */
int parseIsFunctionCall(Ast *ast);
AstType *parseGetType(Cctrl *cc, Lexeme *tok);
int parseIsKeyword(Lexeme *tok, Cctrl *cc);
double evalFloatExpr(Ast *ast);
double evalFloatExprOrErr(Ast *ast, int *_ok);
s64 evalIntConstExpr(Ast *ast);
s64 evalIntConstExprOrErr(Ast *ast, int *_ok);
s64 evalOneIntExprOrErr(Ast *LHS, Ast *RHS, AstBinOp op, int *_ok);
s64 evalIntArithmeticOrErr(Ast *ast, int *_ok);
int evalClassRef(Ast *ast, int offset);
int assertLValue(Ast *ast);
void parseRaiseNotLValue(Cctrl *cc, Ast *ast);
void assertNotConstAssign(Cctrl *cc, Ast *ast);
void parseCheckCondition(Cctrl *cc, Ast *ast);
Ast *parseLiteralWriteBase(Ast *at);
void parseCheckLiteralWrites(Cctrl *cc);
void parseNoteSliceMoved(Ast *ast);

/* A `for (i64 i = 0; i < s.len; i++)` loop being parsed (see
 * parseElideLoopOpen): its s[i] elements, unchecked if neither i nor s
 * changes in the body and neither has its address taken */
typedef struct ElideLoop {
    Ast *idx;
    Ast *slice;
    int spoiled;
    List *elems;
} ElideLoop;
ElideLoop *parseElideLoopOpen(Cctrl *cc, Ast *init, Ast *cond, Ast *step);
void parseElideLoopClose(Cctrl *cc, ElideLoop *loop);
void parseElideNoteElem(Cctrl *cc, Ast *base, Ast *index, Ast *elem);
void parseElideNoteWrite(Cctrl *cc, Ast *ast);
void parseElideNoteLabel(Cctrl *cc);
void parseElideSettle(Cctrl *cc);
int parseIsFloatOrInt(Ast *ast);
int parseIsClassOrUnion(int kind);
void parseCheckPrivateField(Cctrl *cc, AstType *field, char *name, int len);
int parseIsFunction(Ast *ast);
int astIsArithmetic(Ast *ast, int is_float);

void assertTokenIsTerminator(Cctrl *cc, Lexeme *tok, s64 terminator_flags);
void assertTokenIsTerminatorWithMsg(Cctrl *cc, Lexeme *tok,
        s64 terminator_flags, const char *fmt, ...);
void assertUniqueSwitchCaseLabels(Cctrl *cc, Vec *case_vector, Ast *case_,
                                  Ast *value_expr, Lexeme *case_tok);
void assertIsFloatOrInt(Ast *ast, s64 lineno);
void assertIsInt(Ast *ast, s64 lineno);
void assertIsFloat(Ast *ast, s64 lineno);
void assertIsPointer(Ast *ast, s64 lineno);

void typeCheckWarn(Cctrl *cc, int op_line, int op_col, Ast *expected,
                   Ast *actual);
int assertNotArrayAssign(Cctrl *cc, Ast *lhs, int op_line, int op_col,
                         int op_len);
int assertNotFnIncDec(Cctrl *cc, Ast *lhs, int op_line, int op_col,
                      int op_len);
int assertNotEnumArithAssign(Cctrl *cc, Ast *lhs, int op_line, int op_col,
                             int op_len);
void typeCheckReturnTypeWarn(Cctrl *cc, Ast *maybe_func, 
                             AstType *check, Ast *retval,
                             Lexeme *ret_tok, Lexeme *semi_tok);
#endif // PRS_UTIL
