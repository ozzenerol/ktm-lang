module libc;

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
