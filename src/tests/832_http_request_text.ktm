import mem, net.http, str, test;

/* net.http (v0.21.1): r.setText keeps a copy of a text for the rest of the
 * request, so middleware can hand a handler a user id it read into a buffer
 * it frees at once; getText with and without a default, replacing, and has. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

/* auth: "Bearer ada" -> user id "01a1-ada", read into memory freed here */
bool auth(http.Writer *w, http.Request *r) {
    if (str.equals(r.header("Authorization"), "Bearer ada")) {
        String id = str.from("01a1-ada");
        r.setText("userId", id[0:]);
        mem.set(id.ptr, 'x', id.len);
        id.free();
    }
    return true;
}

void me(http.Writer *w, http.Request *r) {
    u8[] id = r.getText("userId");
    if (!id.len) {
        w.error(401, "who are you?");
        return;
    }
    w.text(200, "%.*s %d", id.len, id.ptr, r.has("userId"));
}

i32 main() {
    printf("Test - http request text: ");
    http.Mux mux;
    mux.use(&auth);
    mux.handle("GET /me", &me);

    http.Request r = http.testRequest("GET", "/me");
    r.addHeader("Authorization", "Bearer ada");
    http.Writer w = http.recorder();
    mux.serve(&w, &r);
    check(w.code == 200 && str.equals(w.body[0:], "01a1-ada 1"), "the handler gets a copy of the text, not the freed buffer");
    w.free();
    r.free();

    r = http.testRequest("GET", "/me");
    w = http.recorder();
    mux.serve(&w, &r);
    check(w.code == 401, "no Authorization: no text");
    check(r.getText("userId").len == 0 && str.equals(r.getText("userId", "nobody"), "nobody"), "getText when unset: \"\" or the default");
    r.setText("k", "first");
    r.setText("k", "second");
    check(str.equals(r.getText("k"), "second") && r.nvalues == 1, "a setText replaces the key's text");
    r.setText("empty", "");
    check(r.has("empty") && r.getText("empty", "def").len == 0, "an empty text is kept, and is not the default");
    check(r.getText("k").ptr[r.getText("k").len] == 0, "the copy is NUL-terminated, for C");
    w.free();
    r.free();
    mux.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
