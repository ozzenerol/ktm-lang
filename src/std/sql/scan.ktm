module sql;

#ifdef __KTM_LINK_SQLITE3__
import cstr, json, mem, reflect, str, vec;

/* Queries with their values as arguments, and rows into ktm values:
 *
 *   db.exec("INSERT INTO books (title, year) VALUES (?, ?)", "Dune", 1965);
 *   vec.Vec<Book> all = sql.queryAll<Book>(db, "SELECT * FROM books WHERE year > ?", 1960);
 *   defer sql.free(reflect.of(all));
 *   Book b = sql.queryOne<Book>(db, "SELECT * FROM books WHERE id = ?", 7);
 *   i64 n = sql.queryOne<i64>(db, "SELECT count(*) FROM books");
 *
 * Each `?` takes the next argument: an integer, bool or enum (its
 * name), a float, text (a u8[], String, u8 * or string literal), bytes
 * as sql.blob(b), a struct, slice or vec.Vec (as JSON text), or a
 * pointer to any of these (a null pointer is NULL). A struct is filled
 * by column name: a field's @key, else its name (exactly, then ignoring
 * case); @skip and private fields are left alone. Every failure throws:
 * ERR_SQL with SQLite's message, ERR_NO_ROWS, ERR_SCAN, ERR_ARG. */

/* Thrown by db.exec, queryAll and queryOne when SQLite refuses the
 * statement: the message is SQLite's, then the query */
pub const i64 ERR_SQL = 0x73716C01;

/* Thrown by queryOne when the query returns no row */
pub const i64 ERR_NO_ROWS = 0x73716C02;

/* Thrown when a column can't go into its field: text into an i64, a
 * name that isn't a member of the enum */
pub const i64 ERR_SCAN = 0x73716C03;

/* Thrown for an argument SQLite has no place for, a count of arguments
 * that doesn't match the `?`s, or a null database */
pub const i64 ERR_ARG = 0x73716C04;

pub extern "c" u8 *sqlite3_column_name(sqlite3_stmt *stmt, i32 iCol);
pub extern "c" i32 sqlite3_column_count(sqlite3_stmt *stmt);
pub extern "c" i32 sqlite3_changes(sqlite3 *db);
pub extern "c" i64 sqlite3_last_insert_rowid(sqlite3 *db);

/* Bytes for a BLOB parameter: db.exec("... VALUES (?)", sql.blob(png)).
 * A plain u8[] is text. */
pub struct Blob {
    u8[] data;
};

pub Blob blob(u8[] data) {
    Blob b;
    b.data = data;
    return b;
}

/* Frees what queryAll and queryOne allocated in v (Strings, slices,
 * pointed-to values) and zeroes it */
pub void free(reflect.Value v) {
    json.free(v);
}

static void sqlFail(Db *db, u8[] query) {
    throw(ERR_SQL, "sql: %s (in: %.*s)", sqlite3_errmsg(db.conn), query.len, query.ptr);
}

static bool isByte(reflect.Type *t) {
    return t && t.kind == reflect.Kind.int && t.size == 1;
}

static bool isBlob(reflect.Type *t) {
    return t.kind == reflect.Kind.structType && str.equals(t.name, "sql.Blob");
}

static bool isVec(reflect.Type *t) {
    return t.kind == reflect.Kind.structType && str.startsWith(t.name, "vec.Vec<");
}

/* Text sqlite copies: bound with SQLITE_TRANSIENT, so `s` may go away */
static i32 bindText(sqlite3_stmt *st, i32 i, u8[] s) {
    return sqlite3_bind_text(st, i, s.ptr ? s.ptr : "", s.len, (void *)SQLITE_TRANSIENT);
}

