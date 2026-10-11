module sql;

#ifdef __KTM_LINK_SQLITE3__
import cstr, mem;

pub const i64 SQLITE_OK = 0;   /* Successful result */

/* beginning-of-error-codes */
pub const i64 SQLITE_ERROR = 1;   /* Generic error */

pub const i64 SQLITE_INTERNAL = 2;   /* Internal logic error in SQLite */

pub const i64 SQLITE_PERM = 3;   /* Access permission denied */

pub const i64 SQLITE_ABORT = 4;   /* Callback routine requested an abort */

pub const i64 SQLITE_BUSY = 5;   /* The database file is locked */

pub const i64 SQLITE_LOCKED = 6;   /* A table in the database is locked */

pub const i64 SQLITE_NOMEM = 7;   /* A malloc() failed */

pub const i64 SQLITE_READONLY = 8;   /* Attempt to write a readonly database */

pub const i64 SQLITE_INTERRUPT = 9;   /* Operation terminated by sqlite3_interrupt()*/

pub const i64 SQLITE_IOERR = 10;   /* Some kind of disk I/O error occurred */

pub const i64 SQLITE_CORRUPT = 11;   /* The database disk image is malformed */

pub const i64 SQLITE_NOTFOUND = 12;   /* Unknown opcode in sqlite3_file_control() */

pub const i64 SQLITE_FULL = 13;   /* Insertion failed because database is full */

pub const i64 SQLITE_CANTOPEN = 14;   /* Unable to open the database file */

pub const i64 SQLITE_PROTOCOL = 15;   /* Database lock protocol error */

pub const i64 SQLITE_EMPTY = 16;   /* Internal use only */

pub const i64 SQLITE_SCHEMA = 17;   /* The database schema changed */

pub const i64 SQLITE_TOOBIG = 18;   /* String or BLOB exceeds size limit */

pub const i64 SQLITE_CONSTRAINT = 19;   /* Abort due to constraint violation */

pub const i64 SQLITE_MISMATCH = 20;   /* Data type mismatch */

pub const i64 SQLITE_MISUSE = 21;   /* Library used incorrectly */

pub const i64 SQLITE_NOLFS = 22;   /* Uses OS features not supported on host */

pub const i64 SQLITE_AUTH = 23;   /* Authorization denied */

pub const i64 SQLITE_FORMAT = 24;   /* Not used */

pub const i64 SQLITE_RANGE = 25;   /* 2nd parameter to sqlite3_bind out of range */

pub const i64 SQLITE_NOTADB = 26;   /* File opened that is not a database file */

pub const i64 SQLITE_NOTICE = 27;   /* Notifications from sqlite3_log() */

pub const i64 SQLITE_WARNING = 28;   /* Warnings from sqlite3_log() */

pub const i64 SQLITE_ROW = 100;  /* sqlite3_step() has another row ready */

pub const i64 SQLITE_DONE = 101;  /* sqlite3_step() has finished executing */

pub const i64 SQLITE_INTEGER = 1;

pub const i64 SQLITE_FLOAT = 2;

pub const i64 SQLITE_BLOB = 4;

pub const i64 SQLITE_NULL = 5;

pub const i64 SQLITE_TEXT = 3;

pub const i64 SQLITE3_TEXT = 3;

/* Destructor argument of sqlite3_bind_* ((void *)-1): sqlite copies the
 * value, so the caller may free or reuse it right after the call. */
const i64 SQLITE_TRANSIENT = (-1);

pub struct sqlite3_stmt {};

pub struct sqlite3 {};

pub struct sqlite3_value {};

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

pub Db *open(u8 *dbname, bool printerror=false) {
    sqlite3 *db = null;
    Db *ctx;
    i32 rc = sqlite3_open(dbname, &db);

    if (rc != SQLITE_OK) {
        if (printerror) {
            u8 *s = sqlite3_errmsg(db);
            printf("NOT OK: %s\n", s);
        }
        /* The handle is allocated even when opening fails. */
        sqlite3_close(db);
        return null;
    }

    ctx = mem.alloc(sizeof(Db));
    ctx.dbname = dbname;
    ctx.conn = db;
    return ctx;
}

