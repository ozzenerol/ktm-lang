#include "testhelper.ktm"

/* SqlRowRelease on a row of a prepared statement finalized that
 * statement, so the next SqlExecPrepared or SqlFinalizePrepared used a
 * dead handle; and SqlIterPrepared never freed the column array, leaking
 * it on every execution. A row now knows whether it owns its statement. */

I64 RunPrepared(SqlPreparedStmt *ps, I64 from)
{
  SqlRow row;
  SqlParam p[1];
  I64 n = 0;
  p[0].type = SQLITE_INTEGER;
  p[0].i64 = from;
  if (SqlExecPrepared(ps, &row, p) != SQLITE_ROW) return 0;
  while (SqlIterPrepared(&row)) n++;
  return n;
}

I32 Main()
{
  "Test - sql row release prepared: ";
  I64 correct = 0, tests = 0, i, n, before, delta;
  SqlRow row;
  SqlParam p[1];
  SqlPreparedStmt *ps;
  SqlCtx *ctx = SqlCtxNew(":memory:");

  tests++; if (ctx) correct++;
  if (!ctx) {
    PrintResult(correct, tests);
    "====\n";
    return 0;
  }
  SqlExecRaw(ctx, "CREATE TABLE t(id INTEGER, s TEXT);");
  SqlExecRaw(ctx, "INSERT INTO t VALUES(1,'a'),(2,'b'),(3,'c'),(4,'d');");
  ps = SqlPrepare(ctx, "SELECT id, s FROM t WHERE id >= ? ORDER BY id;");
  tests++; if (ps) correct++;

  /* Releasing a prepared row early keeps the statement usable. */
  p[0].type = SQLITE_INTEGER;
  p[0].i64 = 2;
  tests++;
  if (ps && SqlExecPrepared(ps, &row, p) == SQLITE_ROW && SqlIterPrepared(&row) &&
      row.col[0].i64 == 2) {
    correct++;
  }
  if (ps) SqlRowRelease(&row);
  tests++; if (row.stmt == NULL && row.col == NULL) correct++;
  tests++; if (ps && RunPrepared(ps, 1) == 4 && RunPrepared(ps, 3) == 2) correct++;

  /* Running it to the end and then releasing is harmless too. */
  tests++; if (ps && RunPrepared(ps, 4) == 1) correct++;
  if (ps) {
    SqlExecPrepared(ps, &row, p);
    while (SqlIterPrepared(&row)) {}
    SqlRowRelease(&row);
  }
  tests++; if (ps && RunPrepared(ps, 1) == 4) correct++;

  /* No column array is leaked per execution: a leaked one is 64 bytes,
   * 1.28 MB over these 20000 runs. On macOS the heap total can also step
   * up by a few KB with nothing leaked: each CPU's malloc magazine keeps
   * its last freed block cached and counted as in use, and the thread may
   * move to another core during the loop (16 cores: up to ~128 KB). So
   * many runs, and an allowance of 128 KB. */
  if (ps) {
    for (i = 0; i < 20; i++) RunPrepared(ps, 1);
    before = HeapInUse();
    for (i = 0; i < 20000; i++) RunPrepared(ps, 1);
    delta = HeapInUse() - before;
    tests++; if (delta < 128 * 1024) correct++; else "leaked %d bytes\n", delta;
  }
  SqlFinalizePrepared(ps);

  /* Rows of SqlSelect still own (and finalize) their statement. */
  n = 0;
  tests++;
  if (SqlSelect(ctx, &row, "SELECT id FROM t;")) {
    while (SqlIter(&row)) n++;
    SqlRowRelease(&row);
    if (n == 4) correct++;
  }
  tests++;
  if (SqlSelect(ctx, &row, "SELECT id FROM t;") && SqlIter(&row)) {
    SqlRowRelease(&row);
    if (row.stmt == NULL) correct++;
  }

  SqlCtxRelease(ctx);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
