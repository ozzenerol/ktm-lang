import test;

import sql;

/* SqlPrepare ignored sqlite3_prepare_v2's result and returned an object
 * with a null stmt, which SqlExecPrepared then crashed on. It returns null
 * now. SqlExecPrepared with null params on a statement with parameters
 * read params[0]; null now leaves every parameter unbound (SQL null). */

i64 countRows(sql.Db *ctx, u8 *sql) {
    sql.Row row;
    i64 n = 0;
    if (!sql.select(ctx, &row, sql)) { return -1; }
    while (sql.next(&row)) { n++; }
    return n;
}

i32 main() {
    printf("Test - sql prepare failure: ");
    i64 correct = 0, tests = 0;
    sql.Stmt *ps;
    sql.Param p[2];
    sql.Db *ctx = sql.open(":memory:");

    tests++; if (ctx) { correct++; }
    if (!ctx) {
        test.printResult(correct, tests);
        printf("====\n");
        return 0;
    }
    sql.exec(ctx, "CREATE TABLE t(x, y);");

    /* failed prepares */
    tests++; if (sql.prepare(ctx, "SELEC broken ?;") == null) { correct++; }
    tests++; if (sql.prepare(ctx, "SELECT * FROM nosuch;") == null) { correct++; }
    tests++; if (sql.prepare(ctx, "") == null) { correct++; }
    tests++; if (sql.execPrepared(null, null, null) != sql.SQLITE_DONE) { correct++; }
    sql.finalize(null);

    /* null params on a parameterised statement */
    ps = sql.prepare(ctx, "INSERT INTO t VALUES(?, ?);");
    tests++; if (ps && ps.paramsCount == 2) { correct++; }
    tests++; if (ps && sql.execPrepared(ps, null, null) == sql.SQLITE_DONE) { correct++; }
    tests++; if (countRows(ctx, "SELECT * FROM t WHERE x IS NULL AND y IS NULL;") == 1) { correct++; }

    /* and real params still bind afterwards */
    p[0].type = sql.SQLITE_INTEGER; p[0].intVal = 7;
    p[1].type = sql.SQLITE_INTEGER; p[1].intVal = 8;
    tests++; if (ps && sql.execPrepared(ps, null, p) == sql.SQLITE_DONE) { correct++; }
    tests++; if (countRows(ctx, "SELECT * FROM t WHERE x = 7 AND y = 8;") == 1) { correct++; }
    sql.finalize(ps);

    sql.close(ctx);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
