import config, proc, reflect, str, test;

/* config.fromEnv checks validate's rules (@min, @max, @oneOf, @email,
 * @url) on the settings, set or left at their defaults, and names each
 * variable in its error: "SHELF_MAX_BOOKMARKS: must be at least 1",
 * every problem in one ERR_CONFIG. @required keeps config's meaning (the
 * variable must be set). */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

struct Db {
    @min(1) @max(100) i64 pool;
    @url u8[] url;
};

struct Settings {
    @min(1) i64 maxBookmarks;
    @oneOf("dev", "prod") u8[] mode;
    @required @email String admin;
    @min(3) String name;
    i64 port;
    Db db;
    @env("T821_RATIO") @max(1) f64 ratio;
};

/* fromEnv's error ("" when it passed) */
String load(Settings *s) {
    String got;
    try {
        config.fromEnv(reflect.of(*s), "T821_");
    } catch (Error e) {
        got = str.format("%s|%d", e.message, e.code == config.ERR_CONFIG);
    }
    return got;
}

bool has(String *got, u8[] want, u8 *what) {
    bool ok = str.contains((*got)[0:], want);
    check(ok, what);
    if (!ok) { printf("  got: %.*s\n", got.len, got.ptr); }
    return ok;
}

Settings fresh() {
    Settings s;
    s.maxBookmarks = 10;
    s.port = 8080;
    s.db.pool = 4;
    return s;
}

i32 main() {
    printf("Test - config.fromEnv with validate's rules: ");

    /* every rule broken, one error naming each variable */
    proc.setenv("T821_MAX_BOOKMARKS", "0");
    proc.setenv("T821_MODE", "test");
    proc.setenv("T821_ADMIN", "nope");
    proc.setenv("T821_NAME", "ab");
    proc.setenv("T821_DB_POOL", "500");
    proc.setenv("T821_DB_URL", "x");
    proc.setenv("T821_RATIO", "1.5");
    Settings s = fresh();
    String got = load(&s);
    has(&got, "config: T821_MAX_BOOKMARKS: must be at least 1; ", "@min names the variable");
    has(&got, "T821_MODE: must be one of dev, prod; ", "@oneOf");
    has(&got, "T821_ADMIN: not an email address; ", "@email");
    has(&got, "T821_NAME: must have at least 3 characters; ", "@min on a String");
    has(&got, "T821_DB_POOL: must be at most 100; ", "a nested struct's field");
    has(&got, "T821_DB_URL: not a URL; ", "@url");
    has(&got, "T821_RATIO: must be at most 1|1", "@env's own name; ERR_CONFIG");
    got.free();
    config.free(reflect.of(s));

    /* all good */
    proc.setenv("T821_MAX_BOOKMARKS", "25");
    proc.setenv("T821_MODE", "prod");
    proc.setenv("T821_ADMIN", "ann@example.com");
    proc.setenv("T821_NAME", "shelf");
    proc.setenv("T821_DB_POOL", "8");
    proc.setenv("T821_DB_URL", "postgres://db/shelf");
    proc.setenv("T821_RATIO", "0.5");
    s = fresh();
    got = load(&s);
    check(got.len == 0 && s.maxBookmarks == 25 && s.db.pool == 8 && str.equals(s.mode, "prod"),
          "values that keep the rules load");
    got.free();
    config.free(reflect.of(s));

    /* a default is checked too; an unset optional text passes */
    proc.unsetenv("T821_MAX_BOOKMARKS");
    proc.unsetenv("T821_MODE");
    proc.unsetenv("T821_NAME");
    proc.unsetenv("T821_DB_URL");
    s = fresh();
    s.maxBookmarks = 0;
    got = load(&s);
    has(&got, "config: T821_MAX_BOOKMARKS: must be at least 1|1", "a default that breaks a rule");
    check(!str.contains(got[0:], "T821_MODE") && !str.contains(got[0:], "T821_NAME"),
          "unset text with no @required passes its rules");
    got.free();
    config.free(reflect.of(s));

    /* a value that can't be read isn't also checked; @required stays config's */
    proc.setenv("T821_MAX_BOOKMARKS", "lots");
    proc.unsetenv("T821_ADMIN");
    s = fresh();
    got = load(&s);
    has(&got, "config: T821_MAX_BOOKMARKS: `lots` isn't a whole number that fits i64; T821_ADMIN is required|1",
        "a bad value and a missing @required: one error each");
    got.free();
    config.free(reflect.of(s));

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
