module sql;
import c, mem, str;

const i64 SQL_INT = SQLITE_INTEGER;

const i64 SQL_FLOAT = SQLITE_FLOAT;

const i64 SQL_TEXT = SQLITE3_TEXT;

const i64 SQL_BLOB = SQLITE_BLOB;

const i64 SQL_NULL = SQLITE_NULL;

/* Destructor argument of sqlite3_bind_* ((void *)-1): sqlite copies the
 * value, so the caller may free or reuse it right after the call. */
const i64 SQLITE_TRANSIENT = (-1);

struct sqlite3_stmt {};

struct sqlite3 {};

struct sqlite3_value {};

pub struct SqlParam {
    i64 type;
    union {
        i64 i64;
        f64 f64;
        u8 *str;
        struct {
            void *blob;
            i64 blob_len;
        };
    };
};

pub struct SqlPreparedStmt {
    sqlite3_stmt *stmt;
    i64  params_count;
};

pub struct SqlColumn {
    i64 len;
    i64 type;
    union {
        i64 i64;
        u8 *str;
        void *blob;
        f64 f64;
    };
};

pub struct SqlRow {
    sqlite3_stmt *stmt;
    i64 cols;
    SqlColumn *col;
    /* false for a row of a prepared statement, which SqlFinalizePrepared
     * finalizes. */
    bool owns_stmt;
};

pub struct SqlCtx {
    sqlite3 *conn;
    u8 *dbname;
};

pub extern "c" i32 sqlite3_prepare_v2(
    sqlite3 *db,            /* Database handle */
    u8 *zSql,               /* SQL statement, UTF-8 encoded */
    i32 nByte,              /* Maximum length of zSql in bytes. */
    sqlite3_stmt **ppStmt,  /* OUT: Statement handle */
    u8 **pzTail             /* OUT: Pointer to unused portion of zSql */
);

pub extern "c" i32 sqlite3_bind_parameter_count(sqlite3_stmt *stmt);

pub extern "c" i32 sqlite3_close(sqlite3 *db);

pub extern "c" i32 sqlite3_clear_bindings(sqlite3_stmt *stmt);

pub extern "c" i32 sqlite3_reset(sqlite3_stmt *pStmt);

pub extern "c" i32 sqlite3_finalize(sqlite3_stmt *pStmt);

pub extern "c" i32 sqlite3_exec(
    sqlite3 *db,                               /* An open database */
    u8 *sql,                                  /* SQL to be evaluated */
    fn(void *_priv, i32 argc, u8 **ptr, u8 **ptr2) i32 callback,  /* Callback function */
    u8 *priv,                                    /* 1st argument to callback */
    u8 **errmsg                              /* Error msg written here */
);

pub extern "c" i32 sqlite3_bind_blob(sqlite3_stmt *stmt, i32 __i, void *__ptr, i32 n,
        fn(void *param) void *_bind);

pub extern "c" i32 sqlite3_bind_blob64(sqlite3_stmt *stmt, i32 __i, void *__ptr, i64 __i2,
        fn(void *param) void *_bind);

pub extern "c" i32 sqlite3_bind_double(sqlite3_stmt *stmt, i32 __i, f64 __f64);

pub extern "c" i32 sqlite3_bind_int(sqlite3_stmt *stmt, i32 __i, i32 __int);

pub extern "c" i32 sqlite3_bind_int64(sqlite3_stmt *stmt, i32 __i, i64 __int64);

pub extern "c" i32 sqlite3_bind_null(sqlite3_stmt *stmt, i32 __i);

pub extern "c" i32 sqlite3_bind_text(sqlite3_stmt *stmt, i32 __i, u8 *__buf, i32 __i2, 
        fn(void *param) void *_bind);

pub extern "c" i32 sqlite3_bind_text16(sqlite3_stmt *stmt, i32 __i, void *__buf, i32 __i2, 
        fn(void *param) void *_bind);

pub extern "c" i32 sqlite3_bind_text64(sqlite3_stmt *stmt, i32 __i, u8 *__buf, i64 __int64,
        fn(void *param) void *_bind, u8 encoding);

pub extern "c" i32 sqlite3_bind_value(sqlite3_stmt *stmt, i32 __i, sqlite3_value *__val);

pub extern "c" i32 sqlite3_step(sqlite3_stmt *stmt);

pub extern "c" i32 sqlite3_data_count(sqlite3_stmt *pStmt);

pub extern "c" i32 sqlite3_column_bytes(sqlite3_stmt *stmt, i32 iCol);

pub extern "c" void *sqlite3_column_blob(sqlite3_stmt *stmt, i32 iCol);

pub extern "c" f64 sqlite3_column_double(sqlite3_stmt *stmt, i32 iCol);

pub extern "c" i32 sqlite3_column_int(sqlite3_stmt *stmt, i32 iCol);

pub extern "c" i64 sqlite3_column_int64(sqlite3_stmt *stmt, i32 iCol);

pub extern "c" u8 *sqlite3_column_text(sqlite3_stmt *stmt, i32 iCol);

pub extern "c" void *sqlite3_column_text16(sqlite3_stmt *stmt, i32 iCol);

