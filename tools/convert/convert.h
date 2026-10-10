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
/* A `catch` without `(Error e)`: the keyword and its handler's last token */
void migrateNoteCatch(Cctrl *cc, Lexeme *kw, Lexeme *last);
/* A switch's `case`/`default` (or its closing `}`): how the arm before it
 * ends (0: no statements, 1: falls through, 2: with `break;`, 3: leaves
 * otherwise), its last statement's last token and its statement count */
void migrateSwitchLabel(Cctrl *cc, Lexeme *label, int kind, Lexeme *prev_end, int n);
/* A control statement's body that isn't a `{ }` block (Y13): the token
 * before it (`)`, `else`, `do`, `catch`), its first and its last token */
void migrateNoteBody(Cctrl *cc, Lexeme *hdr, Lexeme *first, Lexeme *last);
/* A name declared or used (variable, parameter, function, field):
 * renamed when it is a keyword or type name of the new syntax */
void migrateNoteName(Cctrl *cc, Lexeme *name);
/* An identifier used in an expression (with --map: a stdlib name) */
void migrateNoteRef(Cctrl *cc, Lexeme *name);
/* A type name (with --map: a stdlib type) */
void migrateNoteType(Cctrl *cc, Lexeme *name, AstType *type);
/* A macro used (with --map: a stdlib macro) */
void migrateNoteMacro(Cctrl *cc, Lexeme *use);
/* A use of a stdlib struct's field (with --names: renames.tsv) */
void migrateNoteStdField(Cctrl *cc, Lexeme *name);
/* A `->` (with --layout: `.`, which reaches through one pointer) */
void migrateNoteArrow(Cctrl *cc, Lexeme *arrow);
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
