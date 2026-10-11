import test;

import sql;

/* SqlRowRelease on a row of a prepared statement finalized that
 * statement, so the next SqlExecPrepared or SqlFinalizePrepared used a
 * dead handle; and SqlIterPrepared never freed the column array, leaking
 * it on every execution. A row now knows whether it owns its statement. */

i64 runPrepared(sql.Stmt *ps, i64 from) {
    sql.Row row;
    sql.Param p[1];
    i64 n = 0;
    p[0].type = sql.SQLITE_INTEGER;
    p[0].intVal = from;
    if (sql.execPrepared(ps, &row, p) != sql.SQLITE_ROW) { return 0; }
    while (sql.nextPrepared(&row)) { n++; }
    return n;
}

i32 main() {
    printf("Test - sql row release prepared: ");
    i64 correct = 0, tests = 0, i, n, before, delta;
    sql.Row row;
    sql.Param p[1];
    sql.Stmt *ps;
    sql.Db *ctx = sql.open(":memory:");

    tests++; if (ctx) { correct++; }
    if (!ctx) {
        test.printResult(correct, tests);
        printf("====\n");
        return 0;
    }
    sql.execRaw(ctx, "CREATE TABLE t(id INTEGER, s TEXT);");
    sql.execRaw(ctx, "INSERT INTO t VALUES(1,'a'),(2,'b'),(3,'c'),(4,'d');");
    ps = sql.prepare(ctx, "SELECT id, s FROM t WHERE id >= ? ORDER BY id;");
    tests++; if (ps) { correct++; }

    /* Releasing a prepared row early keeps the statement usable. */
    p[0].type = sql.SQLITE_INTEGER;
    p[0].intVal = 2;
    tests++;
    if (ps && sql.execPrepared(ps, &row, p) == sql.SQLITE_ROW && sql.nextPrepared(&row) &&
            row.col[0].intVal == 2) {
        correct++;
    }
    if (ps) { sql.rowRelease(&row); }
    tests++; if (row.stmt == null && row.col == null) { correct++; }
    tests++; if (ps && runPrepared(ps, 1) == 4 && runPrepared(ps, 3) == 2) { correct++; }

    /* Running it to the end and then releasing is harmless too. */
    tests++; if (ps && runPrepared(ps, 4) == 1) { correct++; }
    if (ps) {
        sql.execPrepared(ps, &row, p);
        while (sql.nextPrepared(&row)) {}
        sql.rowRelease(&row);
    }
    tests++; if (ps && runPrepared(ps, 1) == 4) { correct++; }

    /* No column array is leaked per execution: a leaked one is 64 bytes,
     * 1.28 MB over these 20000 runs. On macOS the heap total can also step
     * up by a few KB with nothing leaked: each CPU's malloc magazine keeps
     * its last freed block cached and counted as in use, and the thread may
     * move to another core during the loop (16 cores: up to ~128 KB). So
     * many runs, and an allowance of 128 KB. */
    if (ps) {
        for (i = 0; i < 20; i++) { runPrepared(ps, 1); }
        before = test.heapInUse();
        for (i = 0; i < 20000; i++) { runPrepared(ps, 1); }
        delta = test.heapInUse() - before;
        tests++; if (delta < 128 * 1024) { correct++; } else { printf("leaked %d bytes\n", delta); }
    }
    sql.finalize(ps);

    /* Rows of SqlSelect still own (and finalize) their statement. */
    n = 0;
    tests++;
    if (sql.select(ctx, &row, "SELECT id FROM t;")) {
        while (sql.next(&row)) { n++; }
        sql.rowRelease(&row);
        if (n == 4) { correct++; }
    }
    tests++;
    if (sql.select(ctx, &row, "SELECT id FROM t;") && sql.next(&row)) {
        sql.rowRelease(&row);
        if (row.stmt == null) { correct++; }
    }

    sql.close(ctx);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
