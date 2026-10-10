#ifndef MIGRATE_H__
#define MIGRATE_H__

#include "cctrl.h"
#include "lexer.h"

/* tools/convert: the parser's hooks (compiled only with -DKTM_CONVERT).
 * With cc->migrate set the parser reports what it sees through the
 * migrateNote* hooks; edits are kept as source ranges and applied to the
 * original text, so comments and layout survive. */

typedef struct Migrate Migrate;

/* A postfix cast `x(T)`: its `(` and `)` */
void migrateNoteCast(Cctrl *cc, Lexeme *lparen, Lexeme *rparen);
/* A print statement: its first token (the format string, or a character
 * constant when `is_char`) and its `;` */
void migrateNotePrint(Cctrl *cc, Lexeme *first, Lexeme *semi, int is_char);
/* A C function pointer declarator `R (*name)(P)` (also `(**name)`,
 * `(*name[N])`, `(*Name(params))(P)` and the cast type `R (*)(P)`): the
 * `(` before the `*` and the `)` that closes P */
void migrateNoteFnPtr(Cctrl *cc, Lexeme *open, Lexeme *close);
/* The `...` of an old-style variadic function (implicit argc/argv) */
void migrateNoteOldVarArgs(Cctrl *cc, Lexeme *ellipsis);
/* A use of that implicit `argc` or `argv` */
void migrateNoteOldVarArgUse(Cctrl *cc, Lexeme *name);
/* A function called without parentheses: its name */
void migrateNoteCall(Cctrl *cc, Lexeme *name);
/* The definition of `Main`: its name, the `{` of its body and whether it
 * returns nothing */
void migrateNoteMain(Cctrl *cc, Lexeme *name, Lexeme *lbrace, int is_void);
/* A file-scope item is about to be parsed, starting at `first` */
void migrateItemStart(Cctrl *cc, Lexeme *first);
/* The first token of the item being parsed */
Lexeme *migrateItemFirst(Cctrl *cc);
/* The item started by migrateItemStart runs code: it ends at `last`;
 * `is_decl` when it is a global with an initialiser that isn't constant */
void migrateNoteRunningItem(Cctrl *cc, Lexeme *last, int is_decl);

#endif
