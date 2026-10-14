import config, cstr, fs, json, proc, reflect, str, test;

/* config (v0.20.0): environment variables into a struct (names from the
 * fields in SNAKE_CASE after a prefix, @env("NAME"), @required, defaults
 * kept, nested structs, every kind of field, every problem in one
 * error), a .env file (quotes, export, comments, ${...}, never over the
 * real environment), and the @env/@required attributes' checks. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

enum Level { debug, info, warn };

@json struct Db {
    String host;
    i32 port;
};

@json struct Settings {
    i64 port;
    @required String databaseUrl;
    @env("T801_LOG") u8[] logPath;
    bool verbose;
    f64 ratio;
    Level level;
    u8 small;
    Db db;
    @skip i64 hits;
    String httpURL;
};

bool snake(u8[] in, u8[] want) {
    String s = config.snakeUpper(in);
    bool ok = str.equals(s[0:], want);
    if (!ok) { printf("\n  snakeUpper(%.*s) = %.*s", in.len, in.ptr, s.len, s.ptr); }
    s.free();
    return ok;
}

/* no @json: @env and @required go on any struct */
struct Plain { @required @env("T801_PLAIN") i32 a; };

String gErr;
bool fails(Settings *s, u8[] prefix) {
    try {
        config.fromEnv(reflect.of(*s), prefix);
    } catch (Error e) {
        gErr.free();
        gErr = str.from(cstr.view(e.message));
        return e.code == config.ERR_CONFIG;
    }
    return false;
}

i32 main() {
    printf("Test - config: ");
    check(snake("port", "PORT") && snake("databaseUrl", "DATABASE_URL") && snake("httpURL", "HTTP_URL") &&
          snake("URLPath", "URL_PATH") && snake("v2Name", "V2_NAME") && snake("already_snake", "ALREADY_SNAKE"),
          "snakeUpper");

    reflect.Type *t = reflect.typeOf(Settings);
    check(t.fields[1].required && !t.fields[0].required && str.equals(t.fields[2].env, "T801_LOG") &&
          t.fields[0].env.len == 0, "reflect: Field.required and Field.env");

    proc.setenv("APP_PORT", "9000");
    proc.setenv("APP_DATABASE_URL", "postgres://db/app");
    proc.setenv("T801_LOG", "/var/log/x");
    proc.setenv("APP_VERBOSE", "Yes");
    proc.setenv("APP_LEVEL", "WARN");
    proc.setenv("APP_SMALL", "200");
    proc.setenv("APP_DB_HOST", "db.internal");
    proc.setenv("APP_DB_PORT", "6543");
    proc.setenv("APP_HTTP_URL", "");
    proc.setenv("APP_HITS", "5");
    Settings s;
    s.ratio = 0.25;
    s.httpURL = str.from("http://default");
    config.fromEnv(reflect.of(s), "APP_");
    check(s.port == 9000 && str.equals(s.databaseUrl[0:], "postgres://db/app") && str.equals(s.logPath, "/var/log/x") &&
          s.verbose && s.ratio == 0.25 && s.level == Level.warn && s.small == 200 &&
          str.equals(s.db.host[0:], "db.internal") && s.db.port == 6543 && s.hits == 0 &&
          str.equals(s.httpURL[0:], "http://default"), "every kind of field; defaults kept; \"\" is unset; @skip");
    String j = json.encode(reflect.of(s));
    check(str.find(j[0:], "\"databaseUrl\":\"postgres://db/app\"") >= 0, "@required and @env sit with @json");
    j.free();

    /* every problem at once, the good fields still set */
    proc.unsetenv("APP_DATABASE_URL");
    proc.setenv("APP_PORT", "90x");
    proc.setenv("APP_SMALL", "300");
    proc.setenv("APP_LEVEL", "loud");
    proc.setenv("APP_VERBOSE", "maybe");
    proc.setenv("APP_RATIO", "0.75");
    check(fails(&s, "APP_") && s.ratio == 0.75 && str.equals(s.databaseUrl[0:], "postgres://db/app"), "throws ERR_CONFIG");
    check(str.equals(gErr[0:], "config: APP_PORT: `90x` isn't a whole number that fits i64; "
                     "APP_DATABASE_URL is required; APP_VERBOSE: `maybe` isn't true or false; "
                     "APP_LEVEL: `loud` isn't one of debug, info, warn; APP_SMALL: `300` isn't a whole number that fits u8"),
          "the error names every variable");
    config.free(reflect.of(s));
    check(s.databaseUrl.len == 0 && s.db.host.len == 0, "config.free");

    proc.setenv("T801_PLAIN", "-12");
    Plain pl;
    config.fromEnv(reflect.of(pl));
    check(pl.a == -12, "a struct without @json");

    /* .env */
    fs.writeAll("__801.env",
        "# settings\n"
        "export T801_A=plain value  # a comment\n"
        "T801_B = \"two\\nlines ${T801_A}\"\n"
        "T801_C='${not} \\n as is'\n"
        "T801_D=\"over\n"
        "lines\"\n"
        "\n"
        "T801_E=\n"
        "T801_KEEP=from file\n");
    proc.setenv("T801_KEEP", "from env");
    i64 n = config.loadDotenv("__801.env");
    String a = proc.getenv("T801_A"), b = proc.getenv("T801_B"), c = proc.getenv("T801_C"), d = proc.getenv("T801_D");
    String keep = proc.getenv("T801_KEEP");
    check(n == 5 && str.equals(a[0:], "plain value") && str.equals(b[0:], "two\nlines plain value") &&
          str.equals(c[0:], "${not} \\n as is") && str.equals(d[0:], "over\nlines") && proc.hasEnv("T801_E") &&
          str.equals(keep[0:], "from env"), "a .env file");
    a.free(); b.free(); c.free(); d.free(); keep.free();
    check(config.loadDotenv("__801.env", true) == 6, "override");
    keep = proc.getenv("T801_KEEP");
    check(str.equals(keep[0:], "from file"), "override replaces");
    keep.free();
    check(config.loadDotenv("__801_missing.env") == -1, "no file: -1");
    fs.writeAll("__801.env", "A=1\nB=2\nnot a line\n");
    bool threw = false;
    try { config.loadDotenv("__801.env"); } catch (Error e) {
        threw = e.code == config.ERR_CONFIG && str.equals(cstr.view(e.message), "config: __801.env:3: expected KEY=value");
    }
    check(threw, "a bad line names its number");
    fs.writeAll("__801.env", "A=\"open\n");
    threw = false;
    try { config.loadDotenv("__801.env"); } catch (Error e) { threw = e.code == config.ERR_CONFIG; }
    check(threw, "an open quote");
    fs.remove("__801.env");

    /* the attributes' checks */
    fs.writeAll("__801_bad.ktm", "struct S { @env i64 a; @required(\"x\") i64 b; };\nvoid main() { S s; }\n");
    String w = proc.shellOutput("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit __801_bad.ktm 2>&1");
    check(str.find(w[0:], "`@env` takes the variable's name as a string: @env(\"PORT\")") >= 0, "@env needs a name");
    w.free();
    fs.writeAll("__801_bad.ktm", "struct S { @required(\"x\") i64 b; };\nvoid main() { S s; }\n");
    w = proc.shellOutput("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit __801_bad.ktm 2>&1");
    check(str.find(w[0:], "`@required` takes no arguments") >= 0, "@required takes none");
    w.free();
    fs.remove("__801_bad.ktm");
    gErr.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
