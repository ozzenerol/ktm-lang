#include "testhelper.ktm"

/* Text and blob parameters were bound with SQLITE_STATIC, so sqlite kept
 * reading the caller's buffer while the rows were stepped: reusing or
 * freeing it after SqlSelect/SqlExecPrepared changed (or read freed)
 * data. They are bound with SQLITE_TRANSIENT now (sqlite copies them). */

Bool AllRows(SqlRow *row, U8 *want, I64 count, Bool prepared)
{
  I64 n = 0;
  Bool ok = TRUE;
  while (TRUE) {
    if (prepared) {
      if (!SqlIterPrepared(row)) break;
    } else if (!SqlIter(row)) {
      break;
    }
    if (row->col[0].type != SQLITE_TEXT || StrCmp(row->col[0].str, want)) {
      ok = FALSE;
    }
    n++;
  }
  return ok && n == count;
}

Bool SelectText(SqlCtx *ctx)
{
  SqlRow row;
  SqlParam q[1];
  U8 *key = StrNew("original");
  q[0].type = SQLITE_TEXT;
  q[0].str = key;
  if (!SqlSelect(ctx, &row, "SELECT ?, id FROM t;", q, 1)) return FALSE;
  StrCpy(key, "CHANGED!");
  Free(key);
  return AllRows(&row, "original", 3, FALSE);
}

Bool SelectBlob(SqlCtx *ctx)
{
  SqlRow row;
  SqlParam q[1];
  U8 *blob = MAlloc(4);
  I64 n = 0;
  Bool ok = TRUE;
  MemCpy(blob, "\x01\x02\x03\x04", 4);
  q[0].type = SQLITE_BLOB;
  q[0].blob = blob;
  q[0].blob_len = 4;
  if (!SqlSelect(ctx, &row, "SELECT ?, id FROM t;", q, 1)) return FALSE;
  MemSet(blob, 0xEE, 4);
  Free(blob);
  while (SqlIter(&row)) {
    U8 *b = row.col[0].blob;
    if (row.col[0].type != SQLITE_BLOB || row.col[0].len != 4 ||
        b[0] != 1 || b[1] != 2 || b[2] != 3 || b[3] != 4) {
      ok = FALSE;
    }
    n++;
  }
  return ok && n == 3;
}

Bool InsertThenFree(SqlCtx *ctx)
{
  SqlRow row;
  SqlParam q[1];
  U8 *name = StrNew("kept");
  q[0].type = SQLITE_TEXT;
  q[0].str = name;
  if (!SqlQuery(ctx, "INSERT INTO names(name) VALUES(?);", q, 1)) return FALSE;
  StrCpy(name, "gone");
  Free(name);
  if (!SqlSelect(ctx, &row, "SELECT name FROM names;")) return FALSE;
  return AllRows(&row, "kept", 1, FALSE);
}

Bool PreparedText(SqlCtx *ctx)
{
  SqlRow row;
  SqlParam q[1];
  Bool ok;
  SqlPreparedStmt *st = SqlPrepare(ctx, "SELECT ?, id FROM t;");
  U8 *key = StrNew("prepared");
  q[0].type = SQLITE_TEXT;
  q[0].str = key;
  if (SqlExecPrepared(st, &row, q) != SQLITE_ROW) {
    SqlFinalizePrepared(st);
    return FALSE;
  }
  StrCpy(key, "XXXXXXXX");
  Free(key);
  ok = AllRows(&row, "prepared", 3, TRUE);
  SqlFinalizePrepared(st);
  return ok;
}

I32 Main()
{
  "Test - sql bind transient: ";
  I64 correct = 0, tests = 0;
  SqlCtx *ctx = SqlCtxNew(":memory:");

  if (ctx) {
    SqlExecRaw(ctx, "CREATE TABLE t(id INTEGER);");
    SqlExecRaw(ctx, "INSERT INTO t VALUES(1),(2),(3);");
    SqlExecRaw(ctx, "CREATE TABLE names(name TEXT);");
  }

  tests++; if (ctx) correct++;
  tests++; if (ctx && SelectText(ctx)) correct++;
  tests++; if (ctx && SelectBlob(ctx)) correct++;
  tests++; if (ctx && InsertThenFree(ctx)) correct++;
  tests++; if (ctx && PreparedText(ctx)) correct++;

  if (ctx) SqlCtxRelease(ctx);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
