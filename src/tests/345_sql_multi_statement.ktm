#include "testhelper.ktm"

/* SqlQuery, SqlSelect and SqlPrepare ran only the first statement of a
 * string holding several and silently dropped the rest. They fail now
 * when anything but whitespace or comments follows the first statement;
 * SqlExecRaw still runs them all. */

Bool HasTable(SqlCtx *ctx, U8 *name)
{
  SqlRow row;
  SqlParam p[1];
  Bool found = FALSE;
  p[0].type = SQLITE_TEXT;
  p[0].str = name;
  if (!SqlSelect(ctx, &row, "SELECT name FROM sqlite_master WHERE name = ?;", p, 1)) {
    return FALSE;
  }
  while (SqlIter(&row)) found = TRUE;
  return found;
}

I32 Main()
{
  "Test - sql multi statement: ";
  I64 correct = 0, tests = 0;
  SqlRow row;
  SqlPreparedStmt *ps;
  SqlCtx *ctx = SqlCtxNew(":memory:");

  tests++; if (ctx) correct++;
  if (!ctx) {
    PrintResult(correct, tests);
    "====\n";
    return 0;
  }

  tests++; if (!SqlQuery(ctx, "CREATE TABLE a(x); CREATE TABLE b(y);")) correct++;
  tests++; if (!HasTable(ctx, "a") && !HasTable(ctx, "b")) correct++;

  tests++; if (SqlQuery(ctx, "CREATE TABLE c(x);   -- trailing comment\n /* and another */ \n")) correct++;
  tests++; if (SqlQuery(ctx, "INSERT INTO c VALUES(1);;")) correct++;
  tests++; if (HasTable(ctx, "c")) correct++;

  tests++; if (!SqlSelect(ctx, &row, "SELECT x FROM c; DELETE FROM c;")) correct++;
  tests++; if (!SqlQuery(ctx, "SELECT 1; SELEC broken")) correct++;

  ps = SqlPrepare(ctx, "INSERT INTO c VALUES(2); DROP TABLE c;");
  tests++; if (ps == NULL) correct++;
  SqlFinalizePrepared(ps);
  ps = SqlPrepare(ctx, "INSERT INTO c VALUES(2); -- note");
  tests++; if (ps && SqlExecPrepared(ps, NULL, NULL) == SQLITE_DONE) correct++;
  SqlFinalizePrepared(ps);

  I64 n = 0;
  if (SqlSelect(ctx, &row, "SELECT x FROM c;")) {
    while (SqlIter(&row)) n++;
  }
  tests++; if (n == 2) correct++;

  /* SqlExecRaw runs every statement */
  tests++; if (SqlExecRaw(ctx, "CREATE TABLE d(x); CREATE TABLE e(y);") == NULL) correct++;
  tests++; if (HasTable(ctx, "d") && HasTable(ctx, "e")) correct++;

  SqlCtxRelease(ctx);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
