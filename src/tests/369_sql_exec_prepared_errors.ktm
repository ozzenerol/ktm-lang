#include "testhelper.ktm"

/* SqlExecPrepared ignored the return codes of sqlite3_bind_* and skipped
 * parameters of an unknown type, so the statement ran anyway with NULL in
 * their place and returned SQLITE_DONE. Now a failed bind or an unknown
 * type is returned as the error and the statement does not run. */

/* SQLITE_LIMIT_LENGTH: the longest string or blob sqlite accepts. */
extern "c" I32 sqlite3_limit(sqlite3 *db, I32 id, I32 new_val);
#define SQLITE_LIMIT_LENGTH 0

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
  "Test - sql exec prepared errors: ";
  I64 correct = 0, tests = 0;
  SqlRow row;
  SqlParam p[2];
  I32 rc;
  SqlCtx *ctx = SqlCtxNew(":memory:");
  SqlPreparedStmt *ins, *sel;

  tests++; if (ctx) correct++;
  if (!ctx) {
    PrintResult(correct, tests);
    "====\n";
    return 0;
  }
  SqlQuery(ctx, "CREATE TABLE t(a, b);");
  ins = SqlPrepare(ctx, "INSERT INTO t VALUES(?, ?);");
  sel = SqlPrepare(ctx, "SELECT ?;");
  tests++; if (ins && sel) correct++;

  /* an unknown type in the second parameter: SQLITE_MISMATCH, no row */
  p[0].type = SQL_INT; p[0].i64 = 1;
  p[1].type = 99;      p[1].i64 = 2;
  rc = SqlExecPrepared(ins, NULL, p);
  tests++; if (rc == SQLITE_MISMATCH) correct++;
  tests++; if (Count(ctx, "SELECT * FROM t;") == 0) correct++;

  /* the same with a row: no row is handed out */
  row.stmt = 1(sqlite3_stmt *);
  rc = SqlExecPrepared(sel, &row, p + 1);
  tests++; if (rc == SQLITE_MISMATCH && row.stmt == NULL) correct++;

  /* a bind that sqlite refuses: a string over the length limit */
  sqlite3_limit(ctx->conn, SQLITE_LIMIT_LENGTH, 4);
  p[1].type = SQL_TEXT; p[1].str = "too long";
  rc = SqlExecPrepared(ins, NULL, p);
  tests++; if (rc == SQLITE_TOOBIG) correct++;
  sqlite3_limit(ctx->conn, SQLITE_LIMIT_LENGTH, 1000000);
  tests++; if (Count(ctx, "SELECT * FROM t;") == 0) correct++;

  /* the statement still works after an error, and SQL_NULL binds NULL */
  p[1].type = SQL_NULL;
  rc = SqlExecPrepared(ins, NULL, p);
  tests++; if (rc == SQLITE_DONE) correct++;
  tests++; if (Count(ctx, "SELECT * FROM t WHERE a = 1 AND b IS NULL;") == 1) correct++;

  p[1].type = SQL_TEXT; p[1].str = "ok";
  rc = SqlExecPrepared(sel, &row, p + 1);
  tests++;
  if (rc == SQLITE_ROW && SqlIterPrepared(&row) && row.col[0].type == SQL_TEXT &&
      !StrCmp(row.col[0].str, "ok")) {
    correct++;
  }
  SqlRowRelease(&row);

  SqlFinalizePrepared(ins);
  SqlFinalizePrepared(sel);
  SqlCtxRelease(ctx);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
