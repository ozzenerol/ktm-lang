import sql, str, test;

/* v0.3.1: sql with u8[] and String. open, prepare, exec, select and
 * execRaw take the SQL as a u8[] (a slice need not be NUL-terminated:
 * only its bytes are SQL), execRaw gives its error as a String, a text
 * parameter is a u8[] (an empty one binds '', not null), and a text
 * column is a u8[] view. The Db keeps its own copy of its name. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    printf("Test - sql with u8[] and String: ");
    String name = str.from(":memory:");
    sql.Db *db = sql.open(name);
    name.free();
    check(db != null && db.dbname.equals(":memory:"), "open copies the name");

    u8[] script = "CREATE TABLE t(k TEXT, v INTEGER); trailing garbage";
    check(sql.exec(db, script[0:34]), "exec of part of a text");
    String err = sql.execRaw(db, "INSERT INTO t VALUES('a', 1); INSERT INTO t VALUES('', 2);");
    check(!err.ptr, "execRaw ok: an empty String");
    err = sql.execRaw(db, "SELEC 1");
    check(err.ptr && err.contains("syntax error"), "execRaw error: a String");
    err.free();

    sql.Param ps[2];
    ps[0].type = sql.TEXT;
    u8[] key = "bxyz";
    ps[0].str = key[0:1];
    ps[1].type = sql.INT;
    ps[1].intVal = 3;
    check(sql.exec(db, "INSERT INTO t VALUES(?, ?)", ps, 2), "a u8[] text parameter");

    sql.Row row;
    String got = str.new();
    defer got.free();
    check(sql.select(db, &row, "SELECT k, v FROM t ORDER BY v"), "select");
    while (sql.next(&row)) {
        got.appendFormat("%s=%d(%d) ", row.col[0].str, row.col[1].intVal, row.col[0].str.len);
    }
    check(got.equals("a=1(1) =2(0) b=3(1) "), "text columns are u8[] views");
    if (!got.equals("a=1(1) =2(0) b=3(1) ")) { printf("  got %s\n", got); }

    u8[] none;
    ps[0].str = none;
    check(sql.exec(db, "INSERT INTO t VALUES(?, 4)", ps, 1), "an empty text parameter");
    check(sql.select(db, &row, "SELECT k IS NULL, k = '' FROM t WHERE v = 4") && sql.next(&row) &&
          row.col[0].intVal == 0 && row.col[1].intVal == 1, "binds '', not null");
    sql.rowRelease(&row);

    sql.Stmt *st = sql.prepare(db, "SELECT count(*) FROM t; x"[0:23]);
    check(st != null, "prepare of part of a text");
    sql.finalize(st);
    sql.close(db);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
