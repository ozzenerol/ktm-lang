module c;

public #define SQLITE_OK           0   /* Successful result */

/* beginning-of-error-codes */
public #define SQLITE_ERROR        1   /* Generic error */

public #define SQLITE_INTERNAL     2   /* Internal logic error in SQLite */

public #define SQLITE_PERM         3   /* Access permission denied */

public #define SQLITE_ABORT        4   /* Callback routine requested an abort */

public #define SQLITE_BUSY         5   /* The database file is locked */

public #define SQLITE_LOCKED       6   /* A table in the database is locked */

public #define SQLITE_NOMEM        7   /* A malloc() failed */

public #define SQLITE_READONLY     8   /* Attempt to write a readonly database */

public #define SQLITE_INTERRUPT    9   /* Operation terminated by sqlite3_interrupt()*/

public #define SQLITE_IOERR       10   /* Some kind of disk I/O error occurred */

public #define SQLITE_CORRUPT     11   /* The database disk image is malformed */

public #define SQLITE_NOTFOUND    12   /* Unknown opcode in sqlite3_file_control() */

public #define SQLITE_FULL        13   /* Insertion failed because database is full */

public #define SQLITE_CANTOPEN    14   /* Unable to open the database file */

public #define SQLITE_PROTOCOL    15   /* Database lock protocol error */

public #define SQLITE_EMPTY       16   /* Internal use only */

public #define SQLITE_SCHEMA      17   /* The database schema changed */

public #define SQLITE_TOOBIG      18   /* String or BLOB exceeds size limit */

public #define SQLITE_CONSTRAINT  19   /* Abort due to constraint violation */

public #define SQLITE_MISMATCH    20   /* Data type mismatch */

public #define SQLITE_MISUSE      21   /* Library used incorrectly */

public #define SQLITE_NOLFS       22   /* Uses OS features not supported on host */

public #define SQLITE_AUTH        23   /* Authorization denied */

public #define SQLITE_FORMAT      24   /* Not used */

public #define SQLITE_RANGE       25   /* 2nd parameter to sqlite3_bind out of range */

public #define SQLITE_NOTADB      26   /* File opened that is not a database file */

public #define SQLITE_NOTICE      27   /* Notifications from sqlite3_log() */

public #define SQLITE_WARNING     28   /* Warnings from sqlite3_log() */

public #define SQLITE_ROW         100  /* sqlite3_step() has another row ready */

public #define SQLITE_DONE        101  /* sqlite3_step() has finished executing */

public #define SQLITE_INTEGER  1

public #define SQLITE_FLOAT    2

public #define SQLITE_BLOB     4

public #define SQLITE_NULL     5

public #define SQLITE_TEXT     3

public #define SQLITE3_TEXT    3
