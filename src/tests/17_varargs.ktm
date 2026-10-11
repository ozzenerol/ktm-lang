import cstr, test;

i64 add(...args) {
    i64 acc = 0, arg;
    for (i64 i = 0; i < args.len; ++i) {
        arg = (i64)args[i];
        acc += args[i];
    }
    return acc;
}

i64 arg2(i64 i1, ...args) {
    i64 arg,acc = 0;
    for (i64 i = 0; i < args.len; ++i) {
        arg = (i64)args[i];
        acc += arg;
    }
    return i1+acc;
}

i64 arg3(i64 i1, i64 i2, ...args) {
    i64 arg,acc;
    acc = 0;
    for (i64 i = 0; i < args.len; ++i) {
        arg = (i64)args[i];
        acc += args[i];
    }
    return i1+i2+acc;
}

f64 fAdd(f64 something, ...args) {
    f64 arg,acc;
    acc = 0.0;
    for (i64 i = 0; i < args.len; ++i) {
        arg = (f64)args[i];
        acc += arg;
    }
    return acc+something;
}

u8 buf[512];
u8 *concat(...args) {
    u8 *arg;
    i64 len;
    len = 0;
    for (i64 i = 0; i < args.len; ++i) {
        arg = (u8 *)args[i];
        for (u8 *ptr = arg;*ptr;++ptr) {
            buf[len++] = *ptr;
        }
        buf[len++] = ' ';
        buf[len] = '\0';
    }
    return buf;
}

i64 addS(i64 i1, i64 i2, i64 i3, i64 i4, i64 i5, i64 i6) {
    return i1+i2+i3+i4+i5+i6;
}

i64 wrap(i64 i)
{// simulate a nested function call
    return i;
}

i32 main() {
    printf("Test - Variable arguments: ");
    i64 res, correct=0, tests = 15;
    if (!cstr.cmpLen("foo bar baz", concat("foo", "bar", "baz"), 11)) {
        correct++;
    }

    if (fAdd(2.08,4.0,6.82) == 12.9) { correct++; }
    if (arg2(5,6,7,1) == 19) { correct++; }
    // needs to work both in a function call and variable assignment
    res = arg3(5,6,7,1); 
    if (res == 19) { correct++; }

    if (addS(2,2,2,4,4,0) == 14) { correct++; }
    res = addS(2,2,2,4,4,0);
    if (res == 14) { correct++; }

    /* assign and check */
    res = add(2);
    if (res == 2) { correct++; }

    res = add(2,2);
    if (res == 4) { correct++; }

    res = add(2,2,2);
    if (res == 6) { correct++; }

    res = add(2,2,2,4);
    if (res == 10) { correct++; }

    res = add(2,2,2,4,4);
    if (res == 14) { correct++; }

    res = add(2,2,2,4,4,4);
    if (res == 18) { correct++; }

    res = add(2,2,2,4,4,4,3);
    if (res == 21) { correct++; }

    res = wrap(add(2,2,2,4,4,4,4,3,3));
    if (res == 28) { correct++; }

    res = wrap(wrap(add(2,2,2,4,4,4,4,3,3,5)));
    if (res == 33) { correct++; }

    test.printResult(correct,tests);
    printf("=====\n");
    return 0;
}
