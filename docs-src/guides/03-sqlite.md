# SQLite

Keep a program's data in one file with SQLite, through the standard library's `sql` module. This
guide builds up a small catalogue of books, one whole program at a time: creating a table,
statements with arguments, reading rows into structs, NULLs, errors, transactions, the settings a
real program wants, enums, blobs and JSON columns, tests on a database in memory, and prepared
statements for the fastest bulk inserts.

## What you need

ktm v0.26.0 or newer, built with SQLite. The `sql` module is only in a ktm built from source, which
links SQLite by default (older versions needed `--sqlite`). The release zips don't have it.
That needs sqlite3 and its headers (`libsqlite3-dev` on Debian and Ubuntu; macOS has them):

```sh
git clone https://github.com/ozzenerol/ktm-lang && cd ktm-lang
./build.sh --install
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

## Create a table and add rows

```ktm
import sql;

i32 main() {
    sql.Db *db = sql.open("books.db");
    if (!db) {
        printf("can't open books.db\n");
        return 1;
    }
    defer sql.close(db);

    db.exec("CREATE TABLE IF NOT EXISTS books (\n"
            "    id     INTEGER PRIMARY KEY,\n"
            "    title  TEXT NOT NULL UNIQUE,\n"
            "    author TEXT NOT NULL,\n"
            "    year   INTEGER\n"
            ")");

    i64 n = db.exec("INSERT OR IGNORE INTO books (title, author, year) VALUES (?, ?, ?)",
                    "Dune", "Frank Herbert", 1965);
    printf("%d book(s) added\n", n);

    i64 id = sql.queryOne<i64>(db, "INSERT INTO books (title, author, year) VALUES (?, ?, ?) "
                                   "ON CONFLICT (title) DO UPDATE SET year = excluded.year RETURNING id",
                               "Neuromancer", "William Gibson", 1984);
    printf("Neuromancer has id %d\n", id);

    i64 count = sql.queryOne<i64>(db, "SELECT count(*) FROM books");
    printf("%d book(s) in all\n", count);
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
1 book(s) added
Neuromancer has id 2
2 book(s) in all
```

Run it again and nothing is added: `INSERT OR IGNORE` and `ON CONFLICT` turn the inserts into
no-ops, so the program is safe to run any number of times.

```sh
./app
```

```output
0 book(s) added
Neuromancer has id 2
2 book(s) in all
```

- `sql.open(path)` opens the file, and creates it when it isn't there. It returns `null` when it
  can't. `sql.close(db)` closes it; `defer` it right after opening.
- `db.exec(query, args...)` runs one statement and returns how many rows it inserted, changed or
  deleted.
- Each `?` in the SQL takes the next argument. The values are sent apart from the SQL, never
  pasted into it, so a title with a quote in it (`Robert's Rules`) is just a title and can't
  change the statement.
- `sql.queryOne<T>(db, query, args...)` returns the first row. With a `T` that isn't a struct, such
  as `i64`, it is the first column: here the new id from `RETURNING id` (SQLite 3.35 and newer),
  and the count.
- `db.lastInsertId()` is the id of the last row this connection inserted, for an `INSERT` without
  `RETURNING`.

## Read rows into structs

```ktm
import reflect, sql, vec;

struct Book {
    i64 id;
    String title;
    String author;
    i64 *year;      /* a pointer: null when the column is NULL */
};

void print(Book *b) {
    if (b.year) {
        printf("%3d  %s, %s (%d)\n", b.id, b.title.cstr(), b.author.cstr(), *b.year);
    } else {
        printf("%3d  %s, %s (year unknown)\n", b.id, b.title.cstr(), b.author.cstr());
    }
}

i32 main() {
    sql.Db *db = sql.open("books.db");
    if (!db) {
        printf("can't open books.db\n");
        return 1;
    }
    defer sql.close(db);

    i64 *noYear = null;
    db.exec("INSERT OR IGNORE INTO books (title, author, year) VALUES (?, ?, ?)",
            "Robert's Rules", "Henry M. Robert", noYear);
    db.exec("INSERT OR IGNORE INTO books (title, author, year) VALUES (?, ?, ?)",
            "The Left Hand of Darkness", "Ursula K. Le Guin", 1969);

    /* every row, into a vector of structs */
    vec.Vec<Book> all = sql.queryAll<Book>(db, "SELECT id, title, author, year FROM books ORDER BY id");
    defer sql.free(reflect.of(all));
    for (auto b : all) {
        print(&b);
    }

    /* one row */
    Book dune = sql.queryOne<Book>(db, "SELECT * FROM books WHERE title = ?", "Dune");
    defer sql.free(reflect.of(dune));
    printf("found: ");
    print(&dune);

    /* no row: queryOne throws sql.ERR_NO_ROWS */
    try {
        Book none = sql.queryOne<Book>(db, "SELECT * FROM books WHERE id = ?", 999);
        sql.free(reflect.of(none));
    } catch (Error e) {
        if (e.code == sql.ERR_NO_ROWS) {
            printf("no book 999\n");
        } else {
            throw(e);
        }
    }

    /* not a struct: the first column */
    vec.Vec<String> authors = sql.queryAll<String>(db, "SELECT DISTINCT author FROM books ORDER BY author");
    defer sql.free(reflect.of(authors));
    printf("%d authors, the first is %s\n", authors.len, authors.data[0].cstr());
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
  1  Dune, Frank Herbert (1965)
  2  Neuromancer, William Gibson (1984)
  3  Robert's Rules, Henry M. Robert (year unknown)
  4  The Left Hand of Darkness, Ursula K. Le Guin (1969)
found:   1  Dune, Frank Herbert (1965)
no book 999
4 authors, the first is Frank Herbert
```

- `sql.queryAll<T>` returns every row as a `vec.Vec<T>`, and `sql.queryOne<T>` the first row as a
  `T`.
- Columns fill the fields with the same name, exactly or ignoring case. `SELECT *` works; a column
  with no field is left out, and a field with no column stays zero. `@key("column")` on a field
  reads a column of another name, and `@skip` leaves a field alone.
- A NULL in an `i64` field reads as 0. To tell NULL from 0, make the field a pointer: `year` is
  `null` for NULL and points to the value otherwise. A null pointer argument (`noYear`) is stored
  as NULL.
- `sql.free(reflect.of(x))` frees what reading allocated: the Strings, the vector, the pointers.
- `queryOne` with no row throws `sql.ERR_NO_ROWS`. When "none" is a normal answer, `queryAll` and
  a check of `len` read better.
- With a `T` that isn't a struct, `queryAll` returns the first column of every row:
  `vec.Vec<String>` of authors.

## Errors

Every failure throws `sql.ERR_SQL`, with SQLite's message and the statement. `db.errorCode()` says
which constraint a statement broke:

```ktm
import sql;

/* Adds a book; false when one with that title is already there */
bool addBook(sql.Db *db, u8[] title, u8[] author) {
    try {
        db.exec("INSERT INTO books (title, author) VALUES (?, ?)", title, author);
    } catch (Error e) {
        if (db.errorCode() == sql.CONSTRAINT_UNIQUE) {
            return false;
        }
        throw(e);
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

    if (!addBook(db, "Dune", "Frank Herbert")) {
        printf("Dune is already there\n");
    }

    try {
        db.exec("INSERT INTO books (title) VALUES (?)", "No Author");
    } catch (Error e) {
        printf("%s\n", e.message);
    }
    try {
        db.exec("SELECT * FROM book");
    } catch (Error e) {
        printf("%s\n", e.message);
    }
    try {
        db.exec("UPDATE books SET year = ? WHERE id = ?", 1965);
    } catch (Error e) {
        printf("%s\n", e.message);
    }
    printf("the database still works: %d books\n", sql.queryOne<i64>(db, "SELECT count(*) FROM books"));
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
Dune is already there
sql: NOT NULL constraint failed: books.author (in: INSERT INTO books (title) VALUES (?))
sql: no such table: book (in: SELECT * FROM book)
sql: the query has 2 parameter(s), and 1 argument(s) were given: UPDATE books SET year = ? WHERE id = ?
the database still works: 4 books
```

- `db.errorCode()` is SQLite's code for the last failure on the connection:
  `sql.CONSTRAINT_UNIQUE`, `sql.CONSTRAINT_NOTNULL`, `sql.CONSTRAINT_FOREIGNKEY`,
  `sql.CONSTRAINT_CHECK`, `sql.CONSTRAINT_PRIMARYKEY`, or `sql.SQLITE_BUSY` when another connection
  held a lock too long.
- More or fewer arguments than `?`s, or an argument SQLite has no place for, throws `sql.ERR_ARG`.
- A column that can't go into its field (text into an `i64`) throws `sql.ERR_SCAN`.
- A failed statement changes nothing and doesn't break the connection: the next one works.

## Transactions

A transaction makes several statements one change: all of them happen, or none. Run `BEGIN`,
the statements, then `COMMIT`; when one fails, `ROLLBACK` undoes the ones before it.

```ktm
import date, sql, str;

/* Moves an author's books from books to archive: both steps, or neither */
void archive(sql.Db *db, u8[] author) {
    db.exec("BEGIN");
    try {
        db.exec("INSERT INTO archive (title, author) SELECT title, author FROM books WHERE author = ?", author);
        db.exec("DELETE FROM books WHERE author = ?", author);
    } catch (Error e) {
        db.exec("ROLLBACK");
        throw(e);
    }
    db.exec("COMMIT");
}

void counts(sql.Db *db) {
    printf("  books by Frank Herbert: %d, archived: %d\n",
           sql.queryOne<i64>(db, "SELECT count(*) FROM books WHERE author = ?", "Frank Herbert"),
           sql.queryOne<i64>(db, "SELECT count(*) FROM archive"));
}

i32 main() {
    sql.Db *db = sql.open("books.db");
    if (!db) {
        printf("can't open books.db\n");
        return 1;
    }
    defer sql.close(db);
    db.exec("CREATE TABLE IF NOT EXISTS archive (title TEXT PRIMARY KEY, author TEXT NOT NULL)");

    printf("archive Frank Herbert:\n");
    archive(db, "Frank Herbert");
    counts(db);

    printf("add Dune again and archive it again:\n");
    db.exec("INSERT INTO books (title, author, year) VALUES (?, ?, ?)", "Dune", "Frank Herbert", 1965);
    try {
        archive(db, "Frank Herbert");
    } catch (Error e) {
        printf("  failed: %s\n", e.message);
    }
    counts(db);

    /* many writes: one transaction */
    i64 t0 = date.monoMs();
    db.exec("BEGIN");
    for (i64 i = 1; i <= 10000; i++) {
        scoped String title = str.format("Volume %d", i);
        db.exec("INSERT INTO books (title, author, year) VALUES (?, ?, ?)", title, "Anonymous", 1900 + i % 120);
    }
    db.exec("COMMIT");
    printf("10000 books in %d ms\n", date.monoMs() - t0);
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
  failed: sql: UNIQUE constraint failed: archive.title (in: INSERT INTO archive (title, author) SELECT title, author FROM books WHERE author = ?)
  books by Frank Herbert: 1, archived: 1
10000 books in 21 ms
```

The second time, `Dune` was already in the archive, so the `INSERT` failed. The `ROLLBACK` kept
the new `Dune` in `books` instead of deleting it with nothing archived.

- Rethrowing after the `ROLLBACK` (`throw(e)`) lets the caller decide what to do, as `main` does.
- Put many writes in one transaction. SQLite waits for the disk at the end of every transaction,
  so 10,000 inserts on their own wait 10,000 times: the last loop took 21 ms in one transaction,
  and about 12 seconds without `BEGIN` and `COMMIT` on the same laptop disk.
- Until `COMMIT`, other connections to the file don't see the changes.
- `BEGIN IMMEDIATE` takes the write lock at once, instead of at the first write. Use it when two
  programs write to one file, so neither finds out halfway that the other got there first.

## The settings a real program wants

SQLite's defaults are careful and old. A program that serves requests usually sets four things
when it opens the file:

```ktm
import sql;

/* Opens a database the way a server wants it */
sql.Db *openDb(u8[] path) {
    sql.Db *db = sql.open(path);
    if (!db) { return null; }
    db.exec("PRAGMA journal_mode = WAL");     /* readers don't wait for the writer */
    db.exec("PRAGMA busy_timeout = 5000");    /* wait up to 5 s for a lock */
    db.exec("PRAGMA foreign_keys = ON");      /* check REFERENCES */
    db.exec("PRAGMA synchronous = NORMAL");   /* safe with WAL, much faster */
    return db;
}

i32 main() {
    sql.Db *db = openDb("books.db");
    if (!db) {
        printf("can't open books.db\n");
        return 1;
    }
    defer sql.close(db);

    String mode = sql.queryOne<String>(db, "PRAGMA journal_mode");
    defer mode.free();
    String version = sql.queryOne<String>(db, "SELECT sqlite_version()");
    defer version.free();
    printf("journal_mode %s, busy_timeout %d, foreign_keys %d, synchronous %d, SQLite %s\n", mode.cstr(),
           sql.queryOne<i64>(db, "PRAGMA busy_timeout"), sql.queryOne<i64>(db, "PRAGMA foreign_keys"),
           sql.queryOne<i64>(db, "PRAGMA synchronous"), version.cstr());
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
journal_mode wal, busy_timeout 5000, foreign_keys 1, synchronous 1, SQLite 3.46.1
```

- **`journal_mode = WAL`** lets readers read while one writer writes. It is stored in the file, so
  it stays on. While the database is open there are two more files next to it, `books.db-wal` and
  `books.db-shm`; copy all three when you copy a database that is in use, or use
  `sqlite3 books.db ".backup copy.db"`.
- **`busy_timeout`** makes a statement wait for another connection's lock, here up to 5 seconds,
  instead of failing at once with `database is locked`.
- **`foreign_keys = ON`** makes SQLite check `REFERENCES` and run `ON DELETE CASCADE`. It is off in
  every new connection, so set it every time.
- **`synchronous = NORMAL`** waits for the disk less often. With WAL it can't corrupt the file; a
  power cut can lose the last transactions, never half of one.
- Use one `sql.Db` per thread. To share one database between threads, give each its own connection
  to the file, and let `busy_timeout` handle the waiting.

## Enums, bools, blobs and JSON

Arguments and fields can be more than numbers and text:

```ktm
import reflect, sql, str, vec;

enum Shelf { fiction, history, science };

struct Details {
    i64 pages;
    String language;
};

struct Copy {
    String title;
    Shelf shelf;          /* stored as its name: 'fiction' */
    bool lent;            /* stored as 0 or 1 */
    f64 price;
    u8[] cover;           /* a BLOB */
    Details details;      /* stored as JSON text */
};

i32 main() {
    sql.Db *db = sql.open(":memory:");
    defer sql.close(db);
    db.exec("CREATE TABLE copies (title TEXT, shelf TEXT, lent INTEGER, price REAL, cover BLOB, details TEXT)");

    u8 png[8] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    Details d;
    d.pages = 412;
    d.language = str.from("en");
    db.exec("INSERT INTO copies VALUES (?, ?, ?, ?, ?, ?)",
            "Dune", Shelf.fiction, true, 9.99, sql.blob(png[0:8]), d);
    d.language.free();

    vec.Vec<Copy> copies = sql.queryAll<Copy>(db, "SELECT * FROM copies");
    defer sql.free(reflect.of(copies));
    for (auto c : copies) {
        printf("%s: shelf %s, lent %s, %.2f, cover %d bytes starting %c%c%c, %d pages in %s\n",
               c.title.cstr(), reflect.name(c.shelf), c.lent ? "yes" : "no", c.price, c.cover.len,
               c.cover[1], c.cover[2], c.cover[3], c.details.pages, c.details.language.cstr());
    }

    String row = sql.queryOne<String>(db, "SELECT shelf || ' ' || lent || ' ' || typeof(cover) || ' ' || details FROM copies");
    defer row.free();
    printf("in the table: %s\n", row.cstr());
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
Dune: shelf fiction, lent yes, 9.99, cover 8 bytes starting PNG, 412 pages in en
in the table: fiction 1 blob {"pages":412,"language":"en"}
```

- An enum is stored as its member's name (`'fiction'`), so the table reads well and a new member
  doesn't shift the others. It reads back from the name, or from a number.
- A `bool` is stored as 0 or 1.
- A `u8[]` argument is text. Wrap bytes in `sql.blob(bytes)` to store a BLOB; it reads back into a
  `u8[]` field (or a fixed `u8` array).
- A struct, slice or `vec.Vec` argument is stored as JSON text, and a struct field reads a JSON
  column back. SQLite's JSON functions (`json_extract(details, '$.pages')`) work on it.
- The database here is `":memory:"`: it lives in memory and is gone when it is closed.

## Tests on a database in memory

Each test gets an empty database in memory, with nothing on disk to clean up. Put this next to
your program as `books_test.ktm`:

```ktm
import sql, testing;

/* A fresh database in memory for each test: nothing on disk, nothing
 * left over from the last run */
sql.Db *testDb() {
    sql.Db *db = sql.open(":memory:");
    db.exec("CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT NOT NULL UNIQUE, "
            "author TEXT NOT NULL, year INTEGER)");
    return db;
}

void testInsert(testing.T *t) {
    sql.Db *db = testDb();
    defer sql.close(db);
    t.equal(db.exec("INSERT INTO books (title, author) VALUES (?, ?)", "Dune", "Frank Herbert"), 1);
    t.equal(sql.queryOne<i64>(db, "SELECT count(*) FROM books"), 1);
}

void testTitlesAreUnique(testing.T *t) {
    sql.Db *db = testDb();
    defer sql.close(db);
    db.exec("INSERT INTO books (title, author) VALUES (?, ?)", "Dune", "Frank Herbert");
    bool threw = false;
    try {
        db.exec("INSERT INTO books (title, author) VALUES (?, ?)", "Dune", "Someone Else");
    } catch (Error e) {
        threw = e.code == sql.ERR_SQL;
    }
    t.check(threw, "a second Dune is refused");
    t.equal(sql.queryOne<i64>(db, "SELECT count(*) FROM books"), 1);
}
```

```sh
ktm test
```

```output
ok  	.	0.00s  (2 passed)
```

- Each `test...(testing.T *t)` function is a test. `t.check(ok, "what")` and `t.equal(got, want)`
  report a failure without stopping the others.
- `ktm test -jit -Memsafe` runs the same tests in the JIT and reports any memory the code never
  freed.

## Prepared statements, for the fastest inserts

`db.exec` compiles its SQL each time. For one statement run thousands of times, prepare it once
and run it with new values each time. The lower-level calls do that, with the values in
`sql.Param`s:

```ktm
import date, sql, str;

/* Adds `n` made-up books through one prepared statement */
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
        scoped String title = str.format("Reprint %d", i);
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
    db.exec("BEGIN");
    if (!addMany(db, 10000)) {
        db.exec("ROLLBACK");
        return 1;
    }
    db.exec("COMMIT");
    printf("10000 books in %d ms\n", date.monoMs() - t0);
    return 0;
}
```

```sh
ktm app.ktm -o app
./app
```

```output
10000 books in 9 ms
```

- `sql.prepare(db, query)` compiles a statement, or returns `null` with the reason in
  `sql.sqlite3_errmsg(db.conn)`. `sql.finalize(st)` frees it.
- A `sql.Param` holds one value: `type` is `sql.INT` (`intVal`), `sql.FLOAT` (`floatVal`),
  `sql.TEXT` (`str`), `sql.BLOB` (`blob` and `blobLen`) or `sql.NULL_TYPE`.
- `sql.execPrepared(st, null, params)` runs it and returns `sql.SQLITE_DONE` when it finished. For
  a query, pass a `sql.Row` and read it with `sql.nextPrepared(&row)`.
- These calls return codes instead of throwing. Use them where the speed matters, and `db.exec`
  and `queryAll` everywhere else: 9 ms here against 21 ms with `db.exec` in the transactions
  section.

## Reference

| Call | What it does |
| --- | --- |
| `sql.open(path)` | opens or creates the database; `":memory:"` for one in memory; `null` on failure |
| `sql.close(db)` | closes it |
| `db.exec(query, args...)` | one statement; the rows it changed |
| `sql.queryAll<T>(db, query, args...)` | every row, as a `vec.Vec<T>` |
| `sql.queryOne<T>(db, query, args...)` | the first row as a `T`; `sql.ERR_NO_ROWS` when none |
| `sql.free(reflect.of(x))` | frees what `queryAll` and `queryOne` allocated |
| `sql.blob(bytes)` | bytes as a BLOB argument |
| `db.lastInsertId()` | the id of the last row inserted |
| `db.errorCode()` | why the last call failed: `sql.CONSTRAINT_UNIQUE` ... |
| `sql.execRaw(db, script)` | several statements, no arguments; the error as a `String` |
| `sql.prepare`, `sql.execPrepared`, `sql.finalize` | one statement run many times |

| Argument | Stored as |
| --- | --- |
| integer, `bool` | INTEGER |
| enum | TEXT, the member's name |
| float | REAL |
| `u8[]`, `String`, `u8 *`, a literal | TEXT |
| `sql.blob(bytes)` | BLOB |
| struct, slice, `vec.Vec` | TEXT, as JSON |
| a pointer to one of these | what it points to; a null pointer is NULL |

| Error | When |
| --- | --- |
| `sql.ERR_SQL` | SQLite refused the statement; `db.errorCode()` says why |
| `sql.ERR_NO_ROWS` | `queryOne` found no row |
| `sql.ERR_SCAN` | a column can't go into its field |
| `sql.ERR_ARG` | an argument SQLite has no place for, or the wrong number of them |

## Rules of thumb

- **Values go in arguments, never into the SQL text.** That keeps out SQL injection and quoting
  bugs.
- **Write many rows in one transaction.** It is the difference between milliseconds and seconds.
- **Turn on WAL, a busy timeout and foreign keys** when you open the file.
- **Make a field a pointer when NULL means something**, and free what you read with `sql.free`.
- **Manage the schema with migrations** once the program is in use: the
  [database migrations guide](01-database-migrations.html) uses SQLite too.
