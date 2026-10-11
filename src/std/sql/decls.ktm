module sql;

#ifdef __KTM_LINK_SQLITE3__ 

#define SQLITE_OK           0   /* Successful result */
/* beginning-of-error-codes */
#define SQLITE_ERROR        1   /* Generic error */
#define SQLITE_INTERNAL     2   /* Internal logic error in SQLite */
#define SQLITE_PERM         3   /* Access permission denied */
#define SQLITE_ABORT        4   /* Callback routine requested an abort */
#define SQLITE_BUSY         5   /* The database file is locked */
#define SQLITE_LOCKED       6   /* A table in the database is locked */
#define SQLITE_NOMEM        7   /* A malloc() failed */
#define SQLITE_READONLY     8   /* Attempt to write a readonly database */
#define SQLITE_INTERRUPT    9   /* Operation terminated by sqlite3_interrupt()*/
#define SQLITE_IOERR       10   /* Some kind of disk I/O error occurred */
#define SQLITE_CORRUPT     11   /* The database disk image is malformed */
#define SQLITE_NOTFOUND    12   /* Unknown opcode in sqlite3_file_control() */
#define SQLITE_FULL        13   /* Insertion failed because database is full */
#define SQLITE_CANTOPEN    14   /* Unable to open the database file */
#define SQLITE_PROTOCOL    15   /* Database lock protocol error */
#define SQLITE_EMPTY       16   /* Internal use only */
#define SQLITE_SCHEMA      17   /* The database schema changed */
#define SQLITE_TOOBIG      18   /* String or BLOB exceeds size limit */
#define SQLITE_CONSTRAINT  19   /* Abort due to constraint violation */
#define SQLITE_MISMATCH    20   /* Data type mismatch */
#define SQLITE_MISUSE      21   /* Library used incorrectly */
#define SQLITE_NOLFS       22   /* Uses OS features not supported on host */
#define SQLITE_AUTH        23   /* Authorization denied */
#define SQLITE_FORMAT      24   /* Not used */
#define SQLITE_RANGE       25   /* 2nd parameter to sqlite3_bind out of range */
#define SQLITE_NOTADB      26   /* File opened that is not a database file */
#define SQLITE_NOTICE      27   /* Notifications from sqlite3_log() */
#define SQLITE_WARNING     28   /* Warnings from sqlite3_log() */
#define SQLITE_ROW         100  /* sqlite3_step() has another row ready */
#define SQLITE_DONE        101  /* sqlite3_step() has finished executing */

#define SQLITE_INTEGER  1
#define SQLITE_FLOAT    2
#define SQLITE_BLOB     4
#define SQLITE_NULL     5
#define SQLITE_TEXT     3
#define SQLITE3_TEXT    3

#define SQL_INT   SQLITE_INTEGER
#define SQL_FLOAT SQLITE_FLOAT
#define SQL_TEXT  SQLITE3_TEXT
#define SQL_BLOB  SQLITE_BLOB
#define SQL_NULL  SQLITE_NULL

class sqlite3_stmt {};
class sqlite3 {};
class sqlite3_value {};

/* A value for a `?` parameter: type is SQL_INT (i64), SQL_FLOAT (f64),
 * SQL_TEXT (str, NUL-terminated), SQL_BLOB (blob, blob_len bytes) or
 * SQL_NULL. Text and blobs are copied when bound, so the caller may free
 * or reuse them right after the call. */
class SqlParam
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

class SqlPreparedStmt
{
  sqlite3_stmt *stmt;
  I64  params_count;
};

/* One column of the current row: type is SQL_INT, SQL_FLOAT, SQL_TEXT,
 * SQL_BLOB or SQL_NULL. str and blob point into sqlite's memory and are
 * only valid until the next SqlIter/SqlIterPrepared or SqlRowRelease;
 * len is their size in bytes, 0 for the other types. */
class SqlColumn
{
  I64 len;
  I64 type;
  union
  {
    I64 i64;
    U8 *str;
    U0 *blob;
    F64 f64;
  };
};

