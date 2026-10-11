import cstr, json, libc, mem, test;

/* json.toStr found each float's text by trying %.1g, %.2g, ... up to
 * %.17g until one read back as the same value: up to 17 formats per
 * number (500k floats took over a minute). It now tries %.15g, %.16g and
 * %.17g only (with C's snprintf), and every float still reads back
 * exactly. */

extern "c" f64 strtod(u8 *nptr, u8 **endptr);

bool sameBits(f64 a, f64 b) {
    u64 x, y;
    mem.copy(&x, &a, 8);
    mem.copy(&y, &b, 8);
    return x == y;
}

i32 main() {
    printf("Test - JSON float serialisation: ");
    i64 correct = 0, tests = 0, i;

    f64 vals[14];
    vals[0] = 0.1; vals[1] = 1.0 / 3.0; vals[2] = 5e-324; vals[3] = 1.7976931348623157e308;
    vals[4] = 2.2250738585072014e-308; vals[5] = -0.0; vals[6] = 1e21; vals[7] = 1e-7;
    vals[8] = 123456789.123; vals[9] = 9007199254740993.0; vals[10] = -79977321273640.0;
    vals[11] = 0.5; vals[12] = 100.0; vals[13] = -4.6602437438358e-310;

    /* Each one reads back bit for bit, and is written as a float */
    tests++;
    bool ok = true;
    u8 text[64];
    for (i = 0; i < 14; i++) {
        cstr.formatTo(text, "[%.16e]", vals[i]); /* an exponent, so a float */
        json.Value *j = json.parse(text);
        u8 *s = json.toStr(j);
        json.Value *k = json.parse(s);
        if (!json.ok(k) || k.array.type != json.FLOAT ||
                !sameBits(k.array.floatVal, vals[i])) {
            printf("%.17g gave %s\n", vals[i], s);
            ok = false;
        }
        mem.free(s);
        json.release(k);
        json.release(j);
    }
    if (ok) { correct++; }

    /* Short values stay short */
    tests++;
    json.Value *j = json.parse("[0.1,0.5,100.0,2.5e-3,1e21,-0.0]");
    u8 *s = json.toStr(j);
    if (!cstr.cmp(s, "[0.1,0.5,100.0,0.0025,1.0e+21,-0.0]")) { correct++; }
    else { printf("[%s]\n", s); }
    mem.free(s);
    json.release(j);

    /* Many random bit patterns */
    tests++;
    ok = true;
    u64 seed = 88172645463325252;
    for (i = 0; i < 20000; i++) {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        f64 f;
        mem.copy(&f, &seed, 8);
        if (((seed >> 52) & 0x7FF) == 0x7FF) { continue; } /* inf/nan */
        cstr.formatTo(text, "%.16e", f);
        j = json.parse(text);
        s = json.toStr(j);
        if (!sameBits(libc.strtod(s, null), f)) {
            if (ok) { printf("%s -> %s\n", text, s); }
            ok = false;
        }
        mem.free(s);
        json.release(j);
    }
    if (ok) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
