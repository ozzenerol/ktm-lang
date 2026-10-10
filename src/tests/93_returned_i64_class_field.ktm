#include "testhelper.ktm"

/* Reading a field straight off an intrinsic class value with no storage
 * of its own - `Now().date`, `MkDate(..).time`, `raw(CDate).date` - used
 * the value (which comes back in a register, like an I64) as the address
 * of the class and crashed. */

U16 class P16 { U8 lo; I8 hi; };

CDate MkDate(I32 date, U32 time)
{
  CDate c;
  c.date = date;
  c.time = time;
  return c;
}

P16 MkP(U8 lo, I8 hi)
{
  P16 p;
  p.lo = lo;
  p.hi = hi;
  return p;
}

I64 Twice(I64 x) { return x * 2; }

I32 Main()
{
  I64 correct = 0, tests = 12, i;

  "Test - read a field off a returned I64 class: ";

  if (MkDate(-7, 99).date == -7) correct++;
  if (MkDate(-7, 99).time == 99) correct++;
  /* time is a U32: no sign extension. */
  if (MkDate(1, 0xFFFFFFF0).time == 0xFFFFFFF0) correct++;
  if (MkDate(0x12345678, 5).date == 0x12345678) correct++;

  CDate (*fp)(I32 date, U32 time) = &MkDate;
  if (fp(-8, 98).date == -8 && fp(-8, 98).time == 98) correct++;

  /* Cast results are values too. */
  I64 raw = 0xFFFFFFF900000063;
  if (raw(CDate).date == -7 && raw(CDate).time == 99) correct++;

  /* In expressions, arguments and loops. */
  if (Twice(MkDate(21, 0).date) == 42) correct++;
  I64 tot = 0;
  for (i = 0; i < 5; i++) {
    tot += MkDate(i, i * 2).date + MkDate(i, i * 2).time;
  }
  if (tot == 30) correct++;

  /* A narrower intrinsic class. */
  if (MkP(250, -4).lo == 250 && MkP(250, -4).hi == -4) correct++;

  /* The stdlib's Now() / Struct2Date(). */
  CDate n = Now();
  if (Now().date >= n.date && Now().date - n.date <= 1) correct++;
  CDateStruct ds;
  MemSet(&ds, 0, sizeof(CDateStruct));
  ds.year = 2024;
  ds.mon = 2;
  ds.day_of_mon = 29;
  ds.hour = 13;
  CDate d = Struct2Date(&ds);
  if (Struct2Date(&ds).date == d.date && Struct2Date(&ds).time == d.time)
    correct++;

  /* Copying first still works. */
  CDate c = MkDate(3, 4);
  if (c.date == 3 && c.time == 4) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
