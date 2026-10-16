# SQLite

Keep a program's data in one file with SQLite, through the standard library's `sql` module. This
guide builds up a small catalogue of books, one whole program at a time: creating a table,
inserting with parameters, reading rows, NULLs, prepared statements, transactions, the settings
a real program wants, floats and blobs, and tests on a database in memory.

## What you need

The `sql` module is only in a ktm built with SQLite, which the release zips are not. Build ktm
from source with `--sqlite`. That needs sqlite3 and its headers (`libsqlite3-dev` on Debian and
Ubuntu; macOS has them):

```sh
git clone https://github.com/ozzenerol/ktm-lang && cd ktm-lang
./build.sh --sqlite --install
```

On a ktm built without it, `import sql;` still compiles but the module is empty, so the first use
of it fails:

```output
error: Variable or function `sql.Db` has not been defined
```

Every program in this guide is one file, `app.ktm`, in a directory of its own. Each one works on
`books.db` in that directory, and each step carries on from the database the last one left, so
run them in order. Build and run each one like this:

```sh
ktm app.ktm -o app
./app
```

The `sqlite3` shell is handy for looking inside the file (`sqlite3` on Debian and Ubuntu,
already on macOS), but nothing here needs it.

## Create a table and read it

```ktm
import sql;

i32 main() {
    sql.Db *db = sql.open("books.db");
    if (!db) {
        printf("can't open books.db\n");
        return 1;
    }
    defer sql.close(db);

    scoped String err = sql.execRaw(db,
        "CREATE TABLE IF NOT EXISTS books (\n"
        "    id     INTEGER PRIMARY KEY,\n"
        "    title  TEXT NOT NULL UNIQUE,\n"
        "    author TEXT NOT NULL,\n"
        "    year   INTEGER\n"
        ");\n"
        "INSERT OR IGNORE INTO books (title, author, year) VALUES ('Dune', 'Frank Herbert', 1965);\n");
    if (err.len) {
        printf("%s\n", err.cstr());
        return 1;
    }

    sql.Row row;
    if (!sql.select(db, &row, "SELECT id, title, author, year FROM books")) {
        printf("%s\n", sql.sqlite3_errmsg(db.conn));
        return 1;
    }
    while (sql.next(&row)) {
        printf("%d  %s, %s (%d)\n", row.col[0].intVal, row.col[1].str, row.col[2].str, row.col[3].intVal);
    }
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
1  Dune, Frank Herbert (1965)
```

- `sql.open(path)` opens the file, and creates it when it isn't there. It returns `null` when it
  can't. `sql.close(db)` closes it.
- `sql.execRaw(db, script)` runs any number of statements, which suits a schema. It returns the
  error as a `String`, empty when everything ran. The String is yours, hence `scoped`.
- `execRaw` takes no parameters, so never build its text from values that come from outside.
  The next section shows how values go in.
- `sql.select(db, &row, query)` runs one query, and `sql.next(&row)` steps through its rows.
  `row.col[i]` is column `i`: `.intVal` for an integer, `.str` (a `u8[]`) for text.
- `CREATE TABLE IF NOT EXISTS` and `INSERT OR IGNORE` with the `UNIQUE` title make the program
  safe to run again: the second run adds nothing.
- When a call fails, `sql.sqlite3_errmsg(db.conn)` says why.

## Insert with parameters

A `?` in the SQL is a parameter. Its value goes in an `sql.Param`, with its type, and travels
separately from the SQL, so a title with a quote in it (`Robert's Rules`) is just a title and
can't change the statement:

