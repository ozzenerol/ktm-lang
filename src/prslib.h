#ifndef PRS_LIB_H
#define PRS_LIB_H

#include "cctrl.h"

Ast *parseUnaryExpr(Cctrl *cc);
Ast *parseExpr(Cctrl *cc, int prec);
Ast *parseFunctionArguments(Cctrl *cc, char *fname, int len, s64 terminator);
Ast *parseThrowCall(Cctrl *cc, Ast *file, int line, Ast **_code);
int parseIsErrorType(AstType *type);
Ast *parsePrintStatement(Cctrl *cc);
Ast *parsePrintCall(Cctrl *cc, s64 terminator);
Ast *parseCallArguments(Cctrl *cc, char *name, int len);
Vec *parseParams(Cctrl *cc, s64 terminator, int *has_var_args, int store);
void parseDeclInternal(Cctrl *cc, Lexeme **tok, AstType **type);
void parseAssignAuto(Cctrl *cc, Ast *ast);
AstType *parseReturnAuto(Cctrl *cc, Ast *retval);
AstType *parseArrayDimensions(Cctrl *cc, AstType *base_type);
AstType *parsePointerType(Cctrl *cc, AstType *type);
Ast *parseSliceFrom(Cctrl *cc, AstType *want, Ast *expr);
Ast *parseAutoDeferFree(Cctrl *cc, Ast *var, Lexeme *at);
Ast *parseGenericExplicitCall(Cctrl *cc, struct GenericDef *d, char *fname, int len);
Ast *parseResolveOverloadRef(Cctrl *cc, AstType *want, Ast *expr);
void parseCheckOverloadRefs(Cctrl *cc);
Ast *parseReflectCall(Cctrl *cc, char *name, int len);
Ast *parseHiddenSliceVar(Cctrl *cc, AstType *type);
Ast *parseStringCPtr(Cctrl *cc, Ast *expr);
AstType *parseBaseDeclSpec(Cctrl *cc);
AstType *parseDeclSpec(Cctrl *cc);
AstType *parseFullType(Cctrl *cc);
Ast *parseFunctionPointer(Cctrl *cc, AstType *rettype);
AstType *parseFunctionPointerType(Cctrl *cc,
        char **fnptr_name, int *fnptr_name_len, AstType *rettype);
int tokenIsFnIdent(Lexeme *tok);
int parseIsReservedName(char *s, int len);
int parseCheckNotReserved(Cctrl *cc, Lexeme *tok);
int tokenMayStartFnType(Lexeme *tok);
int parseFnStartsType(Cctrl *cc);
int parseFnLooksLikeType(Cctrl *cc);
AstType *parseFnType(Cctrl *cc);
Ast *findFunctionDecl(Cctrl *cc, char *fname, int len);
Ast *parseCreateBinaryOp(Cctrl *cc, AstBinOp operation, Ast *left, Ast *right);
void parseRaiseFnConvert(Cctrl *cc, AstType *expected, AstType *actual);
void parseRaiseIfNotImported(Cctrl *cc, Lexeme *at, char *name, int len);
char *parseFnConvertHint(AstType *expected, AstType *actual);
Ast *parseSizeof(Cctrl *cc);
Ast *parseAlignof(Cctrl *cc);
Ast *parseTypeof(Cctrl *cc);

/* Defined in parser.c; used here by parseParams to honour `reg`/`noreg`
 * on parameter declarations as well as locals. */
void parseRegModifier(Cctrl *cc, int *kind_out, AoStr **reg_out);

#endif