pub void close(Db *ctx) {
    if (!ctx) { return; }
    /* Unlike sqlite3_close, closes once the last statement is finalized
     * instead of failing with SQLITE_BUSY and leaking the connection. */
    sqlite3_close_v2(ctx.conn);
    mem.free(ctx);
}

/* Only one statement may be given: anything after the first one other
 * than whitespace and comments is an error, rather than silently not
 * run. Preparing such a tail gives SQLITE_OK and no statement. */
static bool sqlTailIsEmpty(Db *ctx, u8 *tail) {
    sqlite3_stmt *extra = null;
    if (!tail || !*tail) {
        return true;
    }
    i32 rc = sqlite3_prepare_v2(ctx.conn, tail, -1, &extra, null);
    if (extra) {
        sqlite3_finalize(extra);
        return false;
    }
    return rc == SQLITE_OK;
}

pub Stmt *prepare(Db *ctx, u8 *sql) {
    Stmt *pstmt = mem.alloc(sizeof(Stmt));
    u8 *tail = null;
    pstmt.stmt = null;
    if (sqlite3_prepare_v2(ctx.conn, sql, -1, &(pstmt.stmt), &tail) != SQLITE_OK ||
            pstmt.stmt == null || !sqlTailIsEmpty(ctx, tail)) {
        /* A failed prepare leaves stmt null; so does an empty string. */
        sqlite3_finalize(pstmt.stmt);
        mem.free(pstmt);
        return null;
    }
    pstmt.paramsCount = sqlite3_bind_parameter_count(pstmt.stmt);
    return pstmt;
}

static void sqlReset(Stmt *pstmt) {
    sqlite3_clear_bindings(pstmt.stmt);
    sqlite3_reset(pstmt.stmt);
}

pub void finalize(Stmt *pstmt) {
    if (!pstmt) { return; }
    sqlite3_finalize(pstmt.stmt);
    mem.free(pstmt);
}

pub extern "c" void sqlite3_free(void *ptr);

/* Unsafe */ 
pub u8 *execRaw(Db *ctx, u8 *sql) {
    u8 *errmsg = null, *copy;
    i32 rc = sqlite3_exec(ctx.conn, sql, 0, 0, &errmsg);
    if (rc == SQLITE_OK) {
        return null;
    }
    if (errmsg) {
        copy = cstr.dup(errmsg);
        sqlite3_free(errmsg);
    } else {
        copy = cstr.dup(sqlite3_errmsg(ctx.conn));
    }
    return copy;
}

