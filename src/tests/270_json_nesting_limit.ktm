import cstr, json, mem, test;

/* Deeply nested JSON (80000 `[`) overflowed the stack: the parser,
 * json.toStr and json.release recurse once per level. Arrays and
 * objects may now nest JSON_MAX_DEPTH (512) deep; deeper input is the
 * error JSON_NESTING_TOO_DEEP. */

/* `n` levels of arrays, or of objects {"a": ...} when `obj` */
u8 *nest(i64 n, bool obj) {
    u8 *s = mem.alloc(6 * n + 2), *p = s;
    i64 i;
    for (i = 0; i < n; i++) {
        if (obj) { mem.copy(p, "{\"a\":", 5); p += 5; }
        else { *p++ = '['; }
    }
    *p++ = '1';
    u8 close = ']';
    if (obj) { close = '}'; }
    for (i = 0; i < n; i++) { *p++ = close; }
    *p = '\0';
    return s;
}

i64 parseErr(i64 n, bool obj)
{ // the error (JSON_OK on success), after a json.toStr if it parsed
    u8 *s = nest(n, obj);
    json.Value *j = json.parse(s);
    i64 err = j.state.error;
    if (json.ok(j)) {
        u8 *o = json.toStr(j);
        if (cstr.len(o) < n) { err = -1; }
        mem.free(o);
    }
    json.release(j);
    mem.free(s);
    return err;
}

i32 main() {
    printf("Test - JSON nesting limit: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (json.MAX_DEPTH == 512 && parseErr(1, false) == json.OK &&
            parseErr(512, false) == json.OK && parseErr(512, true) == json.OK) {
        correct++;
            }
    tests++;
    if (parseErr(513, false) == json.NESTING_TOO_DEEP &&
            parseErr(513, true) == json.NESTING_TOO_DEEP) {
        correct++;
            }
    /* Far deeper input is an error, not a crash */
    tests++;
    if (parseErr(80000, false) == json.NESTING_TOO_DEEP &&
            parseErr(80000, true) == json.NESTING_TOO_DEEP &&
            parseErr(300000, false) == json.NESTING_TOO_DEEP) {
        correct++;
            }

    /* Siblings don't add up: only nesting counts */
    tests++;
    u8 *wide = mem.alloc(4 * 2000 + 3), *p = wide;
    *p++ = '[';
    for (i64 i = 0; i < 2000; i++) { mem.copy(p, "[1],", 4); p += 4; }
    p[-1] = ']';
    *p = '\0';
    json.Value *j = json.parse(wide);
    if (json.ok(j)) { correct++; }
    json.release(j);
    mem.free(wide);

    /* The message */
    tests++;
    u8 *s = nest(600, false);
    j = json.parse(s);
    u8 *e = json.errorStr(j);
    if (e && !cstr.cmpLen(e, "Arrays and objects nested more than 512 deep", 44)) { correct++; }
    else { printf("message: %s\n", e); }
    mem.free(e);
    json.release(j);
    mem.free(s);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
