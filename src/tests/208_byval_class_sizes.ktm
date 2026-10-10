#include "testhelper.ktm"

/* By-value classes of every size from 1 to 16 bytes, and two with float
 * fields (12 bytes of F32; an F64 then 3 bytes), passed to and
 * returned from functions. AAPCS64 passes such a class in one or two x
 * registers; the arm64 backends (compiled and JIT) only moved 1/2/4/8
 * byte pieces and failed to compile a 3, 5, 6, 7, 9..15 byte one
 * ("3-byte struct param chunk not supported"). Covers parameters in
 * registers and on the stack (after 7 I64 arguments the 2-register
 * classes no longer fit), returns, and that the neighbours of the copies
 * are left alone. */

class S1 { U8 b[1]; };
class S2 { U8 b[2]; };
class S3 { U8 b[3]; };
class S4 { U8 b[4]; };
class S5 { U8 b[5]; };
class S6 { U8 b[6]; };
class S7 { U8 b[7]; };
class S8 { U8 b[8]; };
class S9 { U8 b[9]; };
class S10 { U8 b[10]; };
class S11 { U8 b[11]; };
class S12 { U8 b[12]; };
class S13 { U8 b[13]; };
class S14 { U8 b[14]; };
class S15 { U8 b[15]; };
class S16 { U8 b[16]; };

