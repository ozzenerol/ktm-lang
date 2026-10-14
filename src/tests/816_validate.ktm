import cstr, fs, json, net.http, proc, reflect, str, test, validate;

/* validate (v0.21.0): @required, @min/@max (a number's value, a text's
 * length in characters, a slice's in items), @email, @url, @oneOf on
 * any struct; errors per field by JSON key, nested ("address.city"),
 * struct pointers and slices of structs ("tags[1].name"); an empty
 * optional text skips its rules; message() and toJson(); a rule on the
 * wrong kind of field throws ERR_VALIDATE; http.decodeValid through a
 * Mux: 201, 400 on bad JSON, 422 with the field list, a throw still a
 * 500; and the attributes' compile-time checks. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s", what); }
}

@json struct Address {
    @required u8[] city;
    @max(5) u8[] zip;
};

@json struct Tag {
    @required @max(3) u8[] name;
};

@json struct Bookmark {
    @required @url String url;
    @required @min(2) @max(10) String title;
    @oneOf("read", "unread") u8[] status;
    @min(0) @max(5) i64 stars;
    @min(0.5) f64 weight;
    @key("e-mail") @email u8[] email;
    @min(-10) i32 offset;
    Address address;
    Tag[] tags;
    @max(2) u8[][] labels;
};

/* no @json: the rules go on any struct */
struct Plain {
    @required Address *owner;
    @required bool agreed;
    @max(3) u8[] note;
};

struct Wrong { @email i64 n; };

bool has(String *s, u8[] part) {
    bool ok = str.find((*s)[0:], part) >= 0;
    if (!ok) { printf("\n  no `%.*s` in: %.*s", part.len, part.ptr, s.len, s.ptr); }
    return ok;
}

void createBookmark(http.Writer *w, http.Request *r) {
    Bookmark b = http.decodeValid<Bookmark>(r);
    w.text(201, "%s", b.title.cstr());
}

void boom(http.Writer *w, http.Request *r) {
    throw(7, "boom");
}

i32 gCode;
String gBody;
void post(http.Mux *mux, u8[] target, u8[] body) {
    http.Request r = http.testRequest("POST", target, body);
    http.Writer w = http.recorder();
    mux.serve(&w, &r);
    gCode = w.code;
    gBody.free();
    gBody = str.from(w.body[0:]);
    w.free();
    r.free();
}

