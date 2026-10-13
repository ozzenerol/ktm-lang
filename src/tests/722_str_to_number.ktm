import str, test;

/* str.toInt / toUint / toFloat (v0.18.0): the whole u8[] is the number
 * (no spaces, nothing after it), else false and *out untouched; 0x and 0b
 * prefixes; overflow is false; a String works too, and as a method. */

i32 main()
{
    printf("Test - str number parsing: ");
    i64 correct = 0, tests = 0;
    i64 n = 99;
    u64 u = 99;
    f64 f = 9.5;

    tests++; if (str.toInt("42", &n) && n == 42) { correct++; }
    tests++; if (str.toInt("-17", &n) && n == -17) { correct++; }
    tests++; if (str.toInt("+8", &n) && n == 8) { correct++; }
    tests++; if (str.toInt("0x1F", &n) && n == 31) { correct++; }
    tests++; if (str.toInt("-0b101", &n) && n == -5) { correct++; }
    tests++; if (str.toInt("9223372036854775807", &n) && n == 9223372036854775807) { correct++; }
    tests++; if (str.toInt("-9223372036854775808", &n) && n < 0 && n - 1 > 0) { correct++; }
    n = 7;
    tests++; if (!str.toInt("9223372036854775808", &n) && n == 7) { correct++; }
    tests++; if (!str.toInt("", &n) && !str.toInt("-", &n) && !str.toInt(" 1", &n) &&
                 !str.toInt("1 ", &n) && !str.toInt("12a", &n) && !str.toInt("0x", &n) && n == 7) { correct++; }
    tests++; if (str.toUint("18446744073709551615", &u) && u == 0xFFFFFFFFFFFFFFFF) { correct++; }
    tests++; if (!str.toUint("18446744073709551616", &u) && !str.toUint("-1", &u)) { correct++; }

    u8[] line = "port=8080;";
    tests++; if (str.toInt(line[5:9], &n) && n == 8080) { correct++; }
    String s = str.from("123");
    tests++; if (s.toInt(&n) && n == 123) { correct++; }
    s.free();

    tests++; if (str.toFloat("1.5", &f) && f == 1.5) { correct++; }
    tests++; if (str.toFloat("-2e3", &f) && f == -2000.0) { correct++; }
    tests++; if (str.toFloat(".25", &f) && f == 0.25) { correct++; }
    f = 3.0;
    tests++; if (!str.toFloat("1.5x", &f) && !str.toFloat("", &f) && !str.toFloat(" 1", &f) && f == 3.0) { correct++; }
    u8[] g = "x2.75y";
    tests++; if (str.toFloat(g[1:5], &f) && f == 2.75) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
