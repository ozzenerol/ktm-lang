module sql;

#ifdef __KTM_LINK_SQLITE3__ 

const i64 SQLITE_OK = 0;   /* Successful result */
/* beginning-of-error-codes */
const i64 SQLITE_ERROR = 1;   /* Generic error */
const i64 SQLITE_INTERNAL = 2;   /* Internal logic error in SQLite */
const i64 SQLITE_PERM = 3;   /* Access permission denied */
const i64 SQLITE_ABORT = 4;   /* Callback routine requested an abort */
const i64 SQLITE_BUSY = 5;   /* The database file is locked */
const i64 SQLITE_LOCKED = 6;   /* A table in the database is locked */
const i64 SQLITE_NOMEM = 7;   /* A malloc() failed */
const i64 SQLITE_READONLY = 8;   /* Attempt to write a readonly database */
const i64 SQLITE_INTERRUPT = 9;   /* Operation terminated by sqlite3_interrupt()*/
const i64 SQLITE_IOERR = 10;   /* Some kind of disk I/O error occurred */
const i64 SQLITE_CORRUPT = 11;   /* The database disk image is malformed */
const i64 SQLITE_NOTFOUND = 12;   /* Unknown opcode in sqlite3_file_control() */
const i64 SQLITE_FULL = 13;   /* Insertion failed because database is full */
const i64 SQLITE_CANTOPEN = 14;   /* Unable to open the database file */
const i64 SQLITE_PROTOCOL = 15;   /* Database lock protocol error */
const i64 SQLITE_EMPTY = 16;   /* Internal use only */
const i64 SQLITE_SCHEMA = 17;   /* The database schema changed */
const i64 SQLITE_TOOBIG = 18;   /* String or BLOB exceeds size limit */
const i64 SQLITE_CONSTRAINT = 19;   /* Abort due to constraint violation */
const i64 SQLITE_MISMATCH = 20;   /* Data type mismatch */
const i64 SQLITE_MISUSE = 21;   /* Library used incorrectly */
const i64 SQLITE_NOLFS = 22;   /* Uses OS features not supported on host */
const i64 SQLITE_AUTH = 23;   /* Authorization denied */
const i64 SQLITE_FORMAT = 24;   /* Not used */
const i64 SQLITE_RANGE = 25;   /* 2nd parameter to sqlite3_bind out of range */
const i64 SQLITE_NOTADB = 26;   /* File opened that is not a database file */
const i64 SQLITE_NOTICE = 27;   /* Notifications from sqlite3_log() */
const i64 SQLITE_WARNING = 28;   /* Warnings from sqlite3_log() */
const i64 SQLITE_ROW = 100;  /* sqlite3_step() has another row ready */
const i64 SQLITE_DONE = 101;  /* sqlite3_step() has finished executing */

const i64 SQLITE_INTEGER = 1;
const i64 SQLITE_FLOAT = 2;
const i64 SQLITE_BLOB = 4;
const i64 SQLITE_NULL = 5;
const i64 SQLITE_TEXT = 3;
const i64 SQLITE3_TEXT = 3;

const i64 SQL_INT = SQLITE_INTEGER;
const i64 SQL_FLOAT = SQLITE_FLOAT;
const i64 SQL_TEXT = SQLITE3_TEXT;
const i64 SQL_BLOB = SQLITE_BLOB;
const i64 SQL_NULL = SQLITE_NULL;

struct sqlite3_stmt {};
struct sqlite3 {};
struct sqlite3_value {};

/* A value for a `?` parameter: type is SQL_INT (i64), SQL_FLOAT (f64),
 * SQL_TEXT (str, NUL-terminated), SQL_BLOB (blob, blob_len bytes) or
 * SQL_NULL. Text and blobs are copied when bound, so the caller may free
 * or reuse them right after the call. */