i32 main() {
    printf("Test - validate: ");

    /* every rule broken */
    Bookmark b;
    b.title = str.from("a title that is too long");
    b.status = "maybe";
    b.stars = 9;
    b.weight = 0.1;
    b.email = "nope@x";
    b.offset = -11;
    b.address.zip = "1234567";
    Tag ts[2];
    ts[0].name = "ok";
    ts[1].name = "toolong";
    b.tags = ts[0:2];
    u8[] ls[3];
    ls[0] = "a"; ls[1] = "b"; ls[2] = "c";
    b.labels = ls[0:3];
    validate.Errors errs;
    check(!validate.check(reflect.of(b), &errs), "invalid");
    check(errs.items.len == 11, "11 errors");
    String m = errs.message();
    check(has(&m, "url: is required; title: must have at most 10 characters; "
                  "status: must be one of read, unread; stars: must be at most 5; "
                  "weight: must be at least 0.5; e-mail: not an email address; "
                  "offset: must be at least -10; address.city: is required; "
                  "address.zip: must have at most 5 characters; "
                  "tags[1].name: must have at most 3 characters; labels: must have at most 2 items"),
          "the message, in field order");
    m.free();
    String j = errs.toJson();
    check(has(&j, "{\"error\":\"invalid request\",\"fields\":[{\"field\":\"url\",\"message\":\"is required\"},"),
          "toJson");
    check(has(&j, "{\"field\":\"tags[1].name\",\"message\":\"must have at most 3 characters\"}"), "toJson nested");
    j.free();

    /* every rule kept; a check replaces the errors */
    b.url = str.from("https://example.com/x");
    b.title.free();
    b.title = str.from("Ünï");   /* 3 characters, 5 bytes */
    b.status = "";               /* empty, not @required: passes */
    b.stars = 0;
    b.weight = 0.5;
    b.email = "ann@example.com";
    b.offset = -10;
    b.address.city = "Iasi";
    b.address.zip = "";
    ts[1].name = "x";
    b.labels = ls[0:2];
    check(validate.check(reflect.of(b), &errs), "valid");
    check(errs.items.len == 0, "no errors left");
    b.title.free();
    b.title = str.from("Ü");
    check(!validate.check(reflect.of(b), &errs) && errs.items.len == 1, "1 character");
    m = errs.message();
    check(has(&m, "title: must have at least 2 characters"), "at least 2");
    m.free();

    /* url and email edges */
    check(validate.isUrl("http://a.b/c?d=1"), "a URL");
    check(!validate.isUrl("example.com"), "no scheme");
    check(!validate.isUrl("mailto:ann@example.com"), "no host");
    check(validate.isEmail("a.b+c@sub.example.org"), "an email");
    check(!validate.isEmail("a@b@c.d") && !validate.isEmail("@b.c") && !validate.isEmail("a@.b") &&
          !validate.isEmail("a b@c.d") && !validate.isEmail("a@b..c"), "not emails");

    /* pointers, bools, errs.add */
    Plain p;
    check(!validate.check(reflect.of(p), &errs) && errs.items.len == 2, "pointer and bool required");
    m = errs.message();
    check(has(&m, "owner: is required; agreed: is required"), "plain message");
    m.free();
    Address a;
    p.owner = &a;
    p.agreed = true;
    p.note = "long";
    check(!validate.check(reflect.of(p), &errs), "into the pointer");
    errs.add("note", "taken by %s", "ann");
    m = errs.message();
    check(has(&m, "owner.city: is required; note: must have at most 3 characters; note: taken by ann"),
          "pointer path and add");
    m.free();
    errs.free();

    /* a rule on a field it can't check */
    Wrong wr;
    bool threw = false;
    try {
        validate.check(reflect.of(wr), &errs);
    } catch (Error e) {
        threw = e.code == validate.ERR_VALIDATE &&
                str.find(cstr.view(e.message), "validate: @email on n, a i64") >= 0;
    }
    check(threw, "ERR_VALIDATE");
    errs.free();
    b.url.free();
    b.title.free();

    /* http.decodeValid through a Mux, as the server answers */
    http.Mux mux;
    mux.handle("POST /bookmarks", &createBookmark);
    mux.handle("POST /boom", &boom);
    post(&mux, "/bookmarks", "{\"url\":\"https://ktm.dev\",\"title\":\"ktm\",\"weight\":1,\"address\":{\"city\":\"Iasi\"}}");
    check(gCode == 201 && str.equals(gBody[0:], "ktm"), "201");
    post(&mux, "/bookmarks", "{\"url\":\"nope\",\"title\":\"ktm\",\"weight\":1,\"address\":{\"city\":\"Iasi\"}}");
    check(gCode == 422 && str.equals(gBody[0:],
          "{\"error\":\"invalid request\",\"fields\":[{\"field\":\"url\",\"message\":\"not a URL\"}]}"), "422");
    post(&mux, "/bookmarks", "{\"url\":");
    check(gCode == 400, "400 on bad JSON");
    post(&mux, "/boom", "");
    check(gCode == 500, "a throw is a 500");
    gBody.free();
    mux.free();

    /* the attributes' checks */
    u8 *bad[] = {
        "struct S { @min i64 a; };",                 "`@min` takes a number: @min(1)",
        "struct S { @max(\"3\") i64 a; };",          "`@max(...)` takes a number: @max(1)",
        "struct S { @oneOf u8[] a; };",              "`@oneOf` takes the allowed values",
        "struct S { @oneOf(\"a|b\") u8[] a; };",     "each value is a non-empty string without '|'",
        "struct S { @oneOf(\"a\", 1) u8[] a; };",    "`@oneOf(...)` takes strings",
        "struct S { @email(\"x\") u8[] a; };",       "`@email` takes no arguments",
        "struct S { @url @url u8[] a; };",           "`@url` is here twice",
        "struct S { @pattern(\"x\") u8[] a; };",   "@min, @max, @email, @url and @oneOf",
    };
    for (i64 i = 0; i < 16; i += 2) {
        String src = str.format("%s\nvoid main() { S s; }\n", bad[i]);
        fs.writeAll("__816_bad.ktm", src[0:]);
        src.free();
        String out = proc.shellOutput("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit __816_bad.ktm 2>&1");
        check(has(&out, cstr.view(bad[i + 1])), bad[i]);
        out.free();
    }
    fs.remove("__816_bad.ktm");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
