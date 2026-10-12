import test;

/* What a catch sees after the longjmp from a throw (F9): its own frame
 * (taken again in the catch block, not kept from before the setjmp),
 * locals the try body changed, and the catch chain, also when the body
 * never finishes normally and is full of temporaries. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 gSink;

/* a body that only ends by throwing, with many temporaries */
i64 work(i64 x)
{
    try {
        i64 a = x * 3, b = x + 7, c = a * b, d = c - a, e = d * x, f = e + b, g = f * c, h = g - d;
        i64 i = h * a + b, j = i - c * d, k = j * e - f, l = k + g * h, m = l - i * j, n = m * k + l;
        gSink = a + b + c + d + e + f + g + h + i + j + k + l + m + n;
        gSink += (a * n - b * m + c * l - d * k + e * j - f * i + g * h) * (x + gSink);
        throw(1);
    } catch {
        return 7;
    }
    return 0;
}

i64 outer(i64 x)
{
    try {
        work(x);
        throw(2);
    } catch (Error e) {
        return e.code * 100 + work(x + 1);
    }
    return 0;
}

void boom(i64 at) { if (at > 2) { throw(9); } }

i64 steps(i64 n)
{
    i64 done = 0, sum = 0;
    try {
        for (i64 i = 0; i < n; i++) {
            done++;
            sum += i * 10;
            boom(i);
        }
    } catch {
        return done * 1000 + sum;
    }
    return -1;
}

i64 setThrough(i64 *p) { *p = 5; throw(1); return 0; }

i32 main() {
    printf("Test - try/catch after the longjmp: ");

    i64 total = 0;
    for (i64 i = 0; i < 1000; i++) { total += outer(i); }
    check(total == 207000, "nested tries whose bodies only throw, 1000 times");
    i64 code = 0;
    try { throw(3); } catch (Error e) { code = e.code; }
    check(code == 3, "the catch chain is intact after them");

    check(steps(10) == 4060, "locals a loop in the body changed");
    i64 v = 1, w = 2;
    try {
        w = 3;
        v = setThrough(&w) + 100;
    } catch {}
    check(v == 1 && w == 5, "an assignment the throw cut short, a write through a pointer");
    f64 f = 1.5;
    try {
        f = 2.5;
        throw(1);
    } catch {}
    check(f == 2.5, "a float changed in the body");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
