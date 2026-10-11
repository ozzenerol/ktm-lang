module sql;

#define SQL_INT   SQLITE_INTEGER

#define SQL_FLOAT SQLITE_FLOAT

#define SQL_TEXT  SQLITE3_TEXT

#define SQL_BLOB  SQLITE_BLOB

#define SQL_NULL  SQLITE_NULL

/* Destructor argument of sqlite3_bind_* ((U0 *)-1): sqlite copies the
 * value, so the caller may free or reuse it right after the call. */
#define SQLITE_TRANSIENT (-1)

class sqlite3_stmt {};

class sqlite3 {};

class sqlite3_value {};

public class SqlParam
{
  I64 type;
  union 
  {
    I64 i64;
    F64 f64;
    U8 *str;
    class 
    {
      U0 *blob;
      I64 blob_len;
    };
  };
};

public class SqlPreparedStmt
{
  sqlite3_stmt *stmt;
  I64  params_count;
};

public class SqlColumn
{
  I64 len;
  I64 type;
  union {
    I64 i64;
    U8 *str;
    U0 *blob;
    F64 f64;
  };
};

public class SqlRow
{
  sqlite3_stmt *stmt;
  I64 cols;
  SqlColumn *col;
  /* FALSE for a row of a prepared statement, which SqlFinalizePrepared
   * finalizes. */
  Bool owns_stmt;
};

public class SqlCtx
{
  sqlite3 *conn;
  U8 *dbname;
};

public extern "c" I32 sqlite3_prepare_v2(
  sqlite3 *db,            /* Database handle */
  U8 *zSql,               /* SQL statement, UTF-8 encoded */
  I32 nByte,              /* Maximum length of zSql in bytes. */
  sqlite3_stmt **ppStmt,  /* OUT: Statement handle */
  U8 **pzTail             /* OUT: Pointer to unused portion of zSql */
);

public extern "c" I32 sqlite3_bind_parameter_count(sqlite3_stmt *stmt);

public extern "c" I32 sqlite3_close(sqlite3 *db);

public extern "c" I32 sqlite3_clear_bindings(sqlite3_stmt *stmt);

public extern "c" I32 sqlite3_reset(sqlite3_stmt *pStmt);

public extern "c" I32 sqlite3_finalize(sqlite3_stmt *pStmt);

public extern "c" I32 sqlite3_exec(
  sqlite3 *db,                               /* An open database */
  U8 *sql,                                  /* SQL to be evaluated */
  I32 (*callback)(U0 *_priv, I32 argc, U8 **ptr, U8 **ptr2),  /* Callback function */
  U8 *priv,                                    /* 1st argument to callback */
  U8 **errmsg                              /* Error msg written here */
);

public extern "c" I32 sqlite3_bind_blob(sqlite3_stmt *stmt, I32 __i, U0 *__ptr, I32 n,
    U0 *(*_bind)(U0 *param));

public extern "c" I32 sqlite3_bind_blob64(sqlite3_stmt *stmt, I32 __i, U0 *__ptr, I64 __i2,
    U0 *(*_bind)(U0 *param));

public extern "c" I32 sqlite3_bind_double(sqlite3_stmt *stmt, I32 __i, F64 __f64);

public extern "c" I32 sqlite3_bind_int(sqlite3_stmt *stmt, I32 __i, I32 __int);

public extern "c" I32 sqlite3_bind_int64(sqlite3_stmt *stmt, I32 __i, I64 __int64);

public extern "c" I32 sqlite3_bind_null(sqlite3_stmt *stmt, I32 __i);

public extern "c" I32 sqlite3_bind_text(sqlite3_stmt *stmt, I32 __i, U8 *__buf, I32 __i2, 
    U0 *(*_bind)(U0 *param));

public extern "c" I32 sqlite3_bind_text16(sqlite3_stmt *stmt, I32 __i, U0 *__buf, I32 __i2, 
    U0 *(*_bind)(U0 *param));

