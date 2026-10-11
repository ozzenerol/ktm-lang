import libc, mem, sql, test;

/* SqlExecRaw's check was inverted: it returned sqlite's (null) message on
 * success and null on failure, and leaked the message. It returns an
 * MAlloc'd copy of the error now (Free it), null on success. */

bool fails(sql.Db *ctx, u8 *sql, u8 *want) {
    u8 *err = sql.execRaw(ctx, sql);
    bool ok = err && libc.strstr(err, want) != null;
    if (!ok && err) { printf("%s\n", err); }
    mem.free(err);
    return ok;
}

i32 main() {
    printf("Test - sql exec raw error: ");
    i64 correct = 0, tests = 0, i, before, delta;
    sql.Db *ctx = sql.open(":memory:");

    tests++; if (ctx) { correct++; }
    if (!ctx) {
        test.printResult(correct, tests);
        printf("====\n");
        return 0;
    }

    tests++; if (sql.execRaw(ctx, "CREATE TABLE t(id INTEGER);") == null) { correct++; }
    tests++; if (sql.execRaw(ctx, "INSERT INTO t VALUES(1); INSERT INTO t VALUES(2);") == null) { correct++; }
    tests++; if (fails(ctx, "THIS IS NOT SQL;", "syntax error")) { correct++; }
    tests++; if (fails(ctx, "INSERT INTO nosuch VALUES(1);", "no such table")) { correct++; }
    tests++; if (fails(ctx, "CREATE TABLE t(x);", "already exists")) { correct++; }

    /* The message is copied and sqlite's own copy freed. */
    for (i = 0; i < 50; i++) { mem.free(sql.execRaw(ctx, "SELEC 1;")); }
    before = test.heapInUse();
    for (i = 0; i < 2000; i++) { mem.free(sql.execRaw(ctx, "SELEC 1;")); }
    delta = test.heapInUse() - before;
    tests++; if (delta < 4096) { correct++; } else { printf("leaked %d bytes\n", delta); }

    sql.close(ctx);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
