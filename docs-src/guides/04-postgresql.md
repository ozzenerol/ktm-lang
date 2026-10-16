# PostgreSQL

Talk to PostgreSQL from ktm with the [ktm-pg](https://github.com/ozzenerol/ktm-pg) package. This
guide builds up a catalogue of books, one whole program at a time: connecting, running
statements with parameters, reading rows into structs, NULLs, rows one by one, transactions,
errors, a connection pool behind an HTTP API, batches and fast bulk loading, query timeouts, and
LISTEN/NOTIFY.

## What you need

- **Any ktm.** ktm-pg is written in ktm and speaks Postgres' protocol itself: no libpq, no C
  library to install.
- **A PostgreSQL server**, version 14 or newer. With Docker, one line starts one:

```sh
docker run -d --name pg -p 5432:5432 -e POSTGRES_PASSWORD=secret postgres:17
```

Make a project and add the package:

```sh
mkdir books && cd books
ktm mod init example.com/me/books
ktm get github.com/ozzenerol/ktm-pg
```

Every step of this guide is a whole `main.ktm` for this project. Each one carries on from the
tables the last one left, so run them in order. They connect with the server's address from the
environment, so set it once in the terminal you build in:

```sh
export PGHOST=localhost PGUSER=postgres PGPASSWORD=secret PGDATABASE=postgres
```

Then build and run each step like this:

```sh
ktm build
./books
```

## Connect and run statements

```ktm
import "github.com/ozzenerol/ktm-pg";

i32 main() {
    pg.Conn *c = pg.connect();      /* PGHOST, PGUSER, PGPASSWORD, PGDATABASE */
    defer c.close();
    printf("connected to PostgreSQL %s\n", c.serverVersion());

    c.execScript(
        "CREATE TABLE IF NOT EXISTS books (\n"
        "    id     bigserial PRIMARY KEY,\n"
        "    title  text NOT NULL UNIQUE,\n"
        "    author text NOT NULL,\n"
        "    year   int\n"
        ");\n");

    i64 n = c.exec("INSERT INTO books (title, author, year) VALUES ($1, $2, $3) ON CONFLICT (title) DO NOTHING",
                   "Dune", "Frank Herbert", 1965);
    printf("%d row(s) inserted\n", n);

    i64 id = pg.queryOne<i64>(c, "INSERT INTO books (title, author, year) VALUES ($1, $2, $3) "
                                 "ON CONFLICT (title) DO UPDATE SET year = excluded.year RETURNING id",
                              "Neuromancer", "William Gibson", 1984);
    printf("Neuromancer has id %d\n", id);

    i64 count = pg.queryOne<i64>(c, "SELECT count(*) FROM books");
    printf("%d book(s)\n", count);
    return 0;
}
```

```sh
ktm build
./books
```

```output
connected to PostgreSQL 17.11 (Debian 17.11-1.pgdg13+2)
1 row(s) inserted
Neuromancer has id 2
2 book(s)
```

Run it again and nothing is added: `ON CONFLICT` turns the inserts into no-ops.

```sh
./books
```

```output
connected to PostgreSQL 17.11 (Debian 17.11-1.pgdg13+2)
0 row(s) inserted
Neuromancer has id 2
2 book(s)
```

- `pg.connect()` reads the server from `PGHOST`, `PGPORT`, `PGUSER`, `PGPASSWORD` and
  `PGDATABASE`. A URL works too, and wins over the environment:
  `pg.connect("postgres://postgres:secret@localhost:5432/postgres")`. It throws when it can't
  connect or log in.
- `c.close()` ends the connection; `defer` it right after connecting.
- `c.execScript(sql)` runs any number of statements, without parameters: a schema.
- `c.exec(sql, args...)` runs one statement and returns how many rows it changed.
- `$1`, `$2` ... are parameters, filled from the arguments after the SQL, in order. They are sent
  apart from the SQL, never pasted into it, so no value can change the statement.
- `pg.queryOne<T>(c, sql, args...)` returns the first column of the one row a query returns,
  here the new id from `RETURNING id`.
- Arguments can be integers, floats, `bool`, strings (`u8[]`, `String`, literals), `date.Date`,
  structs (sent as JSON), arrays and more; the
  [ktm-pg README](https://github.com/ozzenerol/ktm-pg#running-statements) has the full table.

## Read rows into structs

```ktm
import "github.com/ozzenerol/ktm-pg";
import reflect, vec;

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
    pg.Conn *c = pg.connect();
    defer c.close();

    i64 *noYear = null;
    c.exec("INSERT INTO books (title, author, year) VALUES ($1, $2, $3) ON CONFLICT (title) DO NOTHING",
           "Robert's Rules", "Henry M. Robert", noYear);
    c.exec("INSERT INTO books (title, author, year) VALUES ($1, $2, $3) ON CONFLICT (title) DO NOTHING",
           "The Left Hand of Darkness", "Ursula K. Le Guin", 1969);

    /* every row, into a vector of structs */
    vec.Vec<Book> all = pg.queryAll<Book>(c, "SELECT id, title, author, year FROM books ORDER BY id");
    defer pg.free(reflect.of(all));
    for (auto b : all) {
        print(&b);
    }

    /* one row */
    Book dune = pg.queryOne<Book>(c, "SELECT * FROM books WHERE title = $1", "Dune");
    defer pg.free(reflect.of(dune));
    printf("found: ");
    print(&dune);

    /* no row: queryOne throws pg.ERR_NO_ROWS */
    try {
        Book none = pg.queryOne<Book>(c, "SELECT * FROM books WHERE id = $1", 999);
        pg.free(reflect.of(none));
    } catch (Error e) {
        if (e.code == pg.ERR_NO_ROWS) {
            printf("no book 999\n");
        } else {
            throw(e);
        }
    }

    /* not a struct: the first column */
    vec.Vec<String> authors = pg.queryAll<String>(c, "SELECT DISTINCT author FROM books ORDER BY author");
    defer pg.free(reflect.of(authors));
    printf("%d authors, the first is %s\n", authors.len, authors.data[0].cstr());
    return 0;
}
```

```sh
ktm build
./books
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

- `pg.queryAll<T>` returns every row as a `vec.Vec<T>`, and `pg.queryOne<T>` the first row as a
  `T`. A `T` that isn't a struct (`i64`, `String`) takes the first column.
- Columns fill the fields with the same name. `SELECT *` works; a column with no field is left
  out, and a field with no column stays zero.
- A NULL in an `i64` field reads as 0. To tell NULL from 0, make the field a pointer: `year` is
  `null` for NULL, and points to the value otherwise.
- `pg.free(reflect.of(x))` frees what scanning allocated: the Strings, the vector, the pointers.
- `queryOne` with no row throws `pg.ERR_NO_ROWS`. When "none" is a normal answer, `queryAll` and
  a check of `len` read better, as the HTTP section does.
- `noYear` is a null `i64 *`: a null pointer argument is sent as NULL.

## Rows one by one

`c.query` returns the rows to read one at a time, without building structs. It suits a big
result, or one you only print:

```ktm
import "github.com/ozzenerol/ktm-pg";

i32 main() {
    pg.Conn *c = pg.connect();
    defer c.close();

    scoped pg.Rows r = c.query("SELECT id, title, year FROM books WHERE id > $1 ORDER BY id", 0);
    printf("%d columns: %s, %s, %s\n", r.cols, r.name(0), r.name(1), r.name(2));
    while (r.next()) {
        i64 id = r.int(0);
        u8[] title = r.text(1);       /* a view: valid until the next row */
        if (r.isNull(2)) {
            printf("%3d  %s\n", id, title);
        } else {
            printf("%3d  %s (%d)\n", id, title, r.int(2));
        }
    }
    return 0;
}
```

```sh
ktm build
./books
```

```output
3 columns: id, title, year
  1  Dune (1965)
  2  Neuromancer (1984)
  3  Robert's Rules
  4  The Left Hand of Darkness (1969)
```

- `r.next()` moves to the next row and returns `false` after the last.
- `r.int(i)`, `r.float(i)`, `r.bool(i)`, `r.text(i)` and `r.bytes(i)` read column `i`;
  `r.isNull(i)` tells a NULL apart.
- `r.text(i)` is a view: it is valid until the next row. Copy what you keep with `str.from`.
- `scoped` frees the rows when the block ends, and reads any that are left first. Until then the
  connection runs nothing else.

## Transactions

```ktm
import "github.com/ozzenerol/ktm-pg";

/* Moves `amount` from one account to another: both updates, or neither */
void transfer(pg.Conn *c, u8[] from, u8[] to, i64 amount) {
    pg.Tx tx = c.begin();
    defer tx.rollback();          /* does nothing once commit() ran */
    tx.exec("UPDATE accounts SET balance = balance - $1 WHERE name = $2", amount, from);
    tx.exec("UPDATE accounts SET balance = balance + $1 WHERE name = $2", amount, to);
    tx.commit();
}

void balances(pg.Conn *c) {
    scoped pg.Rows r = c.query("SELECT name, balance FROM accounts ORDER BY name");
    while (r.next()) {
        printf("  %s: %d\n", r.text(0), r.int(1));
    }
}

i32 main() {
    pg.Conn *c = pg.connect();
    defer c.close();

    c.execScript(
        "DROP TABLE IF EXISTS accounts;\n"
        "CREATE TABLE accounts (name text PRIMARY KEY, balance bigint NOT NULL CHECK (balance >= 0));\n"
        "INSERT INTO accounts VALUES ('ann', 100), ('bob', 20);\n");

    transfer(c, "ann", "bob", 30);
    printf("after ann -> bob 30:\n");
    balances(c);

    try {
        transfer(c, "bob", "ann", 500);
    } catch (Error e) {
        printf("bob -> ann 500 failed: %s\n", e.message);
    }
    printf("after the failed transfer:\n");
    balances(c);
    return 0;
}
```

```sh
ktm build
./books
```

```output
after ann -> bob 30:
  ann: 70
  bob: 50
bob -> ann 500 failed: new row for relation "accounts" violates check constraint "accounts_balance_check" (SQLSTATE 23514); detail: Failing row contains (bob, -450).
after the failed transfer:
  ann: 70
  bob: 50
```

- `c.begin()` starts a transaction and returns a `pg.Tx`, with the same `exec`, `query` and
  `execScript` as a connection. `pg.queryAll` and `pg.queryOne` take `&tx` too.
- `tx.commit()` makes the changes stay. `defer tx.rollback();` right after `begin` undoes them
  when anything throws first, and does nothing after a commit.
- The second transfer took bob below zero, so the `CHECK` failed. The error went up through
  `transfer`, the deferred rollback undid the first `UPDATE` too, and `main` caught it.
- `c.begin("ISOLATION LEVEL SERIALIZABLE")` picks a stricter isolation level.

## Errors

Every failure throws. For an error from the server, `e.code` is its SQLSTATE, and ktm-pg names
the common ones:

```ktm
import "github.com/ozzenerol/ktm-pg";

/* Adds a book; false when one with that title is already there */
bool addBook(pg.Conn *c, u8[] title, u8[] author) {
    try {
        c.exec("INSERT INTO books (title, author) VALUES ($1, $2)", title, author);
    } catch (Error e) {
        if (e.code == pg.UNIQUE_VIOLATION) {
            return false;
        }
        throw(e);
    }
    return true;
}

i32 main() {
    pg.Conn *c = pg.connect();
    defer c.close();

    if (!addBook(c, "Dune", "Frank Herbert")) {
        printf("Dune is already there\n");
    }

    try {
        c.exec("INSERT INTO books (title) VALUES ($1)", "No Author");
    } catch (Error e) {
        printf("%s\n", e.message);
    }
    try {
        c.exec("SELECT * FROM book");
    } catch (Error e) {
        printf("%s\n", e.message);
    }
    printf("the connection still works: %d books\n", pg.queryOne<i64>(c, "SELECT count(*) FROM books"));
    return 0;
}
```

```sh
ktm build
./books
```

```output
Dune is already there
null value in column "author" of relation "books" violates not-null constraint (SQLSTATE 23502); detail: Failing row contains (6, No Author, null, null).
relation "book" does not exist (SQLSTATE 42P01)
the connection still works: 4 books
```

- Named codes: `pg.UNIQUE_VIOLATION`, `pg.FOREIGN_KEY_VIOLATION`, `pg.NOT_NULL_VIOLATION`,
  `pg.CHECK_VIOLATION`, `pg.SERIALIZATION_FAILURE`, `pg.DEADLOCK_DETECTED`, `pg.UNDEFINED_TABLE`,
  `pg.UNDEFINED_COLUMN`, `pg.SYNTAX_ERROR`, `pg.QUERY_CANCELED`, `pg.INVALID_PASSWORD`,
  `pg.DIVISION_BY_ZERO`, `pg.IN_FAILED_TRANSACTION`. Any other: `pg.state("23505")`.
- `e.message` is the server's message, the SQLSTATE and the detail.
- The client's own errors: `pg.ERR_CONNECT`, `pg.ERR_AUTH`, `pg.ERR_TLS`, `pg.ERR_IO` (the
  connection broke), `pg.ERR_NO_ROWS`, `pg.ERR_SCAN`, `pg.ERR_POOL`, `pg.ERR_ARG`, `pg.ERR_CLOSED`.
- A failed statement doesn't break the connection: the next one works.
- The id in the NOT NULL message is 6, not 5. A sequence never gives a number back, so the failed
  inserts used 5 and 6. Ids have gaps; never count on them being dense.

## A pool behind an HTTP API

A server needs more than one connection: requests arrive together, on several threads. A
`pg.Pool` keeps connections open and lends one to each query. This step turns the catalogue
into a small JSON API with `net.http`:

```ktm
import "github.com/ozzenerol/ktm-pg";
import json, net.http, reflect, str, vec;

@json struct Book {
    i64 id;
    String title;
    String author;
    i64 *year;
};

@json struct NewBook {
    String title;
    String author;
    i64 year;
};

pg.Pool *db;

/* GET /books */
void listBooks(http.Writer *w, http.Request *r) {
    vec.Vec<Book> books = pg.queryAll<Book>(db, "SELECT id, title, author, year FROM books ORDER BY id");
    defer pg.free(reflect.of(books));
    w.json(200, reflect.of(books));
}

/* GET /books/{id} */
void getBook(http.Writer *w, http.Request *r) {
    i64 id;
    if (!str.toInt(r.param("id"), &id)) {
        w.text(400, "bad id\n");
        return;
    }
    vec.Vec<Book> found = pg.queryAll<Book>(db, "SELECT id, title, author, year FROM books WHERE id = $1", id);
    defer pg.free(reflect.of(found));
    if (!found.len) {
        w.text(404, "no book %d\n", id);
        return;
    }
    w.json(200, reflect.of(found.data[0]));
}

/* POST /books with {"title": ..., "author": ..., "year": ...} */
void addBook(http.Writer *w, http.Request *r) {
    NewBook nb;
    try {
        nb = json.decode<NewBook>(r.body);
    } catch (Error e) {
        w.text(400, "%s\n", e.message);
        return;
    }
    defer json.free(reflect.of(nb));
    try {
        i64 id = pg.queryOne<i64>(db, "INSERT INTO books (title, author, year) VALUES ($1, $2, $3) RETURNING id",
                                  nb.title, nb.author, nb.year);
        w.text(201, "%d\n", id);
    } catch (Error e) {
        if (e.code == pg.UNIQUE_VIOLATION) {
            w.text(409, "there is already a book called %s\n", nb.title.cstr());
            return;
        }
        throw(e);
    }
}

i32 main() {
    db = pg.open();               /* connects when a query first needs it */
    defer db.close();
    db.ping();                    /* a wrong address or password shows here, at start-up */

    http.Mux mux;
    mux.handle("GET /books", &listBooks);
    mux.handle("GET /books/{id}", &getBook);
    mux.handle("POST /books", &addBook);
    printf("listening on :8080\n");
    return http.listen(8080, &mux);
}
```

Start it:

```sh
ktm build
./books
```

Then, from a second terminal:

```sh
curl localhost:8080/books
```

```output
[{"id":1,"title":"Dune","author":"Frank Herbert","year":1965},{"id":2,"title":"Neuromancer","author":"William Gibson","year":1984},{"id":3,"title":"Robert's Rules","author":"Henry M. Robert","year":null},{"id":4,"title":"The Left Hand of Darkness","author":"Ursula K. Le Guin","year":1969}]
```

```sh
curl localhost:8080/books/1
curl localhost:8080/books/999
curl localhost:8080/books/x
```

```output
{"id":1,"title":"Dune","author":"Frank Herbert","year":1965}
no book 999
bad id
```

```sh
curl -X POST localhost:8080/books -d '{"title": "Hyperion", "author": "Dan Simmons", "year": 1989}'
curl -X POST localhost:8080/books -d '{"title": "Hyperion", "author": "Dan Simmons", "year": 1989}'
curl -X POST localhost:8080/books -d '{"title": 5}'
```

```output
7
there is already a book called Hyperion
1:11: title: expected a string, got a number
```

Stop the server with Ctrl+C.

- `pg.open()` makes a pool for the same server `pg.connect` would use. It connects only when a
  query needs a connection; `db.ping()` connects at once, so a wrong password stops the program at
  start-up instead of at the first request.
- Everything that takes a connection takes the pool: `pg.queryAll<T>(db, ...)`,
  `pg.queryOne<T>(db, ...)`, `db.exec`, `db.query`, `db.begin()`.
- The pool is safe from every thread of the HTTP server. A query waits for its answer without
  blocking the thread, which serves other requests meanwhile.
- `pg.Options` sets the pool's size and limits: `pg.Options o; o.maxConns = 32; pg.open("", &o);`.
  The default is 4 connections per CPU, between 8 and 64.
- `year` is an `i64 *` in `Book`, so a NULL year comes out as JSON `null`.
- `db.acquire()` lends a connection to you alone, for a `SET` or a temporary table;
  `c.release()` gives it back.

## Batches and bulk loading

Each `exec` waits for its answer before the next one goes out. A batch sends several statements
at once, and binary COPY loads many rows faster than any INSERT:

```ktm
import "github.com/ozzenerol/ktm-pg";
import date;

struct Reading {
    i64 sensor;
    f64 celsius;
};

i32 main() {
    pg.Conn *c = pg.connect();
    defer c.close();
    c.execScript(
        "DROP TABLE IF EXISTS readings;\n"
        "CREATE TABLE readings (sensor bigint NOT NULL, celsius float8 NOT NULL);\n");

    /* a batch: several statements in one round trip, all or nothing */
    pg.Batch b;
    defer b.free();
    b.add("INSERT INTO readings VALUES ($1, $2)", 1, 20.5);
    b.add("INSERT INTO readings VALUES ($1, $2)", 2, 21.0);
    b.add("UPDATE readings SET celsius = celsius + $1 WHERE sensor = $2", 0.25, 1);
    i64[] counts = c.execBatch(&b);
    printf("batch: %d statements, the last changed %d row(s)\n", counts.len, counts[counts.len - 1]);
    counts.free();

    /* binary COPY: the fastest way in for many rows */
    i64 t0 = date.monoMs();
    pg.CopyInto<Reading> in = c.copyInto<Reading>("readings");
    for (i64 i = 0; i < 100000; i++) {
        Reading r;
        r.sensor = i % 50;
        r.celsius = 15.0 + (i % 100) / 10.0;
        in.put(r);
    }
    i64 n = in.end();
    printf("copied %d rows in %d ms\n", n, date.monoMs() - t0);

    f64 avg = pg.queryOne<f64>(c, "SELECT avg(celsius) FROM readings");
    printf("%d rows, %.2f degrees on average\n", pg.queryOne<i64>(c, "SELECT count(*) FROM readings"), avg);
    return 0;
}
```

```sh
ktm build
./books
```

```output
batch: 3 statements, the last changed 1 row(s)
copied 100000 rows in 23 ms
100002 rows, 19.95 degrees on average
```

- `b.add(sql, args...)` adds a statement, copying its arguments. `c.execBatch(&b)` sends them all
  in one round trip and returns each one's changed-row count, an `i64[]` that is yours to free.
- The statements of a batch are one transaction: when one fails, its error is thrown and none of
  them stays.
- `c.copyInto<T>("table")` loads rows straight from structs, in Postgres' binary format. The
  fields name the columns, as when reading. `in.put(x)` adds a row, `in.end()` finishes and
  returns the count, `in.abort()` gives up and loads nothing.
- On a pool, take a connection for COPY with `db.acquire()`.

## Query timeouts

```ktm
import "github.com/ozzenerol/ktm-pg";

i32 main() {
    pg.Conn *c = pg.connect();
    defer c.close();

    c.setQueryTimeout(500);       /* ms, for every query on c from now on */
    try {
        c.exec("SELECT pg_sleep(3)");
    } catch (Error e) {
        if (e.code == pg.QUERY_CANCELED) {
            printf("cancelled: %s\n", e.message);
        } else {
            throw(e);
        }
    }
    printf("the connection still works: %d\n", pg.queryOne<i64>(c, "SELECT 1 + 1"));
    return 0;
}
```

```sh
ktm build
./books
```

```output
cancelled: timeout after 500 ms: canceling statement due to user request (SQLSTATE 57014)
the connection still works: 2
```

- `c.setQueryTimeout(ms)` limits every query on the connection. A query past it is cancelled on
  the server and throws `pg.QUERY_CANCELED`, and the connection stays usable.
- For a pool, set `o.queryTimeoutMs` in its `pg.Options`.
- `?statement_timeout=5s` at the end of the URL sets the server's own limit as well.

## LISTEN and NOTIFY

Postgres can tell a program that something happened, without the program asking over and over:

```ktm
import "github.com/ozzenerol/ktm-pg";

i32 main() {
    pg.Conn *listener = pg.connect();     /* a connection of its own for listening */
    defer listener.close();
    pg.Conn *c = pg.connect();
    defer c.close();

    listener.listen("new_books");

    /* another program, or a trigger, would do this */
    c.notify("new_books", "Hyperion");
    c.exec("SELECT pg_notify('new_books', $1)", "The Fall of Hyperion");

    pg.Notification n;
    while (listener.waitNotification(&n, 1000)) {     /* false after 1 s with none */
        printf("%s: %s\n", n.channel, n.payload);
        n.free();
    }
    printf("no more for 1 s\n");
    return 0;
}
```

```sh
ktm build
./books
```

```output
new_books: Hyperion
new_books: The Fall of Hyperion
no more for 1 s
```

- `listener.listen(channel)` subscribes. Notifications sent while the listener is busy are kept
  until it reads them.
- `waitNotification(&n, ms)` waits for one, up to `ms`; it returns `false` when none came. Free
  each one with `n.free()`.
- `c.notify(channel, payload)` sends one, as `NOTIFY` and `pg_notify()` do in SQL; a trigger can
  send them too.
- Listen on a connection of its own: from a pool, `db.acquire()` one and keep it.

## Connecting securely

ktm-pg uses TLS when the server offers it (`sslmode=prefer`, as libpq does). For a server across
a network, require it and check the server's certificate:

```ktm
pg.Conn *c = pg.connect("postgres://app@db.example.com/app?sslmode=verify-full&sslrootcert=/etc/ssl/db-ca.pem");
```

| `sslmode` | What it does |
| --- | --- |
| `disable` | never TLS |
| `prefer` | TLS when the server has it (the default) |
| `require` | TLS, without checking the certificate |
| `verify-ca` | TLS, and the certificate must come from a CA in `sslrootcert` |
| `verify-full` | as `verify-ca`, and the certificate must name the host |

Passwords are sent with SCRAM-SHA-256, the Postgres default, and with TLS also bound to the
connection (SCRAM-SHA-256-PLUS).

## Reference

| Call | What it does |
| --- | --- |
| `pg.connect(url)` | a `pg.Conn *`; the URL, else the `PG*` environment |
| `pg.open(url, &opts)` | a `pg.Pool *`, safe from every thread |
| `c.close()`, `db.close()` | ends a connection or a pool |
| `c.exec(sql, args...)` | one statement; the rows it changed |
| `c.execScript(sql)` | several statements, no parameters |
| `c.query(sql, args...)` | `pg.Rows` to read with `next()` |
| `pg.queryAll<T>(db, sql, args...)` | every row, as a `vec.Vec<T>` |
| `pg.queryOne<T>(db, sql, args...)` | the first row as a `T`; `pg.ERR_NO_ROWS` when none |
| `pg.free(reflect.of(x))` | frees what scanning allocated |
| `c.begin()` | a `pg.Tx`: `commit()`, `rollback()` |
| `c.execBatch(&b)` | a `pg.Batch` in one round trip |
| `c.copyInto<T>(table)` | binary COPY from structs: `put`, `end`, `abort` |
| `c.setQueryTimeout(ms)` | a time limit for every query |
| `c.listen(ch)`, `c.waitNotification(&n, ms)`, `c.notify(ch, payload)` | LISTEN and NOTIFY |
| `db.acquire()`, `c.release()` | a pool's connection to yourself |

`db` in the table is a `pg.Conn *`, a `pg.Tx *` or a `pg.Pool *`.

## Rules of thumb

- **Values go in `$1`, `$2` ..., never into the SQL text.**
- **One pool per program**, opened in `main` and checked with `ping()` before serving.
- **`defer tx.rollback();` right after `begin()`**, and `commit()` at the end.
- **Free scanned values** with `pg.free(reflect.of(x))`, or scan into `pg.queryResult<T>` for big
  results (see the [README](https://github.com/ozzenerol/ktm-pg#big-results-queryresult)).
- **Set a query timeout** on servers, so one slow query can't hold a request forever.
- **Manage the schema with migrations**: the [database migrations
  guide](01-database-migrations.html) covers Postgres too.