pub extern "c" sqlite3_value *sqlite3_column_value(sqlite3_stmt *stmt, i32 iCol);

pub extern "c" i32 sqlite3_column_bytes16(sqlite3_stmt *stmt, i32 iCol);

pub extern "c" i32 sqlite3_column_type(sqlite3_stmt *stmt, i32 iCol);

pub extern "c" i32 sqlite3_open(u8 *dbname, sqlite3 **db);

pub extern "c" i32 sqlite3_close_v2(sqlite3 *db);

pub extern "c" u8 *sqlite3_errmsg(sqlite3 *db);

pub SqlCtx *open(u8 *dbname, bool printerror=false) {
    sqlite3 *db = null;
    SqlCtx *ctx;
    i32 rc = c.sqlite3_open(dbname, &db);

    if (rc != c.SQLITE_OK) {
        if (printerror) {
            u8 *s = c.sqlite3_errmsg(db);
            printf("NOT OK: %s\n", s);
        }
        /* The handle is allocated even when opening fails. */
        c.sqlite3_close(db);
        return null;
    }

    ctx = mem.alloc(sizeof(SqlCtx));
    ctx.dbname = dbname;
    ctx.conn = db;
    return ctx;
}

pub void close(SqlCtx *ctx) {
    if (!ctx) { return; }
    /* Unlike sqlite3_close, closes once the last statement is finalized
     * instead of failing with SQLITE_BUSY and leaking the connection. */
    c.sqlite3_close_v2(ctx.conn);
    mem.free(ctx);
}

/* Only one statement may be given: anything after the first one other
 * than whitespace and comments is an error, rather than silently not
 * run. Preparing such a tail gives SQLITE_OK and no statement. */
static bool sqlTailIsEmpty(SqlCtx *ctx, u8 *tail) {
    sqlite3_stmt *extra = null;
    if (!tail || !*tail) {
        return true;
    }
    i32 rc = c.sqlite3_prepare_v2(ctx.conn, tail, -1, &extra, null);
    if (extra) {
        c.sqlite3_finalize(extra);
        return false;
    }
    return rc == c.SQLITE_OK;
}

pub SqlPreparedStmt *prepare(SqlCtx *ctx, u8 *sql) {
    SqlPreparedStmt *pstmt = mem.alloc(sizeof(SqlPreparedStmt));
    u8 *tail = null;
    pstmt.stmt = null;
    if (c.sqlite3_prepare_v2(ctx.conn, sql, -1, &(pstmt.stmt), &tail) != c.SQLITE_OK ||
            pstmt.stmt == null || !sqlTailIsEmpty(ctx, tail)) {
        /* A failed prepare leaves stmt null; so does an empty string. */
        c.sqlite3_finalize(pstmt.stmt);
        mem.free(pstmt);
        return null;
    }
    pstmt.params_count = sqlite3_bind_parameter_count(pstmt.stmt);
    return pstmt;
}

static void sqlReset(SqlPreparedStmt *pstmt) {
    c.sqlite3_clear_bindings(pstmt.stmt);
    c.sqlite3_reset(pstmt.stmt);
}

pub void finalize(SqlPreparedStmt *pstmt) {
    if (!pstmt) { return; }
    c.sqlite3_finalize(pstmt.stmt);
    mem.free(pstmt);
}

extern "c" void sqlite3_free(void *ptr);

/* Unsafe */ 
pub u8 *execRaw(SqlCtx *ctx, u8 *sql) {
    u8 *errmsg = null, *copy;
    i32 rc = c.sqlite3_exec(ctx.conn, sql, 0, 0, &errmsg);
    if (rc == c.SQLITE_OK) {
        return null;
    }
    if (errmsg) {
        copy = str.dup(errmsg);
        sqlite3_free(errmsg);
    } else {
        copy = str.dup(c.sqlite3_errmsg(ctx.conn));
    }
    return copy;
}

pub i32 execPrepared(SqlPreparedStmt *pstmt, SqlRow *row, SqlParam *params) {
    i32 rc = 0;

    if (row) {
        row.stmt = null;
    }
    if (!pstmt) {
        return c.SQLITE_MISUSE;
    }
    sqlReset(pstmt);

    /* null params: every parameter is left unbound, which is SQL null. */
    for (auto i = 0; params && i < pstmt.params_count; ++i) {
        SqlParam *param = &params[i];
        switch (param.type) {
            case SQL_INT:
                rc = sqlite3_bind_int64(pstmt.stmt, i + 1, param.intVal);
            case SQL_FLOAT:
                rc = sqlite3_bind_double(pstmt.stmt, i + 1, param.floatVal);
            case SQL_TEXT:
                rc = sqlite3_bind_text(pstmt.stmt, i + 1, param.str, -1, (void *)SQLITE_TRANSIENT);
            case SQL_BLOB:
                rc = sqlite3_bind_blob(pstmt.stmt, i + 1, param.blob, param.blob_len,
                        (void *)SQLITE_TRANSIENT);
            case SQL_NULL:
                rc = sqlite3_bind_null(pstmt.stmt, i + 1);
            default:
                rc = c.SQLITE_MISMATCH;
        }
        if (rc != c.SQLITE_OK) {
            /* Don't run the statement with a parameter missing; the next
             * SqlExecPrepared clears what was bound so far. */
            return rc;
        }
    }

    /* Execute. */
    rc = c.sqlite3_step(pstmt.stmt);
    if (rc == c.SQLITE_ROW && row) {
        row.stmt = pstmt.stmt;
        row.cols = 0;
        row.col = null;
        row.owns_stmt = false;
    }

    return rc;
}

