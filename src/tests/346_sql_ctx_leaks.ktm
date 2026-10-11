#include "testhelper.ktm"

import sql;

/* SqlCtxNew leaked the sqlite handle when opening failed; SqlCtxRelease
 * used sqlite3_close, which fails with SQLITE_BUSY (and keeps the
 * connection) while a statement is still alive; SqlColumn.len was left
 * uninitialised for INTEGER, FLOAT and null columns; sqlite3_open was
 * declared returning bool; the SQL_* type names were not exported. */

#define BAD_DB_PATH "/nonexistent-ktm-dir-346/x.db"

i32 main() {
    printf("Test - sql ctx leaks: ");
    i64 correct = 0, tests = 0, i, before, delta;
    sql.Db *ctx;
    sql.Stmt *ps;
    sql.Row row;
    sql.sqlite3 *db = null;

    /* the SQL_* names are public */
    tests++;
    if (sql.INT == sql.SQLITE_INTEGER && sql.FLOAT == sql.SQLITE_FLOAT &&
            sql.TEXT == sql.SQLITE_TEXT && sql.BLOB == sql.SQLITE_BLOB && sql.NULL_TYPE == sql.SQLITE_NULL) {
        correct++;
    }

    /* sqlite3_open's i32 result */
    tests++; if (sql.sqlite3_open(BAD_DB_PATH, &db) == sql.SQLITE_CANTOPEN) { correct++; }
    sql.sqlite3_close(db);

    /* a failed open frees the handle */
    tests++; if (sql.open(BAD_DB_PATH) == null) { correct++; }
    for (i = 0; i < 20; i++) { sql.open(BAD_DB_PATH); }
    before = HeapInUse();
    for (i = 0; i < 300; i++) { sql.open(BAD_DB_PATH); }
    delta = HeapInUse() - before;
    tests++; if (delta < 16384) { correct++; } else { printf("failed open leaked %d bytes\n", delta); }

    /* releasing a context with a live statement still closes it. A kept
     * connection is ~145 KB, 40 MB over these 280 opens. On macOS the heap
     * total can also step up by ~130 KB once or twice with nothing leaked:
     * each CPU's malloc magazine keeps its last freed block (the closed
     * connection's) cached and counted as in use, and the thread may move
     * to another core during the loop (16 cores: up to ~2.1 MB). So allow
     * under 8 KB per open. */
    for (i = 0; i < 300; i++) {
        if (i == 20) { before = HeapInUse(); }
        ctx = sql.open(":memory:");
        ps = sql.prepare(ctx, "SELECT 1;");
        sql.close(ctx);
        sql.finalize(ps);
    }
    delta = HeapInUse() - before;
    tests++; if (delta < 280 * 8192) { correct++; } else { printf("busy close leaked %d bytes\n", delta); }
    sql.close(null);

    /* len is 0 for columns that are not TEXT or BLOB */
    ctx = sql.open(":memory:");
    tests++;
    if (ctx) {
        bool ok = true;
        for (i = 0; i < 3 && ok; i++) {
            if (!sql.select(ctx, &row, "SELECT 'abcdefgh', 'abcdefgh', 'abcdefgh', 'abcdefgh';") ||
                    !sql.next(&row)) {
                ok = false;
            }
            sql.rowRelease(&row);
            if (!sql.select(ctx, &row, "SELECT 1, 2.5, NULL, 'xy';") || !sql.next(&row)) {
                ok = false;
            } else if (row.col[0].len != 0 || row.col[1].len != 0 || row.col[2].len != 0 ||
                                 row.col[3].len != 2) {
                printf("len %d %d %d %d\n", row.col[0].len, row.col[1].len, row.col[2].len, row.col[3].len);
                ok = false;
            }
            sql.rowRelease(&row);
        }
        if (ok) { correct++; }
        sql.close(ctx);
    }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
