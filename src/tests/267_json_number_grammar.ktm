import cstr, json, mem, test;

/* JSON numbers follow RFC 8259 now: -?(0|[1-9][0-9]*)(.[0-9]+)?
 * ([eE][+-]?[0-9]+)?. `-`, `+`, `.` gave -3, -5 and -2; `01 +1 .5 1. 0x1F
 * 1e 1e2.5 -abc --1` gave 0 and `1a 1-2` gave 1, also with
 * JSON_STRNUM_FLAG, which wrote the bad text back out. An integer outside
 * i64 saturated silently; it is now a JSON_FLOAT, as in JavaScript (with
 * STRNUM the text is kept). 1e400 is inf, and json.toStr writes inf and
 * nan as null. */

bool badFn(u8 *text, i64 flags=0) {
    json.Value *j = json.parse(text, -1, flags);
    bool ok = !json.ok(j) && j.state.error == json.INVALID_NUMBER;
    if (!ok) { printf("[%s] flags %d: error %d\n", text, flags, j.state.error); }
    json.release(j);
    return ok;
}

bool num(u8 *text, i64 type, u8 *out, i64 flags=0)
{ // parses as one value of `type`, written back as `out`
    json.Value *j = json.parse(text, -1, flags);
    bool ok = json.ok(j) && j.type == type;
    if (ok) {
        u8 *s = json.toStr(j);
        ok = !cstr.cmp(s, out);
        if (!ok) { printf("[%s] gave [%s] want [%s]\n", text, s, out); }
        mem.free(s);
    } else {
        printf("[%s] error %d type %d\n", text, j.state.error, j.type);
    }
    json.release(j);
    return ok;
}

i32 main() {
    printf("Test - JSON number grammar: ");
    i64 correct = 0, tests = 0, i;
    u8 *bad[22];
    bad[0] = "-"; bad[1] = "+"; bad[2] = "."; bad[3] = "01"; bad[4] = "+1";
    bad[5] = ".5"; bad[6] = "1."; bad[7] = "0x1F"; bad[8] = "1e"; bad[9] = "1e2.5";
    bad[10] = "-abc"; bad[11] = "--1"; bad[12] = "1a"; bad[13] = "1-2";
    bad[14] = "[1e+]"; bad[15] = "[-01]"; bad[16] = "[1.]"; bad[17] = "{\"a\":-}";
    bad[18] = "[1+2]"; bad[19] = "[1e2e3]"; bad[20] = "[0.e1]"; bad[21] = "[-.5]";

    tests++;
    bool ok = true;
    for (i = 0; i < 22; i++) {
        if (!badFn(bad[i]) || !badFn(bad[i], json.STRNUM_FLAG)) { ok = false; }
    }
    if (ok) { correct++; }

    /* Valid numbers */
    tests++;
    if (num("0", json.INT, "0") && num("-0", json.INT, "0") &&
            num("-1", json.INT, "-1") && num("1.5", json.FLOAT, "1.5") &&
            num("-0.0", json.FLOAT, "-0.0") && num("1e22", json.FLOAT, "1.0e+22") &&
            num("1E+30", json.FLOAT, "1.0e+30") && num("1e-2", json.FLOAT, "0.01") &&
            num("0e0", json.FLOAT, "0.0") && num("[0,-0.5,10]", json.ARRAY, "[0,-0.5,10]") &&
            num("{\"a\":1 , \"b\":2}", json.OBJECT, "{\"a\": 1,\"b\": 2}")) {
        correct++;
            }

    /* i64 limits stay integers, beyond them floats */
    tests++;
    if (num("9223372036854775807", json.INT, "9223372036854775807") &&
            num("-9223372036854775808", json.INT, "-9223372036854775808") &&
            num("9223372036854775808", json.FLOAT, "9.223372036854776e+18") &&
            num("-9223372036854775809", json.FLOAT, "-9.223372036854776e+18") &&
            num("123456789012345678901234567890", json.FLOAT, "1.2345678901234568e+29")) {
        correct++;
            }

    /* With STRNUM the text is kept as written */
    tests++;
    if (num("[123456789012345678901234567890,1e400,-0.0]", json.ARRAY,
                    "[123456789012345678901234567890,1e400,-0.0]", json.STRNUM_FLAG)) {
        correct++;
                    }

    /* inf is written as null */
    tests++;
    json.Value *j = json.parse("[1e400,-1e400]");
    if (json.ok(j) && j.array.type == json.FLOAT && j.array.floatVal > 1e308 &&
            j.array.next.floatVal < -1e308) { correct++; }
    u8 *s = json.toStr(j);
    tests++;
    if (!cstr.cmp(s, "[null,null]")) { correct++; }
    else { printf("[%s]\n", s); }
    mem.free(s);
    json.release(j);

    /* `len` ends the number even with more digits in the buffer */
    tests++;
    j = json.parse("1234567", 3);
    if (json.ok(j) && j.type == json.INT && j.intVal == 123) { correct++; }
    json.release(j);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
