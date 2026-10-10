#include "testhelper.ktm"

/* A function's frame size was kept in 16 bits (IrFunction.stack_space and
 * the prologues' total_stack were u16), so a function with more than about
 * 64 KiB of locals reserved only the size mod 65536: its locals lay below
 * the stack pointer and the next call wrote over them (65480 bytes of
 * array worked, 65481 didn't). Each level here fills its own array, lets a
 * stack-hungry helper run below it, recurses, and checks every byte after
 * the calls return. */

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

I64 Deep(I64 n)
{// ~100 KiB of stack below the caller's frame at n = 200
  I64 pad[64];
  MemSet(pad, n & 0xFF, sizeof(pad));
  if (n == 0) return 0;
  return Deep(n - 1) + pad[n % 64];
}

/* Bytes of buf that aren't `v` */
I64 Bad(U8 *buf, I64 len, U8 v)
{
  I64 i, bad = 0;
  for (i = 0; i < len; i++) bad += buf[i] != v;
  return bad;
}

/* Fill the frame's array, let Deep run below it, check it */
I64 Fill(U8 *buf, I64 size, I64 depth)
{
  MemSet(buf, 0x40 + depth, size);
  Deep(200);
  return Bad(buf, size, 0x40 + depth);
}

I64 Below(I64 depth)
{
  U8 buf[65480];
  I64 bad = Fill(buf, 65480, depth);
  if (depth > 0) bad += Below(depth - 1);
  Deep(200);
  return bad + Bad(buf, 65480, 0x40 + depth);
}

I64 Above(I64 depth)
{
  U8 buf[65481];
  I64 bad = Fill(buf, 65481, depth);
  if (depth > 0) bad += Above(depth - 1);
  Deep(200);
  return bad + Bad(buf, 65481, 0x40 + depth);
}

I64 Frame64K(I64 depth)
{
  U8 buf[65536];
  I64 bad = Fill(buf, 65536, depth);
  if (depth > 0) bad += Frame64K(depth - 1);
  Deep(200);
  return bad + Bad(buf, 65536, 0x40 + depth);
}

I64 Frame65K(I64 depth)
{
  U8 buf[66560];
  I64 bad = Fill(buf, 66560, depth);
  if (depth > 0) bad += Frame65K(depth - 1);
  Deep(200);
  return bad + Bad(buf, 66560, 0x40 + depth);
}

I64 Frame1M(I64 depth)
{
  U8 buf[1048576];
  I64 bad = Fill(buf, 1048576, depth);
  if (depth > 0) bad += Frame1M(depth - 1);
  Deep(200);
  return bad + Bad(buf, 1048576, 0x40 + depth);
}

I64 Frame4M(I64 depth)
{
  U8 buf[4194304];
  I64 bad = Fill(buf, 4194304, depth);
  if (depth > 0) bad += Frame4M(depth - 1);
  Deep(200);
  return bad + Bad(buf, 4194304, 0x40 + depth);
}

/* Several big locals plus scalars around them in one frame */
I64 Mixed()
{
  I64 before = 0x1111;
  U8 a[40000];
  F64 f = 2.5;
  U8 b[40000];
  I64 after = 0x2222;
  MemSet(a, 'a', sizeof(a));
  MemSet(b, 'b', sizeof(b));
  Deep(200);
  return Bad(a, sizeof(a), 'a') + Bad(b, sizeof(b), 'b') +
         (before != 0x1111) + (after != 0x2222) + (f != 2.5);
}

I32 Main()
{
  "Test - stack frames over 64 KiB: ";
  Check(Below(0) == 0, "65480-byte frame");
  Check(Above(0) == 0, "65481-byte frame");
  Check(Frame64K(9) == 0, "64 KiB frames, 10 deep");
  Check(Frame65K(9) == 0, "65 KiB frames, 10 deep");
  Check(Frame1M(2) == 0, "1 MiB frames, 3 deep");
  Check(Frame4M(0) == 0, "4 MiB frame");
  Check(Mixed() == 0, "two 40000-byte arrays and scalars in one frame");
  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
