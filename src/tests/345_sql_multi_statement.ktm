#include "testhelper.ktm"

import sql;

/* SqlQuery, SqlSelect and SqlPrepare ran only the first statement of a
 * string holding several and silently dropped the rest. They fail now
 * when anything but whitespace or comments follows the first statement;
 * SqlExecRaw still runs them all. */

bool hasTable(sql.Db *ctx, u8 *name) {
    sql.Row row;
    sql.Param p[1];
    bool found = false;
    p[0].type = sql.SQLITE_TEXT;
    p[0].str = name;
    if (!sql.select(ctx, &row, "SELECT name FROM sqlite_master WHERE name = ?;", p, 1)) {
        return false;
    }
    while (sql.next(&row)) { found = true; }
    return found;
}

i32 main() {
    printf("Test - sql multi statement: ");
    i64 correct = 0, tests = 0;
    sql.Row row;
    sql.Stmt *ps;
    sql.Db *ctx = sql.open(":memory:");

    tests++; if (ctx) { correct++; }
    if (!ctx) {
        PrintResult(correct, tests);
        printf("====\n");
        return 0;
    }

    tests++; if (!sql.exec(ctx, "CREATE TABLE a(x); CREATE TABLE b(y);")) { correct++; }
    tests++; if (!hasTable(ctx, "a") && !hasTable(ctx, "b")) { correct++; }

    tests++; if (sql.exec(ctx, "CREATE TABLE c(x);   -- trailing comment\n /* and another */ \n")) { correct++; }
    tests++; if (sql.exec(ctx, "INSERT INTO c VALUES(1);;")) { correct++; }
    tests++; if (hasTable(ctx, "c")) { correct++; }

    tests++; if (!sql.select(ctx, &row, "SELECT x FROM c; DELETE FROM c;")) { correct++; }
    tests++; if (!sql.exec(ctx, "SELECT 1; SELEC broken")) { correct++; }

    ps = sql.prepare(ctx, "INSERT INTO c VALUES(2); DROP TABLE c;");
    tests++; if (ps == null) { correct++; }
    sql.finalize(ps);
    ps = sql.prepare(ctx, "INSERT INTO c VALUES(2); -- note");
    tests++; if (ps && sql.execPrepared(ps, null, null) == sql.SQLITE_DONE) { correct++; }
    sql.finalize(ps);

    i64 n = 0;
    if (sql.select(ctx, &row, "SELECT x FROM c;")) {
        while (sql.next(&row)) { n++; }
    }
    tests++; if (n == 2) { correct++; }

    /* SqlExecRaw runs every statement */
    tests++; if (sql.execRaw(ctx, "CREATE TABLE d(x); CREATE TABLE e(y);") == null) { correct++; }
    tests++; if (hasTable(ctx, "d") && hasTable(ctx, "e")) { correct++; }

    sql.close(ctx);
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
