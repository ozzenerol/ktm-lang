import cstr, reflect, sql, str, test, vec;

/* v0.26.0: db.exec(query, args...), sql.queryAll<T>, sql.queryOne<T>:
 * `?` parameters from arguments of any supported type, rows into
 * structs by column name (or one value from column 0), NULL into a
 * pointer field as null, and every failure thrown (ERR_SQL, ERR_NO_ROWS,
 * ERR_SCAN, ERR_ARG). */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

enum Shelf { fiction, history, science };

struct Tags { i64 count; String first; };

struct Book {
    i64 id;
    String title;
    @key("author_name") String author;
    i64 *year;               /* NULL: null */
    f64 price;
    bool lent;
    Shelf shelf;
    u8[] cover;
    u8 isbn[4];
    Tags tags;               /* a JSON column */
    @skip i64 notes;
    i64 missing;             /* no such column: stays 0 */
};

i64 thrown(sql.Db *db, u8[] query) {
    try {
        db.exec(query);
    } catch (Error e) {
        return e.code;
    }
    return 0;
}

i32 main() {
    printf("Test - sql queryAll, queryOne and exec with arguments: ");
    sql.Db *db = sql.open(":memory:");
    db.exec("CREATE TABLE books (id INTEGER PRIMARY KEY, TITLE TEXT NOT NULL UNIQUE, author_name TEXT, "
            "year INTEGER, price REAL, lent INTEGER, shelf TEXT, cover BLOB, isbn BLOB, tags TEXT, notes INTEGER)");

    u8 png[4] = {0x89, 'P', 0, 'G'};
    Tags tags;
    tags.count = 2;
    tags.first = str.from("classic");
    String author = str.from("Frank Herbert");
    i64 year = 1965;
    i64 n = db.exec("INSERT INTO books (title, author_name, year, price, lent, shelf, cover, isbn, tags, notes) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    "Dune", author, &year, 9.5, true, Shelf.science, sql.blob(png[0:4]), sql.blob(png[0:3]), tags, 7);
    check(n == 1, "exec answers the rows it changed");
    check(db.lastInsertId() == 1, "lastInsertId");
    author.free();
    tags.first.free();

    i64 *noYear = null;
    u8 *cAuthor = "Henry M. Robert";
    i64 id = sql.queryOne<i64>(db, "INSERT INTO books (title, author_name, year, shelf) VALUES (?, ?, ?, ?) RETURNING id",
                               "Robert's Rules", cAuthor, noYear, Shelf.history);
    check(id == 2, "queryOne with RETURNING");

    vec.Vec<Book> all = sql.queryAll<Book>(db, "SELECT * FROM books ORDER BY id");
    check(all.len == 2, "queryAll: two rows");
    Book *d = all.at(0);
    check(d.id == 1 && d.title.equals("Dune"), "a column by its name, ignoring case (TITLE)");
    check(d.author.equals("Frank Herbert"), "@key names the column");
    check(d.year && *d.year == 1965, "a pointer field gets a value");
    check(d.price == 9.5 && d.lent && d.shelf == Shelf.science, "float, bool, enum by name");
    check(d.cover.len == 4 && d.cover[0] == 0x89 && d.cover[2] == 0 && d.cover[3] == 'G', "a blob into a u8[]");
    check(d.isbn[0] == 0x89 && d.isbn[1] == 'P' && d.isbn[3] == 0, "a blob into a u8 array");
    check(d.tags.count == 2 && d.tags.first.equals("classic"), "a struct argument goes as JSON and comes back");
    check(d.notes == 0 && d.missing == 0, "@skip and a field with no column stay 0");
    Book *r = all.at(1);
    check(!r.year && r.shelf == Shelf.history && r.price == 0 && !r.cover.len, "NULL: a null pointer, zero values");
    sql.free(reflect.of(all));

    vec.Vec<String> titles = sql.queryAll<String>(db, "SELECT title FROM books WHERE year > ? OR year IS NULL ORDER BY title", 1900);
    check(titles.len == 2 && titles.data[0].equals("Dune"), "queryAll of a non-struct: column 0");
    sql.free(reflect.of(titles));

    check(sql.queryOne<i64>(db, "SELECT count(*) FROM books") == 2, "queryOne<i64>");
    Book one = sql.queryOne<Book>(db, "SELECT id, title FROM books WHERE title = ?", "Robert's Rules");
    check(one.id == 2 && one.title.equals("Robert's Rules"), "queryOne<Book>, a quote in a parameter");
    sql.free(reflect.of(one));

    check(db.exec("UPDATE books SET lent = ? WHERE lent = ?", false, true) == 1, "exec: changed rows");
    check(db.exec("UPDATE books SET lent = 0 WHERE id > ?", 100) == 0, "exec: no rows changed");

    i64 code = 0;
    try { sql.queryOne<i64>(db, "SELECT id FROM books WHERE id = ?", 99); } catch (Error e) { code = e.code; }
    check(code == sql.ERR_NO_ROWS, "queryOne with no row: ERR_NO_ROWS");

    String msg;
    try {
        db.exec("INSERT INTO books (title) VALUES (?)", "Dune");
    } catch (Error e) {
        code = e.code;
        msg = str.from(cstr.view(e.message));
    }
    check(code == sql.ERR_SQL && msg.contains("UNIQUE constraint failed: books.TITLE"), "ERR_SQL with SQLite's message");
    msg.free();

    check(thrown(db, "SELEC 1") == sql.ERR_SQL, "a syntax error");
    check(thrown(db, "SELECT 1; SELECT 2") == sql.ERR_SQL, "two statements");
    code = 0;
    try { db.exec("SELECT ?, ?", 1); } catch (Error e) { code = e.code; }
    check(code == sql.ERR_ARG, "too few arguments: ERR_ARG");
    code = 0;
    try { sql.queryOne<i64>(db, "SELECT title FROM books WHERE id = 1"); } catch (Error e) { code = e.code; }
    check(code == sql.ERR_SCAN, "text into an i64: ERR_SCAN");
    code = 0;
    try { sql.queryAll<Book>(db, "SELECT 'nope' AS shelf"); } catch (Error e) { code = e.code; }
    check(code == sql.ERR_SCAN, "an enum name that isn't a member: ERR_SCAN");

    i64 count = sql.queryOne<i64>(db, "SELECT count(*) FROM books WHERE title = ? AND author_name IS ?", "Dune", "Frank Herbert");
    check(count == 1, "text arguments as literals and u8[]");

    sql.close(db);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
