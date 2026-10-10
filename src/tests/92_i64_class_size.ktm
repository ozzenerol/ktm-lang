#include "testhelper.ktm"

/* An intrinsic class (`I64 class CDate { U32 time; I32 date; };`) is a
 * value of its base integer type whose fields overlay its bytes.
 * sizeof(CDate) was 16 (every intrinsic class was), so arrays
 * of them had a 16-byte stride and classes holding one were padded. It is
 * now the base type's size: I64 class -> 8, I32 class -> 4, U16 -> 2... */

U16 class P16 { U8 lo; U8 hi; };
I16 class S16 { U8 lo; I8 hi; };
I32 class S32 { U16 lo; I16 hi; };
U8 class B8 { U8 v; };

class Holder { I32 a; CDate d; I32 b; };
class Q { I32 a; I32 b; };
class Packed { U8 pre; P16 p; U8 mid; S32 s; U8 post; S16 n; I64 tail; };

CDate MkDate(I32 date, U32 time)
{
  CDate c;
  c.date = date;
  c.time = time;
  return c;
}

I64 DateSum(CDate c, I64 k) { return c.date * 10 + c.time + k; }

P16 MkP(U8 lo, U8 hi)
{
  P16 p;
  p.lo = lo;
  p.hi = hi;
  return p;
}

I64 UseNarrow(P16 p, S16 n) { return p.hi * 1000 + p.lo * 10 + n.hi; }

CDate g_dates[3];
S32 g_s32;

I32 Main()
{
  I64 correct = 0, tests = 19, i;

  "Test - sizeof of an intrinsic class is its base type's size: ";

  if (sizeof(CDate) == 8) correct++;
  if (sizeof(P16) == 2 && sizeof(S16) == 2 && sizeof(S32) == 4 &&
      sizeof(B8) == 1) correct++;
  if (sizeof(Holder) == 24) correct++;

  /* Arrays: 8-byte stride, elements don't overlap. */
  CDate arr[4];
  for (i = 0; i < 4; i++) {
    arr[i] = MkDate(i + 100, i * 7);
  }
  if ((&arr[1])(U8 *) - (&arr[0])(U8 *) == 8) correct++;
  I64 ok = 1;
  for (i = 0; i < 4; i++) {
    if (arr[i].date != i + 100 || arr[i].time != i * 7) ok = 0;
  }
  if (ok) correct++;

  /* Same shape with an ordinary 8-byte class: a 4-byte field of an
   * element with an 8-byte stride (on arm64 the index can't be folded
   * into the load as `lsl #3`). */
  Q qs[4];
  I64 qsum = 0;
  for (i = 0; i < 4; i++) {
    qs[i].a = i;
    qs[i].b = -i * 10;
  }
  for (i = 0; i < 4; i++) qsum += qs[i].b * 100 + qs[i].a;
  if (qsum == -5994) correct++;

  /* The fields overlay the I64: time is the low half, date the high. */
  CDate c = MkDate(7, 8);
  if (c(I64) == 0x700000008) correct++;
  c = MkDate(-5, 1234);
  if (c.date == -5 && c.time == 1234) correct++;

  /* A CDate inside another class, passed by value. */
  Holder h;
  h.a = 11;
  h.b = 22;
  h.d = MkDate(-3, 99);
  if ((&h.d)(U8 *) - (&h)(U8 *) == 8 &&
      (&h.b)(U8 *) - (&h)(U8 *) == 16) correct++;
  if (h.a == 11 && h.b == 22 && h.d.date == -3 && h.d.time == 99) correct++;
  if (DateSum(h.d, 5) == -30 + 99 + 5) correct++;
  Holder h2 = h;
  Holder *hp = &h2;
  if (hp->d.date == -3 && hp->d.time == 99 && hp->b == 22) correct++;

  /* Global array. */
  g_dates[2] = MkDate(42, 43);
  if (g_dates[2].date == 42 && g_dates[2].time == 43 &&
      g_dates[1].date == 0 && g_dates[1].time == 0) correct++;

  /* Narrow intrinsic classes: stores don't clobber the neighbours and
   * reads widen per the base type's signedness. */
  Packed b;
  b.pre = 0xAA;
  b.mid = 0xBB;
  b.post = 0xCC;
  b.tail = 0x1122334455667788;
  b.p = MkP(3, 4);
  b.s.lo = 0xFFFF;
  b.s.hi = -2;
  b.n.lo = 5;
  b.n.hi = -3;
  if (b.pre == 0xAA && b.mid == 0xBB && b.post == 0xCC &&
      b.tail == 0x1122334455667788) correct++;
  P16 q = b.p;
  S16 n = b.n;
  S32 s = b.s;
  if (q(I64) == 0x0403 && n(I64) == -763 && s(I64) == -65537) correct++;
  if (UseNarrow(q, n) == 4000 + 30 - 3) correct++;
  g_s32 = s;
  if (g_s32.lo == 0xFFFF && g_s32.hi == -2 && g_s32(I64) == -65537) correct++;
  B8 bb;
  bb.v = 200;
  if (bb(I64) == 200) correct++;

  /* The stdlib (built by ktm with the same layout) agrees. */
  CDateStruct ds;
  CDateStruct out;
  MemSet(&ds, 0, sizeof(CDateStruct));
  ds.year = 2024;
  ds.mon = 2;
  ds.day_of_mon = 29;
  ds.hour = 13;
  ds.min = 14;
  ds.sec = 15;
  CDate d = Struct2Date(&ds);
  Date2Struct(&out, d);
  if (out.year == 2024 && out.mon == 2 && out.day_of_mon == 29 &&
      out.hour == 13 && out.min == 14 && out.sec == 15 &&
      !StrCmp(MPrintDate(d), "02/29/2024") &&
      !StrCmp(MPrintTime(d), "13:14:15")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
