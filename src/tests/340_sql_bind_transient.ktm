import mem, sql, str, test;

/* Text and blob parameters were bound with SQLITE_STATIC, so sqlite kept
 * reading the caller's buffer while the rows were stepped: reusing or
 * freeing it after SqlSelect/SqlExecPrepared changed (or read freed)
 * data. They are bound with SQLITE_TRANSIENT now (sqlite copies them). */

bool allRows(sql.Row *row, u8 *want, i64 count, bool prepared) {
    i64 n = 0;
    bool ok = true;
    while (true) {
        if (prepared) {
            if (!sql.nextPrepared(row)) { break; }
        } else if (!sql.next(row)) {
            break;
        }
        if (row.col[0].type != sql.SQLITE_TEXT || str.cmp(row.col[0].str, want)) {
            ok = false;
        }
        n++;
    }
    return ok && n == count;
}

bool selectText(sql.Db *ctx) {
    sql.Row row;
    sql.Param q[1];
    u8 *key = str.dup("original");
    q[0].type = sql.SQLITE_TEXT;
    q[0].str = key;
    if (!sql.select(ctx, &row, "SELECT ?, id FROM t;", q, 1)) { return false; }
    str.copy(key, "CHANGED!");
    mem.free(key);
    return allRows(&row, "original", 3, false);
}

bool selectBlob(sql.Db *ctx) {
    sql.Row row;
    sql.Param q[1];
    u8 *blob = mem.alloc(4);
    i64 n = 0;
    bool ok = true;
    mem.copy(blob, "\x01\x02\x03\x04", 4);
    q[0].type = sql.SQLITE_BLOB;
    q[0].blob = blob;
    q[0].blobLen = 4;
    if (!sql.select(ctx, &row, "SELECT ?, id FROM t;", q, 1)) { return false; }
    mem.set(blob, 0xEE, 4);
    mem.free(blob);
    while (sql.next(&row)) {
        u8 *b = row.col[0].blob;
        if (row.col[0].type != sql.SQLITE_BLOB || row.col[0].len != 4 ||
                b[0] != 1 || b[1] != 2 || b[2] != 3 || b[3] != 4) {
            ok = false;
        }
        n++;
    }
    return ok && n == 3;
}

bool insertThenFree(sql.Db *ctx) {
    sql.Row row;
    sql.Param q[1];
    u8 *name = str.dup("kept");
    q[0].type = sql.SQLITE_TEXT;
    q[0].str = name;
    if (!sql.exec(ctx, "INSERT INTO names(name) VALUES(?);", q, 1)) { return false; }
    str.copy(name, "gone");
    mem.free(name);
    if (!sql.select(ctx, &row, "SELECT name FROM names;")) { return false; }
    return allRows(&row, "kept", 1, false);
}

bool preparedText(sql.Db *ctx) {
    sql.Row row;
    sql.Param q[1];
    bool ok;
    sql.Stmt *st = sql.prepare(ctx, "SELECT ?, id FROM t;");
    u8 *key = str.dup("prepared");
    q[0].type = sql.SQLITE_TEXT;
    q[0].str = key;
    if (sql.execPrepared(st, &row, q) != sql.SQLITE_ROW) {
        sql.finalize(st);
        return false;
    }
    str.copy(key, "XXXXXXXX");
    mem.free(key);
    ok = allRows(&row, "prepared", 3, true);
    sql.finalize(st);
    return ok;
}

i32 main() {
    printf("Test - sql bind transient: ");
    i64 correct = 0, tests = 0;
    sql.Db *ctx = sql.open(":memory:");

    if (ctx) {
        sql.execRaw(ctx, "CREATE TABLE t(id INTEGER);");
        sql.execRaw(ctx, "INSERT INTO t VALUES(1),(2),(3);");
        sql.execRaw(ctx, "CREATE TABLE names(name TEXT);");
    }

    tests++; if (ctx) { correct++; }
    tests++; if (ctx && selectText(ctx)) { correct++; }
    tests++; if (ctx && selectBlob(ctx)) { correct++; }
    tests++; if (ctx && insertThenFree(ctx)) { correct++; }
    tests++; if (ctx && preparedText(ctx)) { correct++; }

    if (ctx) { sql.close(ctx); }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
