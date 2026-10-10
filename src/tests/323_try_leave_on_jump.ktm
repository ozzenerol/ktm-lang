#include "testhelper.ktm"

/* A return, break, continue or goto that left a try body never popped
 * its catch frame, so the next throw longjmp'd into the stack of a
 * function that had already returned (segfault or endless loop). */

I64 Depth()
{
  if (!Fs) return 0;
  I64 n = 0;
  CatchFrame *f = Fs->catch_top;
  while (f) {
    n++;
    f = f->prev;
  }
  return n;
}

U0 Boom(I64 v)
{
  throw(v);
}

I64 Risky(I64 x)
{
  if (x < 0) throw('Neg');
  return x + 1;
}

I64 RetInTry(I64 x)
{
  try {
    if (x > 0) return x * 2;
  } catch {
    return -1;
  }
  return -2;
}

I64 RetComputed(I64 x)
{
  try {
    return Risky(x) * 3;
  } catch {
    return -Fs->except_ch;
  }
  return 0;
}

I64 RetNested(I64 x)
{
  try {
    try {
      try {
        if (x == 1) return 100;
      } catch {
      }
      if (x == 2) return 200;
    } catch {
    }
  } catch {
  }
  return 300;
}

I64 RetFromCatch()
{
  try {
    try {
      throw('In');
    } catch {
      return 7;
    }
  } catch {
    return -7;
  }
  return 0;
}

I64 RetInLoopInTry(I64 n)
{
  I64 i;
  try {
    for (i = 0; i < 10; i++) {
      try {
        if (i == n) return i;
      } catch {
      }
    }
  } catch {
  }
  return -1;
}

I64 BreakLoops()
{
  I64 i, total = 0;
  for (i = 0; i < 1000; i++) {
    try {
      if (i % 2) continue;
      total++;
    } catch {
    }
  }
  i = 0;
  while (1) {
    try {
      if (++i == 5) break;
    } catch {
    }
  }
  total += i;
  i = 0;
  do {
    try {
      try {
        if (++i == 3) break;
      } catch {
      }
    } catch {
    }
  } while (1);
  total += i;
  return total;
}

I64 SwitchBreak(I64 x)
{
  I64 r = 0;
  switch (x) {
    case 1:
      try {
        r = 1;
        break;
      } catch {
      }
      r = 99;
      break;
    default:
      r = 2;
  }
  return r;
}

I64 BreakInsideTry()
{
  I64 i, r = 0;
  try {
    for (i = 0; i < 5; i++) {
      if (i == 2) break;
    }
    switch (i) {
      case 2:
        r = 1;
        break;
      default:
        r = 0;
    }
    Boom('Mine');
  } catch {
    if (Fs->except_ch == 'Mine') r += 10;
  }
  return r;
}

I64 Gotos()
{
  I64 r = 0, i = 0;
  try {
    try {
      goto out;
    } catch {
    }
  } catch {
  }
out:
  r += Depth();
  try {
    try {
      goto mid;
    } catch {
    }
mid:
    r += Depth() * 10;
    Boom('Mid');
  } catch {
    if (Fs->except_ch == 'Mid') r += 100;
  }
  try {
again:
    i++;
    if (i < 3) goto again;
    Boom('Loop');
  } catch {
    if (Fs->except_ch == 'Loop') r += 1000 * i;
  }
  return r;
}

I32 Main()
{
  "Test - jumping out of a try body pops its catch frame: ";
  I64 correct = 0, tests = 16, base = Depth();

  if (RetInTry(5) == 10 && Depth() == base) correct++;
  if (RetInTry(0) == -2 && Depth() == base) correct++;
  if (RetComputed(4) == 15 && Depth() == base) correct++;
  if (RetComputed(-1) == -'Neg' && Depth() == base) correct++;
  if (RetNested(1) == 100 && RetNested(2) == 200 && RetNested(3) == 300 &&
      Depth() == base) correct++;
  if (RetFromCatch() == 7 && Depth() == base) correct++;
  if (RetInLoopInTry(4) == 4 && Depth() == base) correct++;
  if (BreakLoops() == 500 + 5 + 3 && Depth() == base) correct++;
  if (SwitchBreak(1) == 1 && SwitchBreak(0) == 2 && Depth() == base) correct++;
  if (BreakInsideTry() == 11 && Depth() == base) correct++;
  if (Gotos() == 0 + 10 + 100 + 3000 && Depth() == base) correct++;

  /* After all of that a throw reaches the live try, not a dead frame. */
  I64 caught = 0, i;
  for (i = 0; i < 100; i++) {
    RetInTry(1);
    BreakLoops();
    try {
      Boom(i);
    } catch {
      if (Fs->except_ch == i) caught++;
    }
  }
  if (caught == 100) correct++;

  I64 outer = 0;
  try {
    RetInTry(3);
    RetNested(1);
    Gotos();
    Boom('Out');
  } catch {
    outer = Fs->except_ch;
  }
  if (outer == 'Out') correct++;

  /* A return inside the outer try leaves only the inner frames. */
  I64 got = 0;
  try {
    if (RetInLoopInTry(2) == 2) Boom('Ok');
  } catch {
    got = Fs->except_ch;
  }
  if (got == 'Ok') correct++;

  if (Depth() == base) correct++;
  if (RetComputed(9) == 30) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