public extern "c" I32 sqlite3_bind_text64(sqlite3_stmt *stmt, I32 __i, U8 *__buf, I64 __int64,
    U0 *(*_bind)(U0 *param), U8 encoding);

public extern "c" I32 sqlite3_bind_value(sqlite3_stmt *stmt, I32 __i, sqlite3_value *__val);

public extern "c" I32 sqlite3_step(sqlite3_stmt *stmt);

public extern "c" I32 sqlite3_data_count(sqlite3_stmt *pStmt);

public extern "c" I32 sqlite3_column_bytes(sqlite3_stmt *stmt, I32 iCol);

public extern "c" U0 *sqlite3_column_blob(sqlite3_stmt *stmt, I32 iCol);

public extern "c" F64 sqlite3_column_double(sqlite3_stmt *stmt, I32 iCol);

public extern "c" I32 sqlite3_column_int(sqlite3_stmt *stmt, I32 iCol);

public extern "c" I64 sqlite3_column_int64(sqlite3_stmt *stmt, I32 iCol);

public extern "c" U8 *sqlite3_column_text(sqlite3_stmt *stmt, I32 iCol);

public extern "c" U0 *sqlite3_column_text16(sqlite3_stmt *stmt, I32 iCol);

public extern "c" sqlite3_value *sqlite3_column_value(sqlite3_stmt *stmt, I32 iCol);

public extern "c" I32 sqlite3_column_bytes16(sqlite3_stmt *stmt, I32 iCol);

public extern "c" I32 sqlite3_column_type(sqlite3_stmt *stmt, I32 iCol);

public extern "c" I32 sqlite3_open(U8 *dbname, sqlite3 **db);

public extern "c" I32 sqlite3_close_v2(sqlite3 *db);

public extern "c" U8 *sqlite3_errmsg(sqlite3 *db);

public SqlCtx *SqlCtxNew(U8 *dbname, Bool printerror=FALSE)
{
  sqlite3 *db = NULL;
  SqlCtx *ctx;
  I32 rc = sqlite3_open(dbname, &db);
  
  if (rc != SQLITE_OK) {
    if (printerror) {
      U8 *s = sqlite3_errmsg(db);
      "NOT OK: %s\n", s;
    }
    /* The handle is allocated even when opening fails. */
    sqlite3_close(db);
    return NULL;
  }

  ctx = MAlloc(sizeof(SqlCtx));
  ctx->dbname = dbname;
  ctx->conn = db;
  return ctx;
}

public U0 SqlCtxRelease(SqlCtx *ctx)
{
  if (!ctx) return;
  /* Unlike sqlite3_close, closes once the last statement is finalized
   * instead of failing with SQLITE_BUSY and leaking the connection. */
  sqlite3_close_v2(ctx->conn);
  Free(ctx);
}

/* Only one statement may be given: anything after the first one other
 * than whitespace and comments is an error, rather than silently not
 * run. Preparing such a tail gives SQLITE_OK and no statement. */
static Bool SqlTailIsEmpty(SqlCtx *ctx, U8 *tail)
{
  sqlite3_stmt *extra = NULL;
  if (!tail || !*tail) {
    return TRUE;
  }
  I32 rc = sqlite3_prepare_v2(ctx->conn, tail, -1, &extra, NULL);
  if (extra) {
    sqlite3_finalize(extra);
    return FALSE;
  }
  return rc == SQLITE_OK;
}

public SqlPreparedStmt *SqlPrepare(SqlCtx *ctx, U8 *sql)
{
  SqlPreparedStmt *pstmt = MAlloc(sizeof(SqlPreparedStmt));
  U8 *tail = NULL;
  pstmt->stmt = NULL;
  if (sqlite3_prepare_v2(ctx->conn, sql, -1, &(pstmt->stmt), &tail) != SQLITE_OK ||
      pstmt->stmt == NULL || !SqlTailIsEmpty(ctx, tail)) {
    /* A failed prepare leaves stmt NULL; so does an empty string. */
    sqlite3_finalize(pstmt->stmt);
    Free(pstmt);
    return NULL;
  }
  pstmt->params_count = sqlite3_bind_parameter_count(pstmt->stmt);
  return pstmt;
}