/* The current row of a statement: col[0] .. col[cols - 1]. */
class SqlRow
{
  sqlite3_stmt *stmt;
  I64 cols;
  SqlColumn *col;
  /* FALSE for a row of a prepared statement, which SqlFinalizePrepared
   * finalizes. */
  Bool owns_stmt;
};

class SqlCtx
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
public extern "c" I32 sqlite3_column_bytes(sqlite3_stmt *stmt, I32 iCol);
public extern "c" I32 sqlite3_column_bytes16(sqlite3_stmt *stmt, I32 iCol);
public extern "c" I32 sqlite3_column_type(sqlite3_stmt *stmt, I32 iCol);
public extern "c" I32 sqlite3_open(U8 *dbname, sqlite3 **db);
public extern "c" I32 sqlite3_close_v2(sqlite3 *db);
public extern "c" U8 *sqlite3_errmsg(sqlite3 *db);

/* Open (or create) the database file dbname, ":memory:" for a private
 * in-memory one. NULL on failure, printing sqlite's message first when
 * printerror is TRUE. dbname is kept, not copied. */
public SqlCtx *SqlCtxNew(U8 *dbname, Bool printerror=FALSE);
/* Close the database and free ctx. Statements not finalized yet keep
 * the connection open until SqlFinalizePrepared. NULL is ignored. */
public U0 SqlCtxRelease(SqlCtx *ctx);
/* Compile one SQL statement with `?` parameters, to run many times with
 * SqlExecPrepared. Only whitespace and comments may follow it. NULL on
 * an error; free it with SqlFinalizePrepared. */
public SqlPreparedStmt *SqlPrepare(SqlCtx *ctx, U8 *Sql);
/* Free a statement of SqlPrepare. NULL is ignored. */
public U0 SqlFinalizePrepared(SqlPreparedStmt *pstmt);
/* Run every statement in sql, with no parameters (so never with values
 * from outside the program). NULL on success, otherwise an error message
 * to Free. */
public U8 *SqlExecRaw(SqlCtx *ctx, U8 *sql);
/* Run a prepared statement with params[0] .. params[n - 1] for its n
 * parameters (NULL params binds them all to NULL). Returns sqlite's
 * code: SQLITE_ROW when row now holds the first row (read the rest with
 * SqlIterPrepared), SQLITE_DONE when there are no rows, or an error:
 * SQLITE_MISUSE for a NULL pstmt, SQLITE_MISMATCH for a parameter type
 * other than SQL_INT/FLOAT/TEXT/BLOB/NULL, or the code of a failed bind
 * (e.g. SQLITE_TOOBIG). On an error the statement does not run and row
 * holds no row. The row belongs to the statement:
 * it is valid until the next SqlExecPrepared or SqlFinalizePrepared. */
public I32 SqlExecPrepared(SqlPreparedStmt *pstmt, SqlRow *row,
    SqlParam *params);
/* Run one SQL statement with param_count parameters and point row at
 * its first row, if any; read the rows with SqlIter. TRUE if the
 * statement ran (also with no rows), FALSE on an error, an unknown
 * parameter type or anything but whitespace and comments after the
 * statement. The row owns the statement: SqlIter frees it after the
 * last row, SqlRowRelease when you stop early. */
public Bool SqlSelect(SqlCtx *ctx, SqlRow *row, U8 *sql,
    SqlParam *params=NULL, I32 param_count=0);
/* Like SqlSelect, for a statement whose rows you don't need (INSERT,
 * UPDATE, CREATE, ...). */
public Bool SqlQuery(SqlCtx *ctx, U8 *sql, SqlParam *params=NULL,
    I32 param_count=0);
/* Move row to its next row (the first one on the first call): TRUE while
 * there is one, FALSE after the last, when the row is released. */
public I32 SqlIter(SqlRow *row);
/* SqlIter for a row of SqlExecPrepared (the same, but returns I64). */
public I64 SqlIterPrepared(SqlRow *row);
/* Release a row before its last row was read: free its columns and, for
 * a row of SqlSelect, its statement. A row of SqlExecPrepared leaves its
 * statement to SqlFinalizePrepared. Calling it again does nothing. */
public U0 SqlRowRelease(SqlRow *row);

#endif /* End of SQLite */