```ktm
import sql;

/* Adds a book and returns its id, or -1. year 0 is stored as NULL:
 * the year is not known. */
i64 addBook(sql.Db *db, u8[] title, u8[] author, i64 year) {
    sql.Param p[3];
    p[0].type = sql.TEXT;
    p[0].str = title;
    p[1].type = sql.TEXT;
    p[1].str = author;
    if (year) {
        p[2].type = sql.INT;
        p[2].intVal = year;
    } else {
        p[2].type = sql.NULL_TYPE;
    }

    sql.Row row;
    if (!sql.select(db, &row, "INSERT INTO books (title, author, year) VALUES (?, ?, ?) RETURNING id", p, 3)) {
        printf("%s\n", sql.sqlite3_errmsg(db.conn));
        return -1;
    }
    i64 id = -1;
    while (sql.next(&row)) {
        id = row.col[0].intVal;
    }
    return id;
}

i32 main() {
    sql.Db *db = sql.open("books.db");
    if (!db) {
        printf("can't open books.db\n");
        return 1;
    }
    defer sql.close(db);

    printf("added %d\n", addBook(db, "The Left Hand of Darkness", "Ursula K. Le Guin", 1969));
    printf("added %d\n", addBook(db, "Robert's Rules", "Henry M. Robert", 0));
    printf("added %d\n", addBook(db, "Neuromancer", "William Gibson", 1984));
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
added 2
added 3
added 4
```

- `sql.exec(db, query, params, count)` runs one statement that returns no rows, and
  `sql.select(db, &row, query, params, count)` one that does. Both return `false` on an error.
- `count` is how many Params there are, one per `?`, in order.
- A Param's `type` is `sql.INT` (`intVal`), `sql.FLOAT` (`floatVal`), `sql.TEXT` (`str`),
  `sql.BLOB` (`blob` and `blobLen`) or `sql.NULL_TYPE`.
- Text and blobs are copied when they are bound, so the values may be freed right after the call.
- `RETURNING id` (SQLite 3.35 and newer) gives back the new row's id. That is why `addBook` uses
  `select` for an INSERT.
- A string literal and a `u8[]` are the same kind of value, so `addBook(db, "Dune", ...)` and
  `addBook(db, someString[0:], ...)` both work.

## Read rows

```ktm
import sql, str, vec;

/* Prints the books written after `year`, newest first */
void after(sql.Db *db, i64 year) {
    sql.Param p[1];
    p[0].type = sql.INT;
    p[0].intVal = year;

    sql.Row row;
    if (!sql.select(db, &row, "SELECT title, year FROM books WHERE year > ? ORDER BY year DESC", p, 1)) {
        printf("%s\n", sql.sqlite3_errmsg(db.conn));
        return;
    }
    printf("after %d:\n", year);
    while (sql.next(&row)) {
        printf("  %d %s\n", row.col[1].intVal, row.col[0].str);
    }
}

/* Prints each book's year, or "?" when it is NULL */
void years(sql.Db *db) {
    sql.Row row;
    if (!sql.select(db, &row, "SELECT title, year FROM books ORDER BY id")) {
        printf("%s\n", sql.sqlite3_errmsg(db.conn));
        return;
    }
    while (sql.next(&row)) {
        if (row.col[1].type == sql.SQLITE_NULL) {
            printf("  ?    %s\n", row.col[0].str);
        } else {
            printf("  %d %s\n", row.col[1].intVal, row.col[0].str);
        }
    }
}

/* The titles, copied out of the rows: they outlive the query */
vec.Vec<String> titles(sql.Db *db) {
    vec.Vec<String> out;
    sql.Row row;
    if (sql.select(db, &row, "SELECT title FROM books ORDER BY title")) {
        while (sql.next(&row)) {
            out.push(str.from(row.col[0].str));
        }
    }
    return out;
}

/* The first book by an author, or false: stops after one row */
bool firstBy(sql.Db *db, u8[] author, String *title) {
    sql.Param p[1];
    p[0].type = sql.TEXT;
    p[0].str = author;

    sql.Row row;
    if (!sql.select(db, &row, "SELECT title FROM books WHERE author = ? ORDER BY year", p, 1)) {
        return false;
    }
    if (!sql.next(&row)) {
        return false;
    }
    *title = str.from(row.col[0].str);
    sql.rowRelease(&row);
    return true;
}

i32 main() {
    sql.Db *db = sql.open("books.db");
    if (!db) {
        printf("can't open books.db\n");
        return 1;
    }
    defer sql.close(db);

    after(db, 1960);
    printf("years:\n");
    years(db);

    vec.Vec<String> t = titles(db);
    printf("%d titles, the first is %s\n", t.len, t.data[0].cstr());
    for (i64 i = 0; i < t.len; i++) { t.data[i].free(); }
    t.free();

    String title;
    if (firstBy(db, "Frank Herbert", &title)) {
        printf("Frank Herbert: %s\n", title.cstr());
        title.free();
    }
    if (!firstBy(db, "Nobody", &title)) {
        printf("Nobody: no books\n");
    }
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
after 1960:
  1984 Neuromancer
  1969 The Left Hand of Darkness
  1965 Dune
years:
  1965 Dune
  1969 The Left Hand of Darkness
  ?    Robert's Rules
  1984 Neuromancer
4 titles, the first is Dune
Frank Herbert: Dune
Nobody: no books
```

