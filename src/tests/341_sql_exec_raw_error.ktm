#include "testhelper.ktm"

/* SqlExecRaw's check was inverted: it returned sqlite's (NULL) message on
 * success and NULL on failure, and leaked the message. It returns an
 * MAlloc'd copy of the error now (Free it), NULL on success. */

Bool Fails(SqlCtx *ctx, U8 *sql, U8 *want)
{
  U8 *err = SqlExecRaw(ctx, sql);
  Bool ok = err && strstr(err, want) != NULL;
  if (!ok && err) "%s\n", err;
  Free(err);
  return ok;
}

I32 Main()
{
  "Test - sql exec raw error: ";
  I64 correct = 0, tests = 0, i, before, delta;
  SqlCtx *ctx = SqlCtxNew(":memory:");

  tests++; if (ctx) correct++;
  if (!ctx) {
    PrintResult(correct, tests);
    "====\n";
    return 0;
  }

  tests++; if (SqlExecRaw(ctx, "CREATE TABLE t(id INTEGER);") == NULL) correct++;
  tests++; if (SqlExecRaw(ctx, "INSERT INTO t VALUES(1); INSERT INTO t VALUES(2);") == NULL) correct++;
  tests++; if (Fails(ctx, "THIS IS NOT SQL;", "syntax error")) correct++;
  tests++; if (Fails(ctx, "INSERT INTO nosuch VALUES(1);", "no such table")) correct++;
  tests++; if (Fails(ctx, "CREATE TABLE t(x);", "already exists")) correct++;

  /* The message is copied and sqlite's own copy freed. */
  for (i = 0; i < 50; i++) Free(SqlExecRaw(ctx, "SELEC 1;"));
  before = HeapInUse();
  for (i = 0; i < 2000; i++) Free(SqlExecRaw(ctx, "SELEC 1;"));
  delta = HeapInUse() - before;
  tests++; if (delta < 4096) correct++; else "leaked %d bytes\n", delta;

  SqlCtxRelease(ctx);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
