import proc, test;

/* proc.expandEnv (v0.18.0): ${NAME} and ${NAME:-fallback} take the
 * environment's value; $${ is a literal ${; a bare $NAME and an
 * unterminated ${ are kept. */

i64 check(u8[] text, u8[] want)
{
    String got = proc.expandEnv(text);
    defer got.free();
    if (got.len == want.len && (want.len == 0 || got[0:got.len].equals(want))) { return 1; }
    printf("\n  expandEnv(\"%s\") = \"%s\", want \"%s\"", text, got, want);
    return 0;
}

i32 main()
{
    printf("Test - proc expandEnv: ");
    i64 correct = 0, tests = 0;

    proc.setenv("KTM_T673_HOST", "db.example.com");
    proc.setenv("KTM_T673_EMPTY", "");
    proc.unsetenv("KTM_T673_UNSET");

    tests++; correct += check("host: ${KTM_T673_HOST}\n", "host: db.example.com\n");
    tests++; correct += check("${KTM_T673_HOST}${KTM_T673_HOST}", "db.example.comdb.example.com");
    tests++; correct += check("a${KTM_T673_UNSET}b", "ab");
    tests++; correct += check("port: ${KTM_T673_UNSET:-5432}", "port: 5432");
    tests++; correct += check("${KTM_T673_EMPTY:-x}", "x");
    tests++; correct += check("${KTM_T673_HOST:-x}", "db.example.com");
    tests++; correct += check("${KTM_T673_UNSET:-}", "");
    tests++; correct += check("pa$$word $HOME $${KTM_T673_HOST}", "pa$$word $HOME ${KTM_T673_HOST}");
    tests++; correct += check("cut ${KTM_T673_HOST", "cut ${KTM_T673_HOST");
    tests++; correct += check("", "");
    tests++; correct += check("end $", "end $");

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