- A query with no rows is not an error: `select` returns `true` and the first `next` returns
  `false`.
- `row.col[i].type` is the value's type: `sql.SQLITE_INTEGER`, `sql.SQLITE_FLOAT`,
  `sql.SQLITE_TEXT`, `sql.SQLITE_BLOB` or `sql.SQLITE_NULL`. A NULL reads as `0` from `intVal`,
  so check the type when NULL means something, as `years` does.
- `row.col[i].str` is a view of SQLite's memory, valid until the next `next`. To keep a value,
  copy it: `str.from(row.col[0].str)`, as `titles` and `firstBy` do. The copies are yours to free.
- Reading every row finishes the query by itself. To stop early, call `sql.rowRelease(&row)`, as
  `firstBy` does after the first row; without it the query stays open.

## Many rows: prepared statements and a transaction

Each `sql.exec` and `sql.select` compiles its SQL, runs it once and throws it away. For the same
statement many times, prepare it once and run it with new values each time. And put many writes
in one transaction: SQLite waits for the disk at the end of every transaction, so 10,000 inserts
on their own wait 10,000 times.

```ktm
import date, sql, str;

/* Adds `n` made-up books, one INSERT each, through one prepared statement */
bool addMany(sql.Db *db, i64 n) {
    sql.Stmt *st = sql.prepare(db, "INSERT INTO books (title, author, year) VALUES (?, ?, ?)");
    if (!st) {
        printf("%s\n", sql.sqlite3_errmsg(db.conn));
        return false;
    }
    defer sql.finalize(st);

    sql.Param p[3];
    p[0].type = sql.TEXT;
    p[1].type = sql.TEXT;
    p[1].str = "Anonymous";
    p[2].type = sql.INT;
    for (i64 i = 1; i <= n; i++) {
        scoped String title = str.format("Volume %d", i);
        p[0].str = title[0:];
        p[2].intVal = 1900 + i % 120;
        if (sql.execPrepared(st, null, p) != sql.SQLITE_DONE) {
            printf("%s\n", sql.sqlite3_errmsg(db.conn));
            return false;
        }
    }
    return true;
}

i32 main() {
    sql.Db *db = sql.open("books.db");
    if (!db) {
        printf("can't open books.db\n");
        return 1;
    }
    defer sql.close(db);

    i64 t0 = date.monoMs();
    sql.exec(db, "BEGIN");
    if (!addMany(db, 10000)) {
        sql.exec(db, "ROLLBACK");
        return 1;
    }
    sql.exec(db, "COMMIT");
    printf("10000 books in %d ms\n", date.monoMs() - t0);

    /* the same statement again, now to read: one row per year asked for */
    sql.Stmt *count = sql.prepare(db, "SELECT count(*) FROM books WHERE year = ?");
    defer sql.finalize(count);
    sql.Param y[1];
    y[0].type = sql.INT;
    for (i64 year = 1950; year <= 1952; year++) {
        y[0].intVal = year;
        sql.Row row;
        if (sql.execPrepared(count, &row, y) == sql.SQLITE_ROW && sql.nextPrepared(&row)) {
            printf("%d: %d books\n", year, row.col[0].intVal);
        }
        sql.rowRelease(&row);
    }
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
10000 books in 11 ms
1950: 83 books
1951: 83 books
1952: 83 books
```

- `sql.prepare(db, query)` compiles a statement, or returns `null` with the reason in
  `sqlite3_errmsg`. `sql.finalize(st)` frees it.
- `sql.execPrepared(st, &row, params)` runs it with these values, as many as the statement has
  `?`s. It returns `sql.SQLITE_DONE` when the statement finished and `sql.SQLITE_ROW` when there is
  a row to read. Pass `null` for the row when there is nothing to read.