S1 Mk1(I64 seed)
{
  S1 s;
  I64 i;
  for (i = 0; i < 1; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk1(seed) made */
I64 Diff1(S1 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 1; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late1(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S1 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff1(s, seed);
}

/* Returns a parameter straight back */
S1 Echo1(S1 s) { return s; }

Bool Check1()
{
  S1 arr[3];
  S1 s = Mk1(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo1(Mk1(40));
  for (i = 0; i < 1; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff1(arr[1], 40);
  bad += Diff1(s, 10);
  bad += Diff1(Mk1(20), 20);
  bad += Late1(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late1(1, 2, 3, 4, 5, 6, 7, Echo1(s), 10);
  if (bad) "size 1: %d bad\n", bad;
  return bad == 0;
}

S2 Mk2(I64 seed)
{
  S2 s;
  I64 i;
  for (i = 0; i < 2; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk2(seed) made */
I64 Diff2(S2 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 2; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late2(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S2 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff2(s, seed);
}

/* Returns a parameter straight back */
S2 Echo2(S2 s) { return s; }

Bool Check2()
{
  S2 arr[3];
  S2 s = Mk2(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo2(Mk2(40));
  for (i = 0; i < 2; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff2(arr[1], 40);
  bad += Diff2(s, 10);
  bad += Diff2(Mk2(20), 20);
  bad += Late2(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late2(1, 2, 3, 4, 5, 6, 7, Echo2(s), 10);
  if (bad) "size 2: %d bad\n", bad;
  return bad == 0;
}

S3 Mk3(I64 seed)
{
  S3 s;
  I64 i;
  for (i = 0; i < 3; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk3(seed) made */
I64 Diff3(S3 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 3; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late3(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S3 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff3(s, seed);
}

/* Returns a parameter straight back */
S3 Echo3(S3 s) { return s; }

Bool Check3()
{
  S3 arr[3];
  S3 s = Mk3(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo3(Mk3(40));
  for (i = 0; i < 3; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff3(arr[1], 40);
  bad += Diff3(s, 10);
  bad += Diff3(Mk3(20), 20);
  bad += Late3(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late3(1, 2, 3, 4, 5, 6, 7, Echo3(s), 10);
  if (bad) "size 3: %d bad\n", bad;
  return bad == 0;
}

S4 Mk4(I64 seed)
{
  S4 s;
  I64 i;
  for (i = 0; i < 4; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk4(seed) made */
I64 Diff4(S4 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 4; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late4(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S4 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff4(s, seed);
}

/* Returns a parameter straight back */
S4 Echo4(S4 s) { return s; }

Bool Check4()
{
  S4 arr[3];
  S4 s = Mk4(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo4(Mk4(40));
  for (i = 0; i < 4; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff4(arr[1], 40);
  bad += Diff4(s, 10);
  bad += Diff4(Mk4(20), 20);
  bad += Late4(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late4(1, 2, 3, 4, 5, 6, 7, Echo4(s), 10);
  if (bad) "size 4: %d bad\n", bad;
  return bad == 0;
}

S5 Mk5(I64 seed)
{
  S5 s;
  I64 i;
  for (i = 0; i < 5; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk5(seed) made */
I64 Diff5(S5 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 5; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late5(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S5 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff5(s, seed);
}

/* Returns a parameter straight back */
S5 Echo5(S5 s) { return s; }

Bool Check5()
{
  S5 arr[3];
  S5 s = Mk5(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo5(Mk5(40));
  for (i = 0; i < 5; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff5(arr[1], 40);
  bad += Diff5(s, 10);
  bad += Diff5(Mk5(20), 20);
  bad += Late5(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late5(1, 2, 3, 4, 5, 6, 7, Echo5(s), 10);
  if (bad) "size 5: %d bad\n", bad;
  return bad == 0;
}

S6 Mk6(I64 seed)
{
  S6 s;
  I64 i;
  for (i = 0; i < 6; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk6(seed) made */
I64 Diff6(S6 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 6; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late6(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S6 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff6(s, seed);
}

/* Returns a parameter straight back */
S6 Echo6(S6 s) { return s; }

Bool Check6()
{
  S6 arr[3];
  S6 s = Mk6(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo6(Mk6(40));
  for (i = 0; i < 6; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff6(arr[1], 40);
  bad += Diff6(s, 10);
  bad += Diff6(Mk6(20), 20);
  bad += Late6(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late6(1, 2, 3, 4, 5, 6, 7, Echo6(s), 10);
  if (bad) "size 6: %d bad\n", bad;
  return bad == 0;
}

S7 Mk7(I64 seed)
{
  S7 s;
  I64 i;
  for (i = 0; i < 7; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk7(seed) made */
I64 Diff7(S7 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 7; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late7(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S7 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff7(s, seed);
}

/* Returns a parameter straight back */
S7 Echo7(S7 s) { return s; }

Bool Check7()
{
  S7 arr[3];
  S7 s = Mk7(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo7(Mk7(40));
  for (i = 0; i < 7; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff7(arr[1], 40);
  bad += Diff7(s, 10);
  bad += Diff7(Mk7(20), 20);
  bad += Late7(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late7(1, 2, 3, 4, 5, 6, 7, Echo7(s), 10);
  if (bad) "size 7: %d bad\n", bad;
  return bad == 0;
}

S8 Mk8(I64 seed)
{
  S8 s;
  I64 i;
  for (i = 0; i < 8; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk8(seed) made */
I64 Diff8(S8 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 8; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late8(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S8 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff8(s, seed);
}

/* Returns a parameter straight back */
S8 Echo8(S8 s) { return s; }

Bool Check8()
{
  S8 arr[3];
  S8 s = Mk8(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo8(Mk8(40));
  for (i = 0; i < 8; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff8(arr[1], 40);
  bad += Diff8(s, 10);
  bad += Diff8(Mk8(20), 20);
  bad += Late8(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late8(1, 2, 3, 4, 5, 6, 7, Echo8(s), 10);
  if (bad) "size 8: %d bad\n", bad;
  return bad == 0;
}

S9 Mk9(I64 seed)
{
  S9 s;
  I64 i;
  for (i = 0; i < 9; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk9(seed) made */
I64 Diff9(S9 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 9; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late9(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S9 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff9(s, seed);
}

/* Returns a parameter straight back */
S9 Echo9(S9 s) { return s; }

Bool Check9()
{
  S9 arr[3];
  S9 s = Mk9(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo9(Mk9(40));
  for (i = 0; i < 9; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff9(arr[1], 40);
  bad += Diff9(s, 10);
  bad += Diff9(Mk9(20), 20);
  bad += Late9(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late9(1, 2, 3, 4, 5, 6, 7, Echo9(s), 10);
  if (bad) "size 9: %d bad\n", bad;
  return bad == 0;
}

S10 Mk10(I64 seed)
{
  S10 s;
  I64 i;
  for (i = 0; i < 10; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk10(seed) made */
I64 Diff10(S10 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 10; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late10(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S10 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff10(s, seed);
}

/* Returns a parameter straight back */
S10 Echo10(S10 s) { return s; }

Bool Check10()
{
  S10 arr[3];
  S10 s = Mk10(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo10(Mk10(40));
  for (i = 0; i < 10; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff10(arr[1], 40);
  bad += Diff10(s, 10);
  bad += Diff10(Mk10(20), 20);
  bad += Late10(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late10(1, 2, 3, 4, 5, 6, 7, Echo10(s), 10);
  if (bad) "size 10: %d bad\n", bad;
  return bad == 0;
}

S11 Mk11(I64 seed)
{
  S11 s;
  I64 i;
  for (i = 0; i < 11; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk11(seed) made */
I64 Diff11(S11 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 11; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late11(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S11 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff11(s, seed);
}

/* Returns a parameter straight back */
S11 Echo11(S11 s) { return s; }

Bool Check11()
{
  S11 arr[3];
  S11 s = Mk11(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo11(Mk11(40));
  for (i = 0; i < 11; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff11(arr[1], 40);
  bad += Diff11(s, 10);
  bad += Diff11(Mk11(20), 20);
  bad += Late11(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late11(1, 2, 3, 4, 5, 6, 7, Echo11(s), 10);
  if (bad) "size 11: %d bad\n", bad;
  return bad == 0;
}

S12 Mk12(I64 seed)
{
  S12 s;
  I64 i;
  for (i = 0; i < 12; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk12(seed) made */
I64 Diff12(S12 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 12; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late12(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S12 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff12(s, seed);
}

/* Returns a parameter straight back */
S12 Echo12(S12 s) { return s; }

Bool Check12()
{
  S12 arr[3];
  S12 s = Mk12(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo12(Mk12(40));
  for (i = 0; i < 12; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff12(arr[1], 40);
  bad += Diff12(s, 10);
  bad += Diff12(Mk12(20), 20);
  bad += Late12(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late12(1, 2, 3, 4, 5, 6, 7, Echo12(s), 10);
  if (bad) "size 12: %d bad\n", bad;
  return bad == 0;
}

S13 Mk13(I64 seed)
{
  S13 s;
  I64 i;
  for (i = 0; i < 13; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk13(seed) made */
I64 Diff13(S13 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 13; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late13(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S13 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff13(s, seed);
}

/* Returns a parameter straight back */
S13 Echo13(S13 s) { return s; }

Bool Check13()
{
  S13 arr[3];
  S13 s = Mk13(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo13(Mk13(40));
  for (i = 0; i < 13; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff13(arr[1], 40);
  bad += Diff13(s, 10);
  bad += Diff13(Mk13(20), 20);
  bad += Late13(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late13(1, 2, 3, 4, 5, 6, 7, Echo13(s), 10);
  if (bad) "size 13: %d bad\n", bad;
  return bad == 0;
}

S14 Mk14(I64 seed)
{
  S14 s;
  I64 i;
  for (i = 0; i < 14; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk14(seed) made */
I64 Diff14(S14 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 14; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late14(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S14 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff14(s, seed);
}

/* Returns a parameter straight back */
S14 Echo14(S14 s) { return s; }

Bool Check14()
{
  S14 arr[3];
  S14 s = Mk14(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo14(Mk14(40));
  for (i = 0; i < 14; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff14(arr[1], 40);
  bad += Diff14(s, 10);
  bad += Diff14(Mk14(20), 20);
  bad += Late14(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late14(1, 2, 3, 4, 5, 6, 7, Echo14(s), 10);
  if (bad) "size 14: %d bad\n", bad;
  return bad == 0;
}

S15 Mk15(I64 seed)
{
  S15 s;
  I64 i;
  for (i = 0; i < 15; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk15(seed) made */
I64 Diff15(S15 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 15; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late15(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S15 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff15(s, seed);
}

/* Returns a parameter straight back */
S15 Echo15(S15 s) { return s; }

Bool Check15()
{
  S15 arr[3];
  S15 s = Mk15(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo15(Mk15(40));
  for (i = 0; i < 15; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff15(arr[1], 40);
  bad += Diff15(s, 10);
  bad += Diff15(Mk15(20), 20);
  bad += Late15(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late15(1, 2, 3, 4, 5, 6, 7, Echo15(s), 10);
  if (bad) "size 15: %d bad\n", bad;
  return bad == 0;
}

S16 Mk16(I64 seed)
{
  S16 s;
  I64 i;
  for (i = 0; i < 16; i++) s.b[i] = seed + i * 3;
  return s;
}

/* 0 when `s` holds what Mk16(seed) made */
I64 Diff16(S16 s, I64 seed)
{
  I64 i, bad = 0;
  for (i = 0; i < 16; i++) if (s.b[i] != (seed + i * 3) & 0xFF) bad++;
  return bad;
}

/* The same, with the class past the argument registers */
I64 Late16(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, I64 g, S16 s, I64 seed)
{
  if (a + b + c + d + e + f + g != 28) return 100;
  return Diff16(s, seed);
}

/* Returns a parameter straight back */
S16 Echo16(S16 s) { return s; }

Bool Check16()
{
  S16 arr[3];
  S16 s = Mk16(10);
  I64 i, bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = Echo16(Mk16(40));
  for (i = 0; i < 16; i++) {
    if (arr[0].b[i] != 0xEE || arr[2].b[i] != 0xEE) bad++;
  }
  bad += Diff16(arr[1], 40);
  bad += Diff16(s, 10);
  bad += Diff16(Mk16(20), 20);
  bad += Late16(1, 2, 3, 4, 5, 6, 7, s, 10);
  bad += Late16(1, 2, 3, 4, 5, 6, 7, Echo16(s), 10);
  if (bad) "size 16: %d bad\n", bad;
  return bad == 0;
}

class F3 { F32 x, y, z; };          /* HFA on arm64, two SSE eightbytes */
class D3 { F64 d; U8 b[3]; };       /* SSE then a 3-byte INTEGER part */

F3 MkF3(I64 seed)
{
  F3 s;
  s.x = seed; s.y = seed + 0.5; s.z = seed + 1.25;
  return s;
}

I64 DiffF3(F3 s, I64 seed)
{
  return (s.x != seed) + (s.y != seed + 0.5) + (s.z != seed + 1.25);
}

D3 MkD3(I64 seed)
{
  D3 s;
  s.d = seed + 0.75;
  s.b[0] = seed; s.b[1] = seed + 1; s.b[2] = seed + 2;
  return s;
}

I64 DiffD3(D3 s, I64 seed)
{
  return (s.d != seed + 0.75) + (s.b[0] != seed) + (s.b[1] != seed + 1) +
         (s.b[2] != seed + 2);
}

I64 LateD3(I64 a, I64 b, I64 c, I64 d, I64 e, I64 f, D3 s, I64 seed)
{
  if (a + b + c + d + e + f != 21) return 100;
  return DiffD3(s, seed);
}

D3 EchoD3(D3 s) { return s; }

Bool CheckFloats()
{
  D3 arr[3];
  D3 s = MkD3(10);
  F3 f = MkF3(7);
  I64 bad = 0;
  MemSet(arr, 0xEE, sizeof(arr));
  arr[1] = EchoD3(MkD3(40));
  if (arr[0].b[2] != 0xEE || arr[2].b[0] != 0xEE) bad++;
  bad += DiffD3(arr[1], 40);
  bad += DiffD3(s, 10);
  bad += LateD3(1, 2, 3, 4, 5, 6, s, 10);
  bad += DiffF3(f, 7);
  bad += DiffF3(MkF3(9), 9);
  if (bad) "floats: %d bad\n", bad;
  return bad == 0;
}

I32 Main()
{
  "Test - by-value classes of 1 to 16 bytes: ";
  I64 tests = 0;
  I64 correct = 0;

  tests++;
  if (Check1()) correct++;
  tests++;
  if (Check2()) correct++;
  tests++;
  if (Check3()) correct++;
  tests++;
  if (Check4()) correct++;
  tests++;
  if (Check5()) correct++;
  tests++;
  if (Check6()) correct++;
  tests++;
  if (Check7()) correct++;
  tests++;
  if (Check8()) correct++;
  tests++;
  if (Check9()) correct++;
  tests++;
  if (Check10()) correct++;
  tests++;
  if (Check11()) correct++;
  tests++;
  if (Check12()) correct++;
  tests++;
  if (Check13()) correct++;
  tests++;
  if (Check14()) correct++;
  tests++;
  if (Check15()) correct++;
  tests++;
  if (Check16()) correct++;
  tests++;
  if (CheckFloats()) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