struct SqlParam {
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

struct SqlPreparedStmt {
    sqlite3_stmt *stmt;
    i64  params_count;
};

/* One column of the current row: type is SQL_INT, SQL_FLOAT, SQL_TEXT,
 * SQL_BLOB or SQL_NULL. str and blob point into sqlite's memory and are
 * only valid until the next SqlIter/SqlIterPrepared or SqlRowRelease;
 * len is their size in bytes, 0 for the other types. */
struct SqlColumn {
    i64 len;
    i64 type;
    union
    {
        i64 i64;
        u8 *str;
        void *blob;
        f64 f64;
    };
};

/* The current row of a statement: col[0] .. col[cols - 1]. */
struct SqlRow {
    sqlite3_stmt *stmt;
    i64 cols;
    SqlColumn *col;
    /* false for a row of a prepared statement, which SqlFinalizePrepared
     * finalizes. */
    bool owns_stmt;
};

struct SqlCtx {
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
pub extern "c" i32 sqlite3_column_bytes(sqlite3_stmt *stmt, i32 iCol);
pub extern "c" i32 sqlite3_column_bytes16(sqlite3_stmt *stmt, i32 iCol);
pub extern "c" i32 sqlite3_column_type(sqlite3_stmt *stmt, i32 iCol);
pub extern "c" i32 sqlite3_open(u8 *dbname, sqlite3 **db);
pub extern "c" i32 sqlite3_close_v2(sqlite3 *db);
pub extern "c" u8 *sqlite3_errmsg(sqlite3 *db);

/* Open (or create) the database file dbname, ":memory:" for a private
 * in-memory one. null on failure, printing sqlite's message first when
 * printerror is true. dbname is kept, not copied. */
pub SqlCtx *open(u8 *dbname, bool printerror=false);
/* Close the database and free ctx. Statements not finalized yet keep
 * the connection open until SqlFinalizePrepared. null is ignored. */
pub void close(SqlCtx *ctx);
/* Compile one SQL statement with `?` parameters, to run many times with
 * SqlExecPrepared. Only whitespace and comments may follow it. null on
 * an error; free it with SqlFinalizePrepared. */
pub SqlPreparedStmt *prepare(SqlCtx *ctx, u8 *Sql);
/* Free a statement of SqlPrepare. null is ignored. */
pub void finalize(SqlPreparedStmt *pstmt);
/* Run every statement in sql, with no parameters (so never with values
 * from outside the program). null on success, otherwise an error message
 * to Free. */
pub u8 *execRaw(SqlCtx *ctx, u8 *sql);
/* Run a prepared statement with params[0] .. params[n - 1] for its n
 * parameters (null params binds them all to null). Returns sqlite's
 * code: SQLITE_ROW when row now holds the first row (read the rest with
 * SqlIterPrepared), SQLITE_DONE when there are no rows, or an error:
 * SQLITE_MISUSE for a null pstmt, SQLITE_MISMATCH for a parameter type
 * other than SQL_INT/FLOAT/TEXT/BLOB/null, or the code of a failed bind
 * (e.g. SQLITE_TOOBIG). On an error the statement does not run and row
 * holds no row. The row belongs to the statement:
 * it is valid until the next SqlExecPrepared or SqlFinalizePrepared. */
pub i32 execPrepared(SqlPreparedStmt *pstmt, SqlRow *row,
        SqlParam *params);
/* Run one SQL statement with param_count parameters and point row at
 * its first row, if any; read the rows with SqlIter. true if the
 * statement ran (also with no rows), false on an error, an unknown
 * parameter type or anything but whitespace and comments after the
 * statement. The row owns the statement: SqlIter frees it after the
 * last row, SqlRowRelease when you stop early. */
pub bool select(SqlCtx *ctx, SqlRow *row, u8 *sql,
        SqlParam *params=null, i32 paramCount=0);
/* Like SqlSelect, for a statement whose rows you don't need (INSERT,
 * UPDATE, CREATE, ...). */
pub bool exec(SqlCtx *ctx, u8 *sql, SqlParam *params=null,
        i32 paramCount=0);
/* Move row to its next row (the first one on the first call): true while
 * there is one, false after the last, when the row is released. */
pub i32 next(SqlRow *row);
/* SqlIter for a row of SqlExecPrepared (the same, but returns i64). */
pub i64 nextPrepared(SqlRow *row);
/* Release a row before its last row was read: free its columns and, for
 * a row of SqlSelect, its statement. A row of SqlExecPrepared leaves its
 * statement to SqlFinalizePrepared. Calling it again does nothing. */
pub void rowRelease(SqlRow *row);

#endif /* End of SQLite */
