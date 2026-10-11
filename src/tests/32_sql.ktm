import libc, sql, test;

#define DB_NAME ("./_test.db")

bool testInsert(sql.Db *ctx) {
    auto err = sql.execRaw(ctx,
            "INSERT INTO example(id,name) VALUES 
        (1,'Foo'),
        (2,'Bar'),
        (3,'Baz'),
        (4,'Dog'),
        (5,'Cat');");
    if (err.len) {
        printf("%s\n",err);
        err.free();
        return false;
    }
    return true;
}

bool testDelete(sql.Db *ctx) {
    sql.Row row;
    i64 count = 0;

    /* Delete one */
    auto ok = sql.exec(ctx,"DELETE FROM example WHERE id = 2;");
    if (!ok) { goto error; }

    /* Count them 5-1 = 4*/
    ok = sql.select(ctx,&row,"SELECT * FROM example;");
    if (!ok) { goto error; }

    count = 0;
    while (sql.next(&row)) {
        count++;
    }

    if (count != 4) { goto error; }

    /* Ensure that despite the query being ok that the count is 0, as 
     * it has been deleted. */
    ok = sql.select(ctx,&row,"SELECT * FROM example WHERE id = 2;");
    if (!ok) { goto error; }

    count = 0;
    while (sql.next(&row)) {
        count++;
    }

    return count == 0;

error:
    return false;
}

bool testSelect(sql.Db *ctx) {
    sql.Row row;
    auto ok = sql.select(ctx,&row,"SELECT * FROM example;");
    i64 count = 0;
    if (ok) {
        while (sql.next(&row)) {
            auto id = row.col[0].intVal;
            auto name = row.col[1].str;
            count++;
            switch (count) {
                case 1: {
                    if (id != 1 && !name.startsWith("Foo")) {
                        goto error;
                    }
                    break;
                }
                case: {
                    if (id != 2 && !name.startsWith("Bar")) {
                        goto error;
                    }
                    break;
                }
                case: {
                    if (id != 3 && !name.startsWith("Baz")) {
                        goto error;
                    }
                    break;
                }
                case: {
                    if (id != 4 && !name.startsWith("Dog")) {
                        goto error;
                    }
                    break;
                }
                case: {
                    if (id != 5 && !name.startsWith("Cat")) {
                        goto error;
                    }
                    break;
                }
            }
        }
    }
    return count == 5;
error:
    return false;
}

bool testCreate(sql.Db *ctx) {
    auto err = sql.execRaw(ctx,
            "CREATE TABLE IF NOT EXISTS example(
         id INTEGER PRIMARY KEY, 
         name TEXT
       );");
    if (err.len) {
        printf("%s\n",err);
        err.free();
        return false;
    }
    return true;
}

i32 main()
{// These tests need to be run in a specific order.
    printf("Test - sqlite bindings & abstraction: \n");
    i64 tests = 4, correct = 0;
    auto ctx = sql.open(DB_NAME);

    if (testCreate(ctx)) { correct++; }
    if (testInsert(ctx)) { correct++; }
    if (testSelect(ctx)) { correct++; }
    if (testDelete(ctx)) { correct++; }

    /* Destroy connection and free class */
    sql.close(ctx);
    /* Delete the database */
    libc.unlink(DB_NAME);
    test.printResult(correct,tests);
    printf("====\n");
    return 0;
}
