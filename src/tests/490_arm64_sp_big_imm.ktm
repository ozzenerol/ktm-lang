import test;

/* Two arm64 encoder traps. ADD/SUB (immediate) only reaches a 12-bit value
 * (optionally shifted by 12), and the encoder kept the low 12 bits of
 * anything else: a call passing two 5000-byte structs by value reserved
 * 5024 & 0xFFF bytes of stack for their copies (JIT), so the copies
 * overwrote the caller's frame. And register 31 is SP in the immediate
 * and extended-register forms but XZR in the shifted-register one, so
 * `add x0, sp, x1` in an asm block added x1 to zero. */

struct Big {
    u8 b[5000];
};

i64 sumBig(Big a, Big b) {
    i64 s = 0;
    for (i64 i = 0; i < 5000; i++) { s += a.b[i] + 2 * b.b[i]; }
    return s;
}

#ifdef IS_ARM_64
asm {
_T490_SP_PLUS::
        add     x0, sp, x0
        ret
_T490_SP_MOV::
        mov     x0, sp
        ret
_T490_SP_SUB::
        mov     x9, sp
        sub     sp, sp, x0
        mov     x10, sp
        add     sp, sp, x0
        sub     x0, x9, x10
        ret
}
pub _extern _T490_SP_PLUS i64 spPlus(i64 n);
pub _extern _T490_SP_MOV i64 spMov();
pub _extern _T490_SP_SUB i64 spSub(i64 n);
#endif

i32 main() {
    printf("Test - arm64 sp operands and big immediates: ");
    i64 tests = 0;
    i64 correct = 0;

    Big a, b;
    i64 want = 0;
    for (i64 i = 0; i < 5000; i++) {
        a.b[i] = i & 0x7f;
        b.b[i] = (i * 7) & 0x3f;
        want += a.b[i] + 2 * b.b[i];
    }
    i64 guard = 0x1234567;
    tests++;
    i64 got = sumBig(a, b);
    if (got == want && guard == 0x1234567 && a.b[4999] == (4999 & 0x7f)) { correct++; }
    else { printf("\n  by value: %d want %d, guard %x\n", got, want, guard); }

#ifdef IS_ARM_64
    tests++;
    i64 sp = spMov();
    i64 sp8 = spPlus(8);
    if (sp != 0 && sp8 == sp + 8) { correct++; }
    else { printf("\n  add x0, sp, x0: %x, sp %x\n", sp8, sp); }
    tests++;
    i64 d = spSub(32);
    if (d == 32) { correct++; }
    else { printf("\n  sub sp, sp, x0 moved sp by %d\n", d); }
#endif

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