/* Argument v into parameter i (from 1) */
static i32 bindValue(sqlite3_stmt *st, i32 i, reflect.Value v) {
    reflect.Type *t = v.type;
    switch (t.kind) {
        case reflect.Kind.int:
        case reflect.Kind.boolean:
            return sqlite3_bind_int64(st, i, reflect.getInt(v));
        case reflect.Kind.float:
            return sqlite3_bind_double(st, i, reflect.getFloat(v));
        case reflect.Kind.enumType: {
            u8[] name = reflect.nameOf(t, reflect.getInt(v));
            if (name.len) { return bindText(st, i, name); }
            return sqlite3_bind_int64(st, i, reflect.getInt(v));
        }
        case reflect.Kind.string: {
            String *s = v.ptr;
            return bindText(st, i, (*s)[0:]);
        }
        case reflect.Kind.slice:
            if (isByte(t.elem)) { return bindText(st, i, *(u8[] *)v.ptr); }
            break;
        case reflect.Kind.pointer: {
            void *p = *(void **)v.ptr;
            if (!p) { return sqlite3_bind_null(st, i); }
            if (isByte(t.elem)) { return bindText(st, i, cstr.view((u8 *)p)); }
            return bindValue(st, i, reflect.__value(p, t.elem));
        }
        case reflect.Kind.structType:
            if (isBlob(t)) {
                Blob *b = v.ptr;
                return sqlite3_bind_blob(st, i, b.data.ptr ? b.data.ptr : "", b.data.len,
                                         (void *)SQLITE_TRANSIENT);
            }
            break;
        default:
            break;
    }
    if (t.kind == reflect.Kind.structType || t.kind == reflect.Kind.slice || t.kind == reflect.Kind.array) {
        scoped String j = json.encode(v);
        return bindText(st, i, j[0:]);
    }
    throw(ERR_ARG, "sql: argument %d: SQLite has no place for a %.*s", i, t.name.len, t.name.ptr);
    return SQLITE_MISUSE;
}

/* The query prepared, with args bound: throws ERR_SQL or ERR_ARG */
static sqlite3_stmt *sqlOpen(Db *db, u8[] query, reflect.Value[] args) {
    if (!db) { throw(ERR_ARG, "sql: the database is null (did sql.open fail?)"); }
    sqlite3_stmt *st = null;
    u8 *tail = null;
    if (sqlite3_prepare_v2(db.conn, query.ptr, query.len, &st, &tail) != SQLITE_OK || !st) {
        sqlite3_finalize(st);
        sqlFail(db, query);
    }
    if (!sqlTailIsEmpty(db, query, tail)) {
        sqlite3_finalize(st);
        throw(ERR_SQL, "sql: one statement at a time (use sql.execRaw for a script): %.*s", query.len, query.ptr);
    }
    i32 want = sqlite3_bind_parameter_count(st);
    if (want != args.len) {
        sqlite3_finalize(st);
        throw(ERR_ARG, "sql: the query has %d parameter(s), and %d argument(s) were given: %.*s",
              want, args.len, query.len, query.ptr);
    }
    for (i64 i = 0; i < args.len; i++) {
        i32 rc;
        try {
            rc = bindValue(st, i + 1, args[i]);
        } catch (Error e) {
            sqlite3_finalize(st);
            throw(e);
        }
        if (rc != SQLITE_OK) {
            sqlite3_finalize(st);
            sqlFail(db, query);
        }
    }
    return st;
}

/* Runs one statement with `?`s from args, as many rows as it returns
 * (they are dropped); answers how many rows it inserted, changed or
 * deleted. Throws ERR_SQL with SQLite's message:
 *   i64 n = db.exec("UPDATE books SET year = ? WHERE id = ?", 1965, id); */
pub i64 Db.exec(u8[] query, any ...args) {
    sqlite3_stmt *st = sqlOpen(self, query, args);
    i32 rc;
    while ((rc = sqlite3_step(st)) == SQLITE_ROW) { }
    sqlite3_finalize(st);
    if (rc != SQLITE_DONE) { sqlFail(self, query); }
    return sqlite3_changes(self.conn);
}

/* The rowid of the last row this connection inserted */
pub i64 Db.lastInsertId() {
    return sqlite3_last_insert_rowid(self.conn);
}