- Read a prepared statement's rows with `sql.nextPrepared(&row)`, and call `sql.rowRelease(&row)`
  after each run.
- `BEGIN` and `COMMIT` around the loop make the 10,000 inserts one transaction. Without them the
  same program took 12 seconds instead of 11 ms, on the laptop disk the times above come from.
- Prepare once and reuse: the second statement counts the books of three years without
  compiling its SQL again.

## Transactions

A transaction makes several statements one change: all of them happen, or none. Run `BEGIN`,
the statements, then `COMMIT`; when one fails, `ROLLBACK` undoes the ones before it.

```ktm
import sql;

/* The number the query answers with, -1 on an error */
i64 count(sql.Db *db, u8[] query) {
    sql.Row row;
    i64 n = -1;
    if (sql.select(db, &row, query)) {
        while (sql.next(&row)) { n = row.col[0].intVal; }
    }
    return n;
}

/* One statement of a transaction: false, with the error printed, when it fails */
bool step(sql.Db *db, u8[] query, sql.Param *p, i32 n) {
    if (sql.exec(db, query, p, n)) { return true; }
    printf("  failed: %s\n", sql.sqlite3_errmsg(db.conn));
    return false;
}

/* Moves an author's books from books to archive: both steps, or neither */
bool archive(sql.Db *db, u8[] author) {
    sql.Param p[1];
    p[0].type = sql.TEXT;
    p[0].str = author;

    sql.exec(db, "BEGIN");
    if (!step(db, "INSERT INTO archive (title, author) SELECT title, author FROM books WHERE author = ?", p, 1) ||
        !step(db, "DELETE FROM books WHERE author = ?", p, 1)) {
        sql.exec(db, "ROLLBACK");
        return false;
    }
    return sql.exec(db, "COMMIT");
}

void counts(sql.Db *db) {
    printf("  books by Frank Herbert: %d, archived: %d\n",
           count(db, "SELECT count(*) FROM books WHERE author = 'Frank Herbert'"),
           count(db, "SELECT count(*) FROM archive"));
}

i32 main() {
    sql.Db *db = sql.open("books.db");
    if (!db) {
        printf("can't open books.db\n");
        return 1;
    }
    defer sql.close(db);
    sql.exec(db, "CREATE TABLE IF NOT EXISTS archive (title TEXT PRIMARY KEY, author TEXT NOT NULL)");

    printf("archive Frank Herbert:\n");
    archive(db, "Frank Herbert");
    counts(db);

    printf("add Dune again and archive it again:\n");
    sql.exec(db, "INSERT INTO books (title, author, year) VALUES ('Dune', 'Frank Herbert', 1965)");
    archive(db, "Frank Herbert");
    counts(db);
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
archive Frank Herbert:
  books by Frank Herbert: 0, archived: 1
add Dune again and archive it again:
  failed: UNIQUE constraint failed: archive.title
  books by Frank Herbert: 1, archived: 1
```

The second time, `Dune` was already in the archive, so the `INSERT` failed. The `ROLLBACK` kept
the new `Dune` in `books` instead of deleting it with nothing archived.

- `sql.exec(db, "BEGIN")`, `"COMMIT"` and `"ROLLBACK"` return `false` when they fail, like any
  statement.
- Until `COMMIT`, other connections to the file don't see the changes.
- `BEGIN IMMEDIATE` takes the write lock at once, instead of at the first write. Use it when two
  programs write to one file, so neither finds out halfway that the other got there first.

## The settings a real program wants

SQLite's defaults are careful and old. A program that serves requests usually sets four things
when it opens the file:

```ktm
import sql;

/* Runs a PRAGMA and prints what it answers */
void pragma(sql.Db *db, u8[] query) {
    sql.Row row;
    if (!sql.select(db, &row, query)) {
        printf("%s: %s\n", query, sql.sqlite3_errmsg(db.conn));
        return;
    }
    while (sql.next(&row)) {
        if (row.col[0].type == sql.SQLITE_TEXT) {
            printf("%s -> %s\n", query, row.col[0].str);
        } else {
            printf("%s -> %d\n", query, row.col[0].intVal);
        }
    }
}

/* Opens a database the way a server wants it */
sql.Db *openDb(u8[] path) {
    sql.Db *db = sql.open(path);
    if (!db) { return null; }
    sql.exec(db, "PRAGMA journal_mode = WAL");     /* readers don't wait for the writer */
    sql.exec(db, "PRAGMA busy_timeout = 5000");    /* wait up to 5 s for a lock */
    sql.exec(db, "PRAGMA foreign_keys = ON");      /* check REFERENCES */
    sql.exec(db, "PRAGMA synchronous = NORMAL");   /* safe with WAL, much faster */
    return db;
}

i32 main() {
    sql.Db *db = openDb("books.db");
    if (!db) {
        printf("can't open books.db\n");
        return 1;
    }
    defer sql.close(db);

    pragma(db, "PRAGMA journal_mode");
    pragma(db, "PRAGMA busy_timeout");
    pragma(db, "PRAGMA foreign_keys");
    pragma(db, "PRAGMA synchronous");
    pragma(db, "SELECT sqlite_version()");
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
PRAGMA journal_mode -> wal
PRAGMA busy_timeout -> 5000
PRAGMA foreign_keys -> 1
PRAGMA synchronous -> 1
SELECT sqlite_version() -> 3.46.1
```

- **`journal_mode = WAL`** lets readers read while one writer writes. It is stored in the file,
  so it stays on. While the database is open there are two more files next to it,
  `books.db-wal` and `books.db-shm`; copy all three when you copy a database that is in use, or
  use `sqlite3 books.db ".backup copy.db"`.
- **`busy_timeout`** makes a statement wait for another connection's lock, here up to 5 seconds,
  instead of failing at once with `database is locked`.
- **`foreign_keys = ON`** makes SQLite check `REFERENCES` and run `ON DELETE CASCADE`. It is off
  in every new connection, so set it every time.
- **`synchronous = NORMAL`** waits for the disk less often. With WAL it can't corrupt the file;
  a power cut can lose the last transactions, never half of one.
- Use one `sql.Db` per thread. To share one database between threads, give each its own
  connection to the file, and let `busy_timeout` handle the waiting.

## Floats and blobs

```ktm
import sql;

i32 main() {
    sql.Db *db = sql.open(":memory:");
    defer sql.close(db);
    sql.exec(db, "CREATE TABLE covers (title TEXT, price REAL, png BLOB)");

    u8 png[8] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    sql.Param p[3];
    p[0].type = sql.TEXT;
    p[0].str = "Dune";
    p[1].type = sql.FLOAT;
    p[1].floatVal = 9.99;
    p[2].type = sql.BLOB;
    p[2].blob = png;
    p[2].blobLen = 8;
    if (!sql.exec(db, "INSERT INTO covers VALUES (?, ?, ?)", p, 3)) {
        printf("%s\n", sql.sqlite3_errmsg(db.conn));
        return 1;
    }

    sql.Row row;
    sql.select(db, &row, "SELECT title, price, png, typeof(price), typeof(png) FROM covers");
    while (sql.next(&row)) {
        u8 *bytes = row.col[2].blob;
        printf("%s costs %.2f; the cover is %d bytes, starting %02x %c%c%c\n", row.col[0].str,
               row.col[1].floatVal, row.col[2].len, bytes[0], bytes[1], bytes[2], bytes[3]);
        printf("stored as %s and %s\n", row.col[3].str, row.col[4].str);
    }
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
Dune costs 9.99; the cover is 8 bytes, starting 89 PNG
stored as real and blob
```

- A float goes in as `sql.FLOAT` with `floatVal`, and comes out in `row.col[i].floatVal`.
- A blob goes in as `sql.BLOB` with `blob` (a pointer) and `blobLen`. It comes out as
  `row.col[i].blob` and `row.col[i].len`: like text, a view that lasts until the next row.
- SQLite stores what it is given: `typeof()` shows how each value was kept.

## Tests on a database in memory

`sql.open(":memory:")` opens a database that lives in memory and is gone when it is closed. Each
test gets an empty one, with nothing on disk to clean up. Put this next to your program as
`books_test.ktm`:

