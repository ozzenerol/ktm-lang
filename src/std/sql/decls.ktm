module sql;

#ifdef __KTM_LINK_SQLITE3__ 

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

pub const i64 INT = SQLITE_INTEGER;
pub const i64 FLOAT = SQLITE_FLOAT;
pub const i64 TEXT = SQLITE3_TEXT;
pub const i64 BLOB = SQLITE_BLOB;
pub const i64 NULL_TYPE = SQLITE_NULL;

pub struct sqlite3_stmt {};
pub struct sqlite3 {};
pub struct sqlite3_value {};

/* A value for a `?` parameter: type is sql.INT (i64), sql.FLOAT (f64),
 * sql.TEXT (str, a u8[]), sql.BLOB (blob, blobLen bytes) or sql.NULL_TYPE.
 * Text and blobs are copied when bound, so the caller may free or reuse
 * them right after the call. */
pub struct Param {
    i64 type;
    union {
        i64 intVal;
        f64 floatVal;
        u8[] str;
        struct {
            void *blob;
            i64 blobLen;
        };
    };
};

pub struct Stmt {
    sqlite3_stmt *stmt;
    i64  paramsCount;
};

/* One column of the current row: type is sql.SQLITE_INTEGER,
 * SQLITE_FLOAT, SQLITE_TEXT, SQLITE_BLOB or SQLITE_NULL. str (a u8[]) and
 * blob are views of sqlite's memory, only valid until the next next(),
 * nextPrepared() or rowRelease(); len is their size in bytes, 0 for the
 * other types. */
pub struct Column {
    i64 len;
    i64 type;
    union
    {
        i64 intVal;
        u8[] str;
        void *blob;
        f64 floatVal;
    };
};

/* The current row of a statement: col[0] .. col[cols - 1]. */
pub struct Row {
    sqlite3_stmt *stmt;
    i64 cols;
    Column *col;
    /* false for a row of a prepared statement, which sql.finalize
     * finalizes. */
    bool ownsStmt;
};

pub struct Db {
    sqlite3 *conn;
    String dbname;  /* its own copy */
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
 * printerror is true. The Db keeps its own copy of dbname. */
pub Db *open(u8[] dbname, bool printerror=false);
/* Close the database and free ctx. Statements not finalized yet keep
 * the connection open until sql.finalize. null is ignored. */
pub void close(Db *ctx);
/* Compile one SQL statement with `?` parameters, to run many times with
 * sql.execPrepared. Only whitespace and comments may follow it. null on
 * an error; free it with sql.finalize. */
pub Stmt *prepare(Db *ctx, u8[] sql);
/* Free a statement of sql.prepare. null is ignored. */
pub void finalize(Stmt *pstmt);
/* Run every statement in sql, with no parameters (so never with values
 * from outside the program). An empty String on success, otherwise the
 * error message as a new String (yours). */
pub String execRaw(Db *ctx, u8[] sql);
/* Run a prepared statement with params[0] .. params[n - 1] for its n
 * parameters (null params binds them all to null). Returns sqlite's
 * code: SQLITE_ROW when row now holds the first row (read the rest with
 * sql.nextPrepared), SQLITE_DONE when there are no rows, or an error:
 * SQLITE_MISUSE for a null pstmt, SQLITE_MISMATCH for a parameter type
 * other than SQL_INT/FLOAT/TEXT/BLOB/null, or the code of a failed bind
 * (e.g. SQLITE_TOOBIG). On an error the statement does not run and row
 * holds no row. The row belongs to the statement:
 * it is valid until the next sql.execPrepared or sql.finalize. */
pub i32 execPrepared(Stmt *pstmt, Row *row,
        Param *params);
/* Run one SQL statement with param_count parameters and point row at
 * its first row, if any; read the rows with sql.next. true if the
 * statement ran (also with no rows), false on an error, an unknown
 * parameter type or anything but whitespace and comments after the
 * statement. The row owns the statement: sql.next frees it after the
 * last row, sql.rowRelease when you stop early. */
pub bool select(Db *ctx, Row *row, u8[] sql,
        Param *params=null, i32 paramCount=0);
/* Like sql.select, for a statement whose rows you don't need (INSERT,
 * UPDATE, CREATE, ...). */
pub bool exec(Db *ctx, u8[] sql, Param *params=null,
        i32 paramCount=0);
/* Move row to its next row (the first one on the first call): true while
 * there is one, false after the last, when the row is released. */
pub i32 next(Row *row);
/* sql.next for a row of sql.execPrepared (the same, but returns i64). */
pub i64 nextPrepared(Row *row);
/* Release a row before its last row was read: free its columns and, for
 * a row of sql.select, its statement. A row of sql.execPrepared leaves its
 * statement to sql.finalize. Calling it again does nothing. */
pub void rowRelease(Row *row);

#endif /* End of SQLite */
