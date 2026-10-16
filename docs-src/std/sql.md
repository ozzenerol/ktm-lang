# SQLite

The `sql` module is SQLite. It is only in a ktm built with `./build.sh --sqlite`; the release
zips don't have it.

Most programs need four calls: `db.exec` for a statement, `sql.queryAll<T>` and
`sql.queryOne<T>` to read rows into structs, and `sql.free` for what they allocated. Each `?` takes
the next argument, of any type: integers, bools and enums, floats, text, `sql.blob(bytes)`, a
struct as JSON, or a pointer to any of them (null is NULL). Failures throw `sql.ERR_SQL` with
SQLite's message.

```ktm
import reflect, sql, vec;

struct Book { i64 id; String title; i64 *year; };

i32 main() {
    sql.Db *db = sql.open("books.db");
    defer sql.close(db);
    db.exec("CREATE TABLE IF NOT EXISTS books (id INTEGER PRIMARY KEY, title TEXT, year INTEGER)");
    i64 id = sql.queryOne<i64>(db, "INSERT INTO books (title, year) VALUES (?, ?) RETURNING id", "Dune", 1965);
    printf("added %d\n", id);

    vec.Vec<Book> books = sql.queryAll<Book>(db, "SELECT * FROM books WHERE year > ?", 1960);
    defer sql.free(reflect.of(books));
    for (auto b : books) { printf("%d %s\n", b.id, b.title.cstr()); }
    return 0;
}
```

The lower-level calls are there too: `sql.exec` and `sql.select` with `sql.Param`s,
`sql.prepare` and `sql.execPrepared` for one statement run many times, and `sql.execRaw` for a
script of statements. The [SQLite guide](../guides/03-sqlite.html) shows all of it.
