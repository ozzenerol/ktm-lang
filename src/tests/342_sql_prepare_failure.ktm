#include "testhelper.ktm"

/* SqlPrepare ignored sqlite3_prepare_v2's result and returned an object
 * with a NULL stmt, which SqlExecPrepared then crashed on. It returns NULL
 * now. SqlExecPrepared with NULL params on a statement with parameters
 * read params[0]; NULL now leaves every parameter unbound (SQL NULL). */

I64 CountRows(SqlCtx *ctx, U8 *sql)
{
  SqlRow row;
  I64 n = 0;
  if (!SqlSelect(ctx, &row, sql)) return -1;
  while (SqlIter(&row)) n++;
  return n;
}

I32 Main()
{
  "Test - sql prepare failure: ";
  I64 correct = 0, tests = 0;
  SqlPreparedStmt *ps;
  SqlParam p[2];
  SqlCtx *ctx = SqlCtxNew(":memory:");

  tests++; if (ctx) correct++;
  if (!ctx) {
    PrintResult(correct, tests);
    "====\n";
    return 0;
  }
  SqlQuery(ctx, "CREATE TABLE t(x, y);");

  /* failed prepares */
  tests++; if (SqlPrepare(ctx, "SELEC broken ?;") == NULL) correct++;
  tests++; if (SqlPrepare(ctx, "SELECT * FROM nosuch;") == NULL) correct++;
  tests++; if (SqlPrepare(ctx, "") == NULL) correct++;
  tests++; if (SqlExecPrepared(NULL, NULL, NULL) != SQLITE_DONE) correct++;
  SqlFinalizePrepared(NULL);

  /* NULL params on a parameterised statement */
  ps = SqlPrepare(ctx, "INSERT INTO t VALUES(?, ?);");
  tests++; if (ps && ps->params_count == 2) correct++;
  tests++; if (ps && SqlExecPrepared(ps, NULL, NULL) == SQLITE_DONE) correct++;
  tests++; if (CountRows(ctx, "SELECT * FROM t WHERE x IS NULL AND y IS NULL;") == 1) correct++;

  /* and real params still bind afterwards */
  p[0].type = SQLITE_INTEGER; p[0].i64 = 7;
  p[1].type = SQLITE_INTEGER; p[1].i64 = 8;
  tests++; if (ps && SqlExecPrepared(ps, NULL, p) == SQLITE_DONE) correct++;
  tests++; if (CountRows(ctx, "SELECT * FROM t WHERE x = 7 AND y = 8;") == 1) correct++;
  SqlFinalizePrepared(ps);

  SqlCtxRelease(ctx);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
