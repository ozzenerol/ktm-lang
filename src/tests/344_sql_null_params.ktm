#include "testhelper.ktm"

/* SqlQuery and SqlSelect rejected SQL_NULL parameters (they fell into the
 * default case, which returned SQLITE_OK as the error) while
 * SqlExecPrepared accepted them. They bind NULL now; an unknown type is
 * still an error. */

I64 Count(SqlCtx *ctx, U8 *sql)
{
  SqlRow row;
  I64 n = 0;
  if (!SqlSelect(ctx, &row, sql)) return -1;
  while (SqlIter(&row)) n++;
  return n;
}

I32 Main()
{
  "Test - sql null params: ";
  I64 correct = 0, tests = 0;
  SqlRow row;
  SqlParam p[3];
  Bool ok;
  SqlCtx *ctx = SqlCtxNew(":memory:");

  tests++; if (ctx) correct++;
  if (!ctx) {
    PrintResult(correct, tests);
    "====\n";
    return 0;
  }
  SqlQuery(ctx, "CREATE TABLE t(a, b, c);");

  p[0].type = SQLITE_INTEGER; p[0].i64 = 1;
  p[1].type = SQLITE_NULL;
  p[2].type = SQLITE_TEXT;    p[2].str = "x";
  tests++; if (SqlQuery(ctx, "INSERT INTO t VALUES(?, ?, ?);", p, 3)) correct++;
  tests++; if (Count(ctx, "SELECT * FROM t WHERE a = 1 AND b IS NULL AND c = 'x';") == 1) correct++;

  p[0].type = SQLITE_NULL;
  tests++; if (SqlQuery(ctx, "INSERT INTO t VALUES(?, 2, 3);", p, 1)) correct++;
  tests++; if (Count(ctx, "SELECT * FROM t WHERE a IS NULL AND b = 2;") == 1) correct++;

  /* SqlSelect with a NULL parameter */
  ok = SqlSelect(ctx, &row, "SELECT ? IS NULL, typeof(?1);", p, 1);
  tests++;
  if (ok && SqlIter(&row) && row.col[0].i64 == 1 && row.col[1].type == SQLITE_TEXT &&
      !StrCmp(row.col[1].str, "null")) {
    correct++;
  }
  if (ok) SqlRowRelease(&row);

  /* an unknown type is a failure and runs nothing */
  p[0].type = 99;
  p[0].i64 = 5;
  tests++; if (!SqlQuery(ctx, "INSERT INTO t VALUES(?, 9, 9);", p, 1)) correct++;
  tests++; if (Count(ctx, "SELECT * FROM t WHERE b = 9;") == 0) correct++;
  tests++; if (!SqlSelect(ctx, &row, "SELECT ?;", p, 1)) correct++;

  SqlCtxRelease(ctx);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