static i32 sqlExecQuery(SqlCtx *ctx, SqlRow *row, u8 *sql, SqlParam *params,
                                                i32 paramCount) {
    i32 rc = c.SQLITE_ERROR;
    sqlite3_stmt *stmt = null;
    u8 *tail = null;

    if (row) {
        row.stmt = null;
    }

    rc = c.sqlite3_prepare_v2(ctx.conn, sql, -1, &stmt, &tail);

    if (rc != c.SQLITE_OK) {
        goto err;
    }
    if (!sqlTailIsEmpty(ctx, tail)) {
        rc = c.SQLITE_ERROR;
        goto err;
    }

    for (auto i = 0; i < paramCount; ++i) {
        switch (params[i].type) {
            case SQL_INT:
                /* The leftmost SQL parameter has an index of 1 ref:
                 * https://www.sqlite.org/c3ref/bind_blob.html */
                rc = sqlite3_bind_int64(stmt, i + 1, params[i].intVal);
            case SQL_FLOAT:
                rc = sqlite3_bind_double(stmt, i + 1, params[i].floatVal);
            case SQL_TEXT:
                rc = sqlite3_bind_text(stmt, i + 1, params[i].str, -1, (void *)SQLITE_TRANSIENT);
            case SQL_BLOB:
                rc = sqlite3_bind_blob(stmt, i + 1, params[i].blob,
                        params[i].blob_len, (void *)SQLITE_TRANSIENT);
            case SQL_NULL:
                rc = sqlite3_bind_null(stmt, i + 1);
            default:
                rc = c.SQLITE_MISMATCH;
                goto err;
        }
        if (rc != c.SQLITE_OK) {
            goto err;
        }
    }

    /* Execute. */
    rc = c.sqlite3_step(stmt);
    if (rc == c.SQLITE_ROW && row) {
        row.stmt = stmt;
        row.cols = 0;
        row.col = null;
        row.owns_stmt = true;
        stmt = null;
    }

err:
    if (stmt) {
        c.sqlite3_finalize(stmt);
    }
    return rc;
}

pub void rowRelease(SqlRow *row) {
    if (row.stmt == null) {
        return;
    }
    mem.free(row.col);
    if (row.owns_stmt) {
        c.sqlite3_finalize(row.stmt);
    }
    row.col = null;
    row.stmt = null;
}

pub bool select(SqlCtx *ctx, SqlRow *row, u8 *sql, SqlParam *params=null,
        i32 paramCount=0) {
    auto result = sqlExecQuery(ctx,row,sql,params,paramCount);
    return result == c.SQLITE_DONE || result == c.SQLITE_ROW;
}

pub bool exec(SqlCtx *ctx, u8 *sql, SqlParam *params=null, i32 paramCount=0) {
    auto result = sqlExecQuery(ctx,null,sql,params,paramCount);
    return result == c.SQLITE_DONE || result == c.SQLITE_ROW;
}

static bool sqlIterGeneric(SqlRow *row) {
    if (row.stmt == null) {
        return false;
    }

    if (row.col != null) {
        if (c.sqlite3_step(row.stmt) != c.SQLITE_ROW) {
            rowRelease(row);
            return false;
        }
    }

    /* XXX: MAlloc's and Free's on each call which is a bit expensive seeing 
     * as this function will be called in a loop */
    mem.free(row.col);
    row.cols = c.sqlite3_data_count(row.stmt);
    row.col = mem.allocZero(row.cols * sizeof(SqlColumn));

    for (auto i = 0; i < row.cols; i++) {
        row.col[i].type = sqlite3_column_type(row.stmt,i);
        switch (row.col[i].type) {
            case c.SQLITE_INTEGER:
                row.col[i].intVal = sqlite3_column_int64(row.stmt,i);
            case c.SQLITE_FLOAT:
                row.col[i].floatVal = sqlite3_column_double(row.stmt,i);
            case c.SQLITE3_TEXT:
                row.col[i].str = (u8 *)sqlite3_column_text(row.stmt,i);
                row.col[i].len = sqlite3_column_bytes(row.stmt,i);
            case c.SQLITE_BLOB:
                row.col[i].blob = (void *)sqlite3_column_blob(row.stmt,i);
                row.col[i].len = sqlite3_column_bytes(row.stmt,i);
            default:
                /* XXX: You may want to handle this case differently */
                row.col[i].str = null;
                row.col[i].intVal = 0;
                row.col[i].floatVal = 0;
        }
    }
    return true;
}

pub i32 next(SqlRow *row) {
    return sqlIterGeneric(row);
}

pub i64 nextPrepared(SqlRow *row) {
    return sqlIterGeneric(row);
}
