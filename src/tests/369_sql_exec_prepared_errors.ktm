import test;

import sql, str;

/* SqlExecPrepared ignored the return codes of sqlite3_bind_* and skipped
 * parameters of an unknown type, so the statement ran anyway with null in
 * their place and returned SQLITE_DONE. Now a failed bind or an unknown
 * type is returned as the error and the statement does not run. */

/* SQLITE_LIMIT_LENGTH: the longest string or blob sqlite accepts. */
extern "c" i32 sqlite3_limit(sql.sqlite3 *db, i32 id, i32 newVal);
const i64 SQLITE_LIMIT_LENGTH = 0;

i64 count(sql.Db *ctx, u8 *sql) {
    sql.Row row;
    i64 n = 0;
    if (!sql.select(ctx, &row, sql)) { return -1; }
    while (sql.next(&row)) { n++; }
    return n;
}

i32 main() {
    printf("Test - sql exec prepared errors: ");
    i64 correct = 0, tests = 0;
    sql.Row row;
    sql.Param p[2];
    i32 rc;
    sql.Db *ctx = sql.open(":memory:");
    sql.Stmt *ins, *sel;

    tests++; if (ctx) { correct++; }
    if (!ctx) {
        test.printResult(correct, tests);
        printf("====\n");
        return 0;
    }
    sql.exec(ctx, "CREATE TABLE t(a, b);");
    ins = sql.prepare(ctx, "INSERT INTO t VALUES(?, ?);");
    sel = sql.prepare(ctx, "SELECT ?;");
    tests++; if (ins && sel) { correct++; }

    /* an unknown type in the second parameter: SQLITE_MISMATCH, no row */
    p[0].type = sql.INT; p[0].intVal = 1;
    p[1].type = 99;      p[1].intVal = 2;
    rc = sql.execPrepared(ins, null, p);
    tests++; if (rc == sql.SQLITE_MISMATCH) { correct++; }
    tests++; if (count(ctx, "SELECT * FROM t;") == 0) { correct++; }

    /* the same with a row: no row is handed out */
    row.stmt = (sql.sqlite3_stmt *)1;
    rc = sql.execPrepared(sel, &row, p + 1);
    tests++; if (rc == sql.SQLITE_MISMATCH && row.stmt == null) { correct++; }

    /* a bind that sqlite refuses: a string over the length limit */
    sqlite3_limit(ctx.conn, SQLITE_LIMIT_LENGTH, 4);
    p[1].type = sql.TEXT; p[1].str = "too long";
    rc = sql.execPrepared(ins, null, p);
    tests++; if (rc == sql.SQLITE_TOOBIG) { correct++; }
    sqlite3_limit(ctx.conn, SQLITE_LIMIT_LENGTH, 1000000);
    tests++; if (count(ctx, "SELECT * FROM t;") == 0) { correct++; }

    /* the statement still works after an error, and SQL_NULL binds null */
    p[1].type = sql.NULL_TYPE;
    rc = sql.execPrepared(ins, null, p);
    tests++; if (rc == sql.SQLITE_DONE) { correct++; }
    tests++; if (count(ctx, "SELECT * FROM t WHERE a = 1 AND b IS NULL;") == 1) { correct++; }

    p[1].type = sql.TEXT; p[1].str = "ok";
    rc = sql.execPrepared(sel, &row, p + 1);
    tests++;
    if (rc == sql.SQLITE_ROW && sql.nextPrepared(&row) && row.col[0].type == sql.TEXT &&
            !str.cmp(row.col[0].str, "ok")) {
        correct++;
    }
    sql.rowRelease(&row);

    sql.finalize(ins);
    sql.finalize(sel);
    sql.close(ctx);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
