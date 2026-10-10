#include "testhelper.ktm"

/* A goto to a label inside a block that follows a return crashed ktm
 * (AOT and JIT): IR lowering dropped every statement after the return
 * unless it was a bare label, so the label's block stayed empty, and
 * merging that empty block freed a list head still in use. Statements
 * that hold a label are now lowered into a fresh block that nothing
 * falls into, as after a goto. */

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

/* the repro */
I64 PlainBlock(I64 x)
{
  if (x) goto inner;
  return 1;
  {
inner:
    x--;
  }
  return x;
}

/* the label sits in an if whose condition is never tested */
I64 IfBlock(I64 x)
{
  if (x) goto inner;
  return 1;
  if (x > 5) {
inner:
    x--;
  }
  return x * 10;
}

/* into the middle of a loop body: the loop goes on as usual */
I64 IntoLoop(I64 x)
{
  I64 n = 0;
  if (x) goto inner;
  return -1;
  while (x > 0) {
inner:
    n++;
    x--;
  }
  return n;
}

/* two blocks deep */
I64 Nested(I64 x)
{
  if (x) goto deep;
  return 0;
  {
    if (x > 100) {
deep:
      x += 1;
    }
  }
  return x;
}

/* from inside a switch */
I64 FromSwitch(I64 x)
{
  switch (x) {
    case 1: return 10;
    default: goto in;
  }
  return 0;
  {
in:
    x *= 3;
  }
  return x;
}

/* an empty labelled block after a return */
I64 g_count = 0;
U0 EmptyBlock(I64 x)
{
  if (x) goto end;
  g_count++;
  return;
  {
end:
  }
}

/* a dead block without a label is still dropped */
I64 NoLabel(I64 x)
{
  return 1;
  {
    x++;
  }
  return x;
}

I32 Main()
{
  "Test - goto into dead block: ";

  Check(PlainBlock(0) == 1 && PlainBlock(5) == 4, "plain block");
  Check(IfBlock(0) == 1 && IfBlock(5) == 40 && IfBlock(9) == 80, "if block");
  Check(IntoLoop(0) == -1 && IntoLoop(3) == 3, "into a loop body");
  Check(Nested(0) == 0 && Nested(7) == 8, "two blocks deep");
  Check(FromSwitch(1) == 10 && FromSwitch(4) == 12, "from a switch");
  EmptyBlock(0);
  EmptyBlock(1);
  Check(g_count == 1, "empty labelled block");
  Check(NoLabel(3) == 1, "dead block without a label");

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