pub i32 execPrepared(Stmt *pstmt, Row *row, Param *params) {
    i32 rc = 0;

    if (row) {
        row.stmt = null;
    }
    if (!pstmt) {
        return SQLITE_MISUSE;
    }
    sqlReset(pstmt);

    /* null params: every parameter is left unbound, which is SQL null. */
    for (auto i = 0; params && i < pstmt.paramsCount; ++i) {
        Param *param = &params[i];
        switch (param.type) {
            case INT:
                rc = sqlite3_bind_int64(pstmt.stmt, i + 1, param.intVal);
                break;
            case FLOAT:
                rc = sqlite3_bind_double(pstmt.stmt, i + 1, param.floatVal);
                break;
            case TEXT:
                rc = sqlite3_bind_text(pstmt.stmt, i + 1, param.str, -1, (void *)SQLITE_TRANSIENT);
                break;
            case BLOB:
                rc = sqlite3_bind_blob(pstmt.stmt, i + 1, param.blob, param.blobLen,
                        (void *)SQLITE_TRANSIENT);
                break;
            case NULL_TYPE:
                rc = sqlite3_bind_null(pstmt.stmt, i + 1);
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
    rc = sqlite3_step(pstmt.stmt);
    if (rc == SQLITE_ROW && row) {
        row.stmt = pstmt.stmt;
        row.cols = 0;
        row.col = null;
        row.ownsStmt = false;
    }

    return rc;
}

static i32 sqlExecQuery(Db *ctx, Row *row, u8 *sql, Param *params,
                                                i32 paramCount) {
    i32 rc = SQLITE_ERROR;
    sqlite3_stmt *stmt = null;
    u8 *tail = null;

    if (row) {
        row.stmt = null;
    }

    rc = sqlite3_prepare_v2(ctx.conn, sql, -1, &stmt, &tail);

    if (rc != SQLITE_OK) {
        goto err;
    }
    if (!sqlTailIsEmpty(ctx, tail)) {
        rc = SQLITE_ERROR;
        goto err;
    }

    for (auto i = 0; i < paramCount; ++i) {
        switch (params[i].type) {
            case INT:
                /* The leftmost SQL parameter has an index of 1 ref:
                 * https://www.sqlite.org/c3ref/bind_blob.html */
                rc = sqlite3_bind_int64(stmt, i + 1, params[i].intVal);
                break;
            case FLOAT:
                rc = sqlite3_bind_double(stmt, i + 1, params[i].floatVal);
                break;
            case TEXT:
                rc = sqlite3_bind_text(stmt, i + 1, params[i].str, -1, (void *)SQLITE_TRANSIENT);
                break;
            case BLOB:
                rc = sqlite3_bind_blob(stmt, i + 1, params[i].blob,
                        params[i].blobLen, (void *)SQLITE_TRANSIENT);
                break;
            case NULL_TYPE:
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
        row.stmt = stmt;
        row.cols = 0;
        row.col = null;
        row.ownsStmt = true;
        stmt = null;
    }

err:
    if (stmt) {
        sqlite3_finalize(stmt);
    }
    return rc;
}

pub void rowRelease(Row *row) {
    if (row.stmt == null) {
        return;
    }
    mem.free(row.col);
    if (row.ownsStmt) {
        sqlite3_finalize(row.stmt);
    }
    row.col = null;
    row.stmt = null;
}

pub bool select(Db *ctx, Row *row, u8 *sql, Param *params=null,
        i32 paramCount=0) {
    auto result = sqlExecQuery(ctx,row,sql,params,paramCount);
    return result == SQLITE_DONE || result == SQLITE_ROW;
}

pub bool exec(Db *ctx, u8 *sql, Param *params=null, i32 paramCount=0) {
    auto result = sqlExecQuery(ctx,null,sql,params,paramCount);
    return result == SQLITE_DONE || result == SQLITE_ROW;
}

static bool sqlIterGeneric(Row *row) {
    if (row.stmt == null) {
        return false;
    }

    if (row.col != null) {
        if (sqlite3_step(row.stmt) != SQLITE_ROW) {
            rowRelease(row);
            return false;
        }
    }

    /* XXX: MAlloc's and Free's on each call which is a bit expensive seeing 
     * as this function will be called in a loop */
    mem.free(row.col);
    row.cols = sqlite3_data_count(row.stmt);
    row.col = mem.allocZero(row.cols * sizeof(Column));

    for (auto i = 0; i < row.cols; i++) {
        row.col[i].type = sqlite3_column_type(row.stmt,i);
        switch (row.col[i].type) {
            case SQLITE_INTEGER:
                row.col[i].intVal = sqlite3_column_int64(row.stmt,i);
                break;
            case SQLITE_FLOAT:
                row.col[i].floatVal = sqlite3_column_double(row.stmt,i);
                break;
            case SQLITE3_TEXT:
                row.col[i].str = (u8 *)sqlite3_column_text(row.stmt,i);
                row.col[i].len = sqlite3_column_bytes(row.stmt,i);
                break;
            case SQLITE_BLOB:
                row.col[i].blob = (void *)sqlite3_column_blob(row.stmt,i);
                row.col[i].len = sqlite3_column_bytes(row.stmt,i);
                break;
            default:
                /* XXX: You may want to handle this case differently */
                row.col[i].str = null;
                row.col[i].intVal = 0;
                row.col[i].floatVal = 0;
                break;
        }
    }
    return true;
}

pub i32 next(Row *row) {
    return sqlIterGeneric(row);
}

pub i64 nextPrepared(Row *row) {
    return sqlIterGeneric(row);
}

#endif
