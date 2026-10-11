import cstr, sql, test;

/* sql.exec and sql.select rejected SQL_NULL parameters (they fell into the
 * default case, which returned SQLITE_OK as the error) while
 * sql.execPrepared accepted them. They bind null now; an unknown type is
 * still an error. */

i64 count(sql.Db *ctx, u8 *sqlVal) {
    sql.Row row;
    i64 n = 0;
    if (!sql.select(ctx, &row, cstr.view(sqlVal))) { return -1; }
    while (sql.next(&row)) { n++; }
    return n;
}

i32 main() {
    printf("Test - sql null params: ");
    i64 correct = 0, tests = 0;
    sql.Row row;
    sql.Param p[3];
    bool ok;
    sql.Db *ctx = sql.open(":memory:");

    tests++; if (ctx) { correct++; }
    if (!ctx) {
        test.printResult(correct, tests);
        printf("====\n");
        return 0;
    }
    sql.exec(ctx, "CREATE TABLE t(a, b, c);");

    p[0].type = sql.SQLITE_INTEGER; p[0].intVal = 1;
    p[1].type = sql.SQLITE_NULL;
    p[2].type = sql.SQLITE_TEXT;    p[2].str = "x";
    tests++; if (sql.exec(ctx, "INSERT INTO t VALUES(?, ?, ?);", p, 3)) { correct++; }
    tests++; if (count(ctx, "SELECT * FROM t WHERE a = 1 AND b IS NULL AND c = 'x';") == 1) { correct++; }

    p[0].type = sql.SQLITE_NULL;
    tests++; if (sql.exec(ctx, "INSERT INTO t VALUES(?, 2, 3);", p, 1)) { correct++; }
    tests++; if (count(ctx, "SELECT * FROM t WHERE a IS NULL AND b = 2;") == 1) { correct++; }

    /* sql.select with a null parameter */
    ok = sql.select(ctx, &row, "SELECT ? IS null, typeof(?1);", p, 1);
    tests++;
    if (ok && sql.next(&row) && row.col[0].intVal == 1 && row.col[1].type == sql.SQLITE_TEXT &&
            row.col[1].str.equals("null")) {
        correct++;
    }
    if (ok) { sql.rowRelease(&row); }

    /* an unknown type is a failure and runs nothing */
    p[0].type = 99;
    p[0].intVal = 5;
    tests++; if (!sql.exec(ctx, "INSERT INTO t VALUES(?, 9, 9);", p, 1)) { correct++; }
    tests++; if (count(ctx, "SELECT * FROM t WHERE b = 9;") == 0) { correct++; }
    tests++; if (!sql.select(ctx, &row, "SELECT ?;", p, 1)) { correct++; }

    sql.close(ctx);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
