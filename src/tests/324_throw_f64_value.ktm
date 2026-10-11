import test;

/* throw(42.75) passed the f64 in a float register, so KTM_Throw read
 * garbage from its integer argument register. The value is now
 * converted like an argument to a u64 parameter. */

u64 asArg(u64 v) {
    return v;
}

u64 caught(f64 f) {
    try {
        throw(f);
    } catch (Error e) {
        return e.code;
    }
    return 0;
}

void throwF32(f32 g) {
    throw(g);
}

i32 main() {
    printf("Test - throw converts its value to u64: ");
    i64 correct = 0, tests = 14;
    f64 f = 42.75;
    f32 g = 7.5;
    f64 big = 3e18;
    i32 vI32 = -5;
    i8 vI8 = -2;
    u8 vU8 = 200;
    u16 vU16 = 65535;
    u32 vU32 = 0xFFFFFFFF;
    u8 *p = "abc";
    i64 r = 7;

    try { throw(f); } catch (Error e) { if (e.code == 42) { correct++; } }
    try { throw(f); } catch (Error e) { if (e.code == asArg(f)) { correct++; } }
    try { throw(f * 2); } catch (Error e) { if (e.code == 85) { correct++; } }
    try { throw(r + 0.5); } catch (Error e) { if (e.code == 7) { correct++; } }
    try { throw(1.99); } catch (Error e) { if (e.code == 1) { correct++; } }
    try { throw(big); } catch (Error e) { if (e.code == 3000000000000000000) { correct++; } }
    try { throwF32(g); } catch (Error e) { if (e.code == 7) { correct++; } }
    if (caught(-2.5) == asArg(-2.5)) { correct++; }

    try { throw(vI32); } catch (Error e) { if (e.code == asArg(vI32) && (i64)e.code == -5) { correct++; } }
    try { throw(vI8); } catch (Error e) { if ((i64)e.code == -2) { correct++; } }
    try { throw(vU8); } catch (Error e) { if (e.code == 200) { correct++; } }
    try { throw(vU16); } catch (Error e) { if (e.code == 65535) { correct++; } }
    try { throw(vU32); } catch (Error e) { if (e.code == 0xFFFFFFFF) { correct++; } }
    try { throw(p); } catch (Error e) { if ((u8 *)e.code == p) { correct++; } }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
