#include "testhelper.ktm"

/* SqlCtxNew leaked the sqlite handle when opening failed; SqlCtxRelease
 * used sqlite3_close, which fails with SQLITE_BUSY (and keeps the
 * connection) while a statement is still alive; SqlColumn.len was left
 * uninitialised for INTEGER, FLOAT and NULL columns; sqlite3_open was
 * declared returning Bool; the SQL_* type names were not exported. */

#define BAD_DB_PATH "/nonexistent-ktm-dir-346/x.db"

I32 Main()
{
  "Test - sql ctx leaks: ";
  I64 correct = 0, tests = 0, i, before, delta;
  SqlCtx *ctx;
  SqlPreparedStmt *ps;
  SqlRow row;
  sqlite3 *db = NULL;

  /* the SQL_* names are public */
  tests++;
  if (SQL_INT == SQLITE_INTEGER && SQL_FLOAT == SQLITE_FLOAT &&
      SQL_TEXT == SQLITE_TEXT && SQL_BLOB == SQLITE_BLOB && SQL_NULL == SQLITE_NULL) {
    correct++;
  }

  /* sqlite3_open's I32 result */
  tests++; if (sqlite3_open(BAD_DB_PATH, &db) == SQLITE_CANTOPEN) correct++;
  sqlite3_close(db);

  /* a failed open frees the handle */
  tests++; if (SqlCtxNew(BAD_DB_PATH) == NULL) correct++;
  for (i = 0; i < 20; i++) SqlCtxNew(BAD_DB_PATH);
  before = HeapInUse();
  for (i = 0; i < 300; i++) SqlCtxNew(BAD_DB_PATH);
  delta = HeapInUse() - before;
  tests++; if (delta < 16384) correct++; else "failed open leaked %d bytes\n", delta;

  /* releasing a context with a live statement still closes it. A kept
   * connection is ~145 KB, 40 MB over these 280 opens. On macOS the heap
   * total can also step up by ~130 KB once or twice with nothing leaked:
   * each CPU's malloc magazine keeps its last freed block (the closed
   * connection's) cached and counted as in use, and the thread may move
   * to another core during the loop (16 cores: up to ~2.1 MB). So allow
   * under 8 KB per open. */
  for (i = 0; i < 300; i++) {
    if (i == 20) before = HeapInUse();
    ctx = SqlCtxNew(":memory:");
    ps = SqlPrepare(ctx, "SELECT 1;");
    SqlCtxRelease(ctx);
    SqlFinalizePrepared(ps);
  }
  delta = HeapInUse() - before;
  tests++; if (delta < 280 * 8192) correct++; else "busy close leaked %d bytes\n", delta;
  SqlCtxRelease(NULL);

  /* len is 0 for columns that are not TEXT or BLOB */
  ctx = SqlCtxNew(":memory:");
  tests++;
  if (ctx) {
    Bool ok = TRUE;
    for (i = 0; i < 3 && ok; i++) {
      if (!SqlSelect(ctx, &row, "SELECT 'abcdefgh', 'abcdefgh', 'abcdefgh', 'abcdefgh';") ||
          !SqlIter(&row)) {
        ok = FALSE;
      }
      SqlRowRelease(&row);
      if (!SqlSelect(ctx, &row, "SELECT 1, 2.5, NULL, 'xy';") || !SqlIter(&row)) {
        ok = FALSE;
      } else if (row.col[0].len != 0 || row.col[1].len != 0 || row.col[2].len != 0 ||
                 row.col[3].len != 2) {
        "len %d %d %d %d\n", row.col[0].len, row.col[1].len, row.col[2].len, row.col[3].len;
        ok = FALSE;
      }
      SqlRowRelease(&row);
    }
    if (ok) correct++;
    SqlCtxRelease(ctx);
  }

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