static U0 SqlReset(SqlPreparedStmt *pstmt)
{
  sqlite3_clear_bindings(pstmt->stmt);
  sqlite3_reset(pstmt->stmt);
}

public U0 SqlFinalizePrepared(SqlPreparedStmt *pstmt)
{
  if (!pstmt) return;
  sqlite3_finalize(pstmt->stmt);
  Free(pstmt);
}

extern "c" U0 sqlite3_free(U0 *ptr);

/* Unsafe */ 
public U8 *SqlExecRaw(SqlCtx *ctx, U8 *sql)
{
  U8 *errmsg = NULL, *copy;
  I32 rc = sqlite3_exec(ctx->conn, sql, 0, 0, &errmsg);
  if (rc == SQLITE_OK) {
    return NULL;
  }
  if (errmsg) {
    copy = StrNew(errmsg);
    sqlite3_free(errmsg);
  } else {
    copy = StrNew(sqlite3_errmsg(ctx->conn));
  }
  return copy;
}

public I32 SqlExecPrepared(SqlPreparedStmt *pstmt, SqlRow *row, SqlParam *params)
{
  I32 rc = 0;

  if (row) {
    row->stmt = NULL;
  }
  if (!pstmt) {
    return SQLITE_MISUSE;
  }
  SqlReset(pstmt);

  /* NULL params: every parameter is left unbound, which is SQL NULL. */
  for (auto i = 0; params && i < pstmt->params_count; ++i) {
    SqlParam *param = &params[i];
    switch (param->type) {
      case SQL_INT:
        rc = sqlite3_bind_int64(pstmt->stmt, i + 1, param->i64);
        break;
      case SQL_FLOAT:
        rc = sqlite3_bind_double(pstmt->stmt, i + 1, param->f64);
        break;
      case SQL_TEXT:
        rc = sqlite3_bind_text(pstmt->stmt, i + 1, param->str, -1, SQLITE_TRANSIENT(U0 *));
        break;
      case SQL_BLOB:
        rc = sqlite3_bind_blob(pstmt->stmt, i + 1, param->blob, param->blob_len,
            SQLITE_TRANSIENT(U0 *));
        break;
      case SQL_NULL:
        rc = sqlite3_bind_null(pstmt->stmt, i + 1);
        break;
      default:
        rc = SQLITE_MISMATCH;
        break;
    }
    if (rc != SQLITE_OK) {
      /* Don't run the statement with a parameter missing; the next
       * SqlExecPrepared clears what was bound so far. */
      return rc;
    }
  }

  /* Execute. */
  rc = sqlite3_step(pstmt->stmt);
  if (rc == SQLITE_ROW && row) {
    row->stmt = pstmt->stmt;
    row->cols = 0;
    row->col = NULL;
    row->owns_stmt = FALSE;
  }

  return rc;
}