/* A row's columns mapped to T's fields: the field for each column, -1 for none */
static i64 *mapFields(sqlite3_stmt *st, reflect.Type *t, i64 cols) {
    i64 *fieldOf = mem.alloc(sizeof(i64) * (cols + 1));
    for (i64 i = 0; i < cols; i++) {
        u8[] name = cstr.view(sqlite3_column_name(st, i));
        i64 found = -1;
        for (i64 j = 0; j < t.fields.len && found < 0; j++) {
            reflect.Field *f = &t.fields[j];
            if (!f.skip && !f.isPrivate && str.equals(f.key, name)) { found = j; }
        }
        for (i64 j = 0; j < t.fields.len && found < 0; j++) {
            reflect.Field *f = &t.fields[j];
            if (!f.skip && !f.isPrivate && str.equalsFold(f.key, name)) { found = j; }
        }
        fieldOf[i] = found;
    }
    return fieldOf;
}

static void scanError(sqlite3_stmt *st, i64 i, reflect.Type *t, u8 *why) {
    throw(ERR_SCAN, "sql: column %s: %s into a %.*s", sqlite3_column_name(st, i), why, t.name.len, t.name.ptr);
}

/* A copy of n bytes at p, with a NUL after them */
static u8 *copyBytes(void *p, i64 n) {
    u8 *out = mem.alloc(n + 1);
    if (n) { mem.copy(out, p, n); }
    out[n] = 0;
    return out;
}

/* Column i of the current row into v, a zeroed value */
static void scanValue(sqlite3_stmt *st, i64 i, reflect.Value v) {
    reflect.Type *t = v.type;
    i32 type = sqlite3_column_type(st, i);
    if (type == SQLITE_NULL) { return; }
    switch (t.kind) {
        case reflect.Kind.int:
        case reflect.Kind.boolean: {
            if (type != SQLITE_INTEGER) {
                i64 n;
                if (type == SQLITE3_TEXT && str.toInt(cstr.view(sqlite3_column_text(st, i)), &n)) {
                    reflect.setInt(v, n);
                    return;
                }
                scanError(st, i, t, type == SQLITE_FLOAT ? "a float can't go" : "text that isn't a number can't go");
            }
            if (!reflect.setInt(v, sqlite3_column_int64(st, i))) {
                scanError(st, i, t, "the number doesn't fit");
            }
            return;
        }
        case reflect.Kind.float:
            if (type != SQLITE_INTEGER && type != SQLITE_FLOAT) { scanError(st, i, t, "text can't go"); }
            reflect.setFloat(v, sqlite3_column_double(st, i));
            return;
        case reflect.Kind.enumType: {
            i64 n;
            if (type == SQLITE_INTEGER) {
                n = sqlite3_column_int64(st, i);
            } else if (!reflect.enumValue(t, cstr.view(sqlite3_column_text(st, i)), &n)) {
                scanError(st, i, t, "a name that isn't a member can't go");
            }
            reflect.setInt(v, n);
            return;
        }
        case reflect.Kind.string: {
            u8 *s = sqlite3_column_text(st, i);
            *(String *)v.ptr = str.from(s[0:sqlite3_column_bytes(st, i)]);
            return;
        }
        case reflect.Kind.slice:
            if (isByte(t.elem)) {
                i64 n;
                void *p;
                if (type == SQLITE_BLOB) {
                    p = sqlite3_column_blob(st, i);
                } else {
                    p = sqlite3_column_text(st, i);
                }
                n = sqlite3_column_bytes(st, i);
                u8 *b = copyBytes(p, n);
                *(u8[] *)v.ptr = b[0:n];
                return;
            }
            break;
        case reflect.Kind.array:
            if (isByte(t.elem)) {
                void *p = type == SQLITE_BLOB ? sqlite3_column_blob(st, i) : (void *)sqlite3_column_text(st, i);
                i64 n = sqlite3_column_bytes(st, i);
                if (n > t.len) { scanError(st, i, t, "too many bytes to go"); }
                if (n) { mem.copy(v.ptr, p, n); }
                return;
            }
            break;
        case reflect.Kind.pointer: {
            if (isByte(t.elem)) {
                *(u8 **)v.ptr = copyBytes(sqlite3_column_text(st, i), sqlite3_column_bytes(st, i));
                return;
            }
            void *p = mem.allocZero(t.elem.size);
            *(void **)v.ptr = p;
            scanValue(st, i, reflect.__value(p, t.elem));
            return;
        }
        default:
            break;
    }
    if (t.kind == reflect.Kind.structType || t.kind == reflect.Kind.slice || t.kind == reflect.Kind.array) {
        u8 *s = sqlite3_column_text(st, i);
        try {
            json.decode(s[0:sqlite3_column_bytes(st, i)], v);
        } catch (Error e) {
            throw(ERR_SCAN, "sql: column %s: %s", sqlite3_column_name(st, i), e.message);
        }
        return;
    }
    scanError(st, i, t, "a value can't go");
}

