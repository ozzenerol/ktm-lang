#ifndef PARSER_H
#define PARSER_H

#include "list.h"
#include "ast.h"
#include "cctrl.h"

#define PUNCT_TERM_COMMA  (1<<0) // ';'
#define PUNCT_TERM_SEMI   (1<<1) // ','
#define PUNCT_TERM_RPAREN (1<<2) // ')'
#define PUNCT_TERM_LPAREN (1<<3) // '('
#define PUNCT_TERM_ARROW  (1<<4) // '->'
#define PUNCT_TERM_DOT    (1<<5) // '.'
#define PUNCT_TERM_LSQR   (1<<6) // '['
#define PUNCT_TERM_RSQR   (1<<7) // ']'

void parseToAst(Cctrl *cc);
Ast *parseStatement(Cctrl *cc);
/* The current file's name as a string literal (for throw and bounds checks) */
Ast *parseThrowFile(Cctrl *cc);
/* An expression where one is required, reported when missing */
Ast *parseRequiredExpr(Cctrl *cc, char *what);
s64 evalIntConstExpr(Ast *ast);

AstType *parseGenericStructInstance(Cctrl *cc, AstType *tmpl, Vec *args, Lexeme *at);
Vec *parseGenericArgs(Cctrl *cc);
void parseGenericFlush(Cctrl *cc);
GenericDef *parseFindGenericFunc(Cctrl *cc, char *name, int len);
Ast *parseGenericFuncInstance(Cctrl *cc, GenericDef *d, Vec *args, Lexeme *at);
Vec *parseGenericInfer(Cctrl *cc, GenericDef *d, Vec *argv, char **why);
Ast *parseGenericMethod(Cctrl *cc, AstType *cls, char *name, int len);
GenericDef *parseFindGenericOwnMethod(Cctrl *cc, AstType *cls, char *name, int len,
                                      AstType **owner);
Ast *parseGenericOwnMethodInstance(Cctrl *cc, GenericDef *d, AstType *cls, Vec *own, Lexeme *at);
Vec *parseGenericOwnMethodInfer(Cctrl *cc, GenericDef *d, AstType *cls, Vec *argv, char **why);
/* --expand: print the code @json makes (parseJsonGenerate) */
extern int parse_expand_generated;
#endif // !PARSER_H
