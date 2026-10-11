module libc;

pub #define SQLITE_OK           0   /* Successful result */

/* beginning-of-error-codes */
pub #define SQLITE_ERROR        1   /* Generic error */

pub #define SQLITE_INTERNAL     2   /* Internal logic error in SQLite */

pub #define SQLITE_PERM         3   /* Access permission denied */

pub #define SQLITE_ABORT        4   /* Callback routine requested an abort */

pub #define SQLITE_BUSY         5   /* The database file is locked */

pub #define SQLITE_LOCKED       6   /* A table in the database is locked */

pub #define SQLITE_NOMEM        7   /* A malloc() failed */

pub #define SQLITE_READONLY     8   /* Attempt to write a readonly database */

pub #define SQLITE_INTERRUPT    9   /* Operation terminated by sqlite3_interrupt()*/

pub #define SQLITE_IOERR       10   /* Some kind of disk I/O error occurred */

pub #define SQLITE_CORRUPT     11   /* The database disk image is malformed */

pub #define SQLITE_NOTFOUND    12   /* Unknown opcode in sqlite3_file_control() */

pub #define SQLITE_FULL        13   /* Insertion failed because database is full */

pub #define SQLITE_CANTOPEN    14   /* Unable to open the database file */

pub #define SQLITE_PROTOCOL    15   /* Database lock protocol error */

pub #define SQLITE_EMPTY       16   /* Internal use only */

pub #define SQLITE_SCHEMA      17   /* The database schema changed */

pub #define SQLITE_TOOBIG      18   /* String or BLOB exceeds size limit */

pub #define SQLITE_CONSTRAINT  19   /* Abort due to constraint violation */

pub #define SQLITE_MISMATCH    20   /* Data type mismatch */

pub #define SQLITE_MISUSE      21   /* Library used incorrectly */

pub #define SQLITE_NOLFS       22   /* Uses OS features not supported on host */

pub #define SQLITE_AUTH        23   /* Authorization denied */

pub #define SQLITE_FORMAT      24   /* Not used */

pub #define SQLITE_RANGE       25   /* 2nd parameter to sqlite3_bind out of range */

pub #define SQLITE_NOTADB      26   /* File opened that is not a database file */

pub #define SQLITE_NOTICE      27   /* Notifications from sqlite3_log() */

pub #define SQLITE_WARNING     28   /* Warnings from sqlite3_log() */

pub #define SQLITE_ROW         100  /* sqlite3_step() has another row ready */

pub #define SQLITE_DONE        101  /* sqlite3_step() has finished executing */

pub #define SQLITE_INTEGER  1

pub #define SQLITE_FLOAT    2

pub #define SQLITE_BLOB     4

pub #define SQLITE_NULL     5

pub #define SQLITE_TEXT     3

pub #define SQLITE3_TEXT    3