/* The current row into x: a struct (not a vec.Vec) by column name,
 * anything else from column 0. fieldOf is mapFields' answer for a struct. */
static void scanRow(sqlite3_stmt *st, reflect.Value x, i64 *fieldOf) {
    reflect.Type *t = x.type;
    if (!fieldOf) {
        scanValue(st, 0, x);
        return;
    }
    i64 cols = sqlite3_column_count(st);
    for (i64 i = 0; i < cols; i++) {
        i64 f = fieldOf[i];
        if (f < 0) { continue; }
        reflect.Field *fd = &t.fields[f];
        scanValue(st, i, reflect.__value((u8 *)x.ptr + fd.offset, fd.type));
    }
}

static bool byName(reflect.Type *t) {
    return t.kind == reflect.Kind.structType && !isVec(t) && !isBlob(t);
}

/* Every row of the query, each into a T: a struct by column name, any
 * other T from the first column. Free it with sql.free(reflect.of(rows)).
 *   vec.Vec<Book> books = sql.queryAll<Book>(db, "SELECT * FROM books WHERE year > ?", 1960); */
pub vec.Vec<T> queryAll<T>(Db *db, u8[] query, any ...args) {
    sqlite3_stmt *st = sqlOpen(db, query, args);
    vec.Vec<T> out = vec.new<T>();
    T x;
    mem.set(&x, 0, sizeof(T));
    reflect.Value xv = reflect.of(x);
    i64 *fieldOf = null;
    i32 rc;
    try {
        if (byName(xv.type)) { fieldOf = mapFields(st, xv.type, sqlite3_column_count(st)); }
        while ((rc = sqlite3_step(st)) == SQLITE_ROW) {
            scanRow(st, xv, fieldOf);
            out.push(x);
            mem.set(&x, 0, sizeof(T));
        }
        if (rc != SQLITE_DONE) { sqlFail(db, query); }
    } catch (Error e) {
        mem.free(fieldOf);
        sqlite3_finalize(st);
        json.free(reflect.of(x));
        json.free(reflect.of(out));
        throw(e);
    }
    mem.free(fieldOf);
    sqlite3_finalize(st);
    return out;
}

/* The first row of the query as a T (a struct by column name, any other
 * T from the first column); throws ERR_NO_ROWS when there is none:
 *   i64 n = sql.queryOne<i64>(db, "SELECT count(*) FROM books");
 *   i64 id = sql.queryOne<i64>(db, "INSERT INTO books (title) VALUES (?) RETURNING id", "Dune"); */
pub T queryOne<T>(Db *db, u8[] query, any ...args) {
    sqlite3_stmt *st = sqlOpen(db, query, args);
    T x;
    mem.set(&x, 0, sizeof(T));
    reflect.Value xv = reflect.of(x);
    i64 *fieldOf = null;
    try {
        i32 rc = sqlite3_step(st);
        if (rc == SQLITE_DONE) { throw(ERR_NO_ROWS, "sql: queryOne: no rows (in: %.*s)", query.len, query.ptr); }
        if (rc != SQLITE_ROW) { sqlFail(db, query); }
        if (byName(xv.type)) { fieldOf = mapFields(st, xv.type, sqlite3_column_count(st)); }
        scanRow(st, xv, fieldOf);
        /* the rest of the statement runs (an INSERT ... RETURNING of many rows) */
        while ((rc = sqlite3_step(st)) == SQLITE_ROW) { }
        if (rc != SQLITE_DONE) { sqlFail(db, query); }
    } catch (Error e) {
        mem.free(fieldOf);
        sqlite3_finalize(st);
        json.free(reflect.of(x));
        throw(e);
    }
    mem.free(fieldOf);
    sqlite3_finalize(st);
    return x;
}
#endif