```ktm
import sql, testing;

/* A fresh database in memory for each test: nothing on disk, nothing
 * left over from the last run */
sql.Db *testDb() {
    sql.Db *db = sql.open(":memory:");
    sql.exec(db, "CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT NOT NULL UNIQUE, "
                 "author TEXT NOT NULL, year INTEGER)");
    return db;
}

i64 count(sql.Db *db) {
    sql.Row row;
    i64 n = -1;
    if (sql.select(db, &row, "SELECT count(*) FROM books")) {
        while (sql.next(&row)) { n = row.col[0].intVal; }
    }
    return n;
}

void testInsert(testing.T *t) {
    sql.Db *db = testDb();
    defer sql.close(db);
    t.check(sql.exec(db, "INSERT INTO books (title, author) VALUES ('Dune', 'Frank Herbert')"), "insert");
    t.equal(count(db), 1);
}

void testTitlesAreUnique(testing.T *t) {
    sql.Db *db = testDb();
    defer sql.close(db);
    sql.exec(db, "INSERT INTO books (title, author) VALUES ('Dune', 'Frank Herbert')");
    t.check(!sql.exec(db, "INSERT INTO books (title, author) VALUES ('Dune', 'Someone Else')"), "a second Dune");
    t.equal(sql.sqlite3_errmsg(db.conn), "UNIQUE constraint failed: books.title");
    t.equal(count(db), 1);
}
```

```sh
ktm test
```

```output
ok  	.	0.00s  (2 passed)
```

- Each `test...(testing.T *t)` function is a test. `t.check(ok, "what")` and `t.equal(got,
  want)` report a failure without stopping the others.
- A failed `t.equal` shows both values:

```output
--- FAIL: testTitlesAreUnique (0.00s)
    books_test.ktm:33: got "UNIQUE constraint failed: books.title", want "UNIQUE constraint failed: books.titl"
```

- `ktm test -jit -Memsafe` runs the same tests in the JIT and reports any memory the code never
  freed.

## Reference

| Call | What it does |
| --- | --- |
| `sql.open(path)` | opens or creates the database; `":memory:"` for one in memory; `null` on failure |
| `sql.close(db)` | closes it |
| `sql.execRaw(db, script)` | any number of statements, no parameters; the error as a `String`, empty when it ran |
| `sql.exec(db, query, params, count)` | one statement; `false` on an error |
| `sql.select(db, &row, query, params, count)` | one query whose rows `sql.next` reads; `false` on an error |
| `sql.next(&row)` | the next row of `select`; `false` after the last |
| `sql.rowRelease(&row)` | ends a query before its last row |
| `sql.prepare(db, query)` | a statement to run many times; `null` on an error |
| `sql.execPrepared(st, &row, params)` | runs it; `sql.SQLITE_DONE`, `sql.SQLITE_ROW`, or an error code |
| `sql.nextPrepared(&row)` | the next row of `execPrepared` |
| `sql.finalize(st)` | frees a prepared statement |
| `sql.sqlite3_errmsg(db.conn)` | why the last call on `db` failed |

| Param type | Field |
| --- | --- |
| `sql.INT` | `intVal` (i64) |
| `sql.FLOAT` | `floatVal` (f64) |
| `sql.TEXT` | `str` (u8[]) |
| `sql.BLOB` | `blob` (pointer) and `blobLen` |
| `sql.NULL_TYPE` | none |

| Column type | Field |
| --- | --- |
| `sql.SQLITE_INTEGER` | `intVal` |
| `sql.SQLITE_FLOAT` | `floatVal` |
| `sql.SQLITE_TEXT` | `str`, and `len` |
| `sql.SQLITE_BLOB` | `blob` and `len` |
| `sql.SQLITE_NULL` | none: `intVal` reads 0 |

## Rules of thumb

- **Values go in Params, never into the SQL text.** That keeps out SQL injection and quoting
  bugs.
- **Write many rows in one transaction.** It is the difference between milliseconds and seconds.
- **Turn on WAL, a busy timeout and foreign keys** when you open the file.
- **Copy what you keep from a row** before the next `next`.
- **Release a query you stop reading early** with `sql.rowRelease`.
- **Manage the schema with migrations** once the program is in use: the
  [database migrations guide](01-database-migrations.html) uses SQLite too.