static I32 SqlExecQuery(SqlCtx *ctx, SqlRow *row, U8 *sql, SqlParam *params,
                        I32 param_count)
{
  I32 rc = SQLITE_ERROR;
  sqlite3_stmt *stmt = NULL;
  U8 *tail = NULL;

  if (row) {
    row->stmt = NULL;
  }

  rc = sqlite3_prepare_v2(ctx->conn, sql, -1, &stmt, &tail);

  if (rc != SQLITE_OK) {
    goto err;
  }
  if (!SqlTailIsEmpty(ctx, tail)) {
    rc = SQLITE_ERROR;
    goto err;
  }

  for (auto i = 0; i < param_count; ++i) {
    switch (params[i].type) {
      case SQL_INT:
        /* The leftmost SQL parameter has an index of 1 ref:
         * https://www.sqlite.org/c3ref/bind_blob.html */
        rc = sqlite3_bind_int64(stmt, i + 1, params[i].i64);
        break;
      case SQL_FLOAT:
        rc = sqlite3_bind_double(stmt, i + 1, params[i].f64);
        break;
      case SQL_TEXT:
        rc = sqlite3_bind_text(stmt, i + 1, params[i].str, -1, SQLITE_TRANSIENT(U0 *));
        break;
      case SQL_BLOB:
        rc = sqlite3_bind_blob(stmt, i + 1, params[i].blob,
            params[i].blob_len, SQLITE_TRANSIENT(U0 *));
        break;
      case SQL_NULL:
        rc = sqlite3_bind_null(stmt, i + 1);
        break;
      default:
        rc = SQLITE_MISMATCH;
        goto err;
    }
    if (rc != SQLITE_OK) {
      goto err;
    }
  }

  /* Execute. */
  rc = sqlite3_step(stmt);
  if (rc == SQLITE_ROW && row) {
    row->stmt = stmt;
    row->cols = 0;
    row->col = NULL;
    row->owns_stmt = TRUE;
    stmt = NULL;
  }

err:
  if (stmt) {
    sqlite3_finalize(stmt);
  }
  return rc;
}

public U0 SqlRowRelease(SqlRow *row)
{
  if (row->stmt == NULL) {
    return;
  }
  Free(row->col);
  if (row->owns_stmt) {
    sqlite3_finalize(row->stmt);
  }
  row->col = NULL;
  row->stmt = NULL;
}

public Bool SqlSelect(SqlCtx *ctx, SqlRow *row, U8 *sql, SqlParam *params=NULL,
    I32 param_count=0)
{
  auto result = SqlExecQuery(ctx,row,sql,params,param_count);
  return result == SQLITE_DONE || result == SQLITE_ROW;
}

public Bool SqlQuery(SqlCtx *ctx, U8 *sql, SqlParam *params=NULL, I32 param_count=0)
{
  auto result = SqlExecQuery(ctx,NULL,sql,params,param_count);
  return result == SQLITE_DONE || result == SQLITE_ROW;
}

static Bool SqlIterGeneric(SqlRow *row)
{
  if (row->stmt == NULL) {
    return FALSE;
  }

  if (row->col != NULL) {
    if (sqlite3_step(row->stmt) != SQLITE_ROW) {
      SqlRowRelease(row);
      return FALSE;
    }
  }

  /* XXX: MAlloc's and Free's on each call which is a bit expensive seeing 
   * as this function will be called in a loop */
  Free(row->col);
  row->cols = sqlite3_data_count(row->stmt);
  row->col = CAlloc(row->cols * sizeof(SqlColumn));

  for (auto i = 0; i < row->cols; i++) {
    row->col[i].type = sqlite3_column_type(row->stmt,i);
    switch (row->col[i].type) {
      case SQLITE_INTEGER:
        row->col[i].i64 = sqlite3_column_int64(row->stmt,i);
        break;
      case SQLITE_FLOAT:
        row->col[i].f64 = sqlite3_column_double(row->stmt,i);
        break;
      case SQLITE3_TEXT:
        row->col[i].str = sqlite3_column_text(row->stmt,i)(U8 *);
        row->col[i].len = sqlite3_column_bytes(row->stmt,i);
        break;
      case SQLITE_BLOB:
        row->col[i].blob = sqlite3_column_blob(row->stmt,i)(U0 *);
        row->col[i].len = sqlite3_column_bytes(row->stmt,i);
        break;
      default:
        /* XXX: You may want to handle this case differently */
        row->col[i].str = NULL;
        row->col[i].i64 = 0;
        row->col[i].f64 = 0;
        break;
    }
  }
  return TRUE;
}

public I32 SqlIter(SqlRow *row)
{
  return SqlIterGeneric(row);
}

public I64 SqlIterPrepared(SqlRow *row)
{
  return SqlIterGeneric(row);
}
