#include "testhelper.ktm"

/* `continue` inside a switch crashed the compiler ("NULL param"): the
 * switch pushed a loop context with no continue target. It must go to
 * the enclosing loop. */

#define CONT_SWITCH_FILE "__327_continue_switch.ktm"
#define CONT_SWITCH_OUT  "__327_continue_switch.out"

I64 CatchDepth()
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

I64 ForSwitch()
{
  I64 i, n = 0;
  for (i = 0; i < 4; i++) {
    switch (i) {
      case 1: continue;
      default: n += 10;
    }
    n++;
  }
  return n;
}

I64 NestedSwitch()
{
  I64 i, n = 0;
  for (i = 0; i < 6; i++) {
    switch (i & 1) {
      case 0:
        switch (i) {
          case 2: continue;
          default: n += 100;
        }
        break;
      case 1:
        n += 1;
        break;
    }
    n += 1000;
  }
  return n;
}

I64 WhileSwitch()
{
  I64 i = 0, n = 0;
  while (i < 5) {
    i++;
    switch (i) {
      case 2:
      case 4:
        continue;
    }
    n += i;
  }
  return n;
}

I64 DoWhileSwitch()
{
  I64 i = 0, n = 0;
  do {
    i++;
    switch (i) {
      case 3: continue;
      default: break;
    }
    n += i;
  } while (i < 5);
  return n;
}

I64 BreakStaysInSwitch()
{
  I64 i, n = 0;
  for (i = 0; i < 3; i++) {
    switch (i) {
      case 0: break;
      case 1: continue;
      default: break;
    }
    n++;
  }
  return n;
}

I64 TryAroundSwitch()
{
  I64 i, n = 0;
  for (i = 0; i < 4; i++) {
    try {
      switch (i) {
        case 1: continue;
        case 2: throw('Two');
        default: n += 10;
      }
      n++;
    } catch {
      n += 100;
    }
  }
  return n;
}

I64 SwitchAroundTry()
{
  I64 i, n = 0;
  for (i = 0; i < 4; i++) {
    switch (i) {
      case 1:
        try {
          try {
            continue;
          } catch {
          }
        } catch {
        }
      default: n += 10;
    }
    n++;
  }
  return n;
}

I64 LoopInsideTry()
{
  I64 i, n = 0;
  try {
    for (i = 0; i < 4; i++) {
      switch (i) {
        case 1: continue;
        default: n += CatchDepth();
      }
    }
  } catch {
    n = -1;
  }
  return n;
}

U0 CleanupContinueSwitch(U0)
{
  Rm(CONT_SWITCH_FILE);
  Rm(CONT_SWITCH_OUT);
  Rm("./a.out");
}

Bool ContinueWithoutLoopFails()
{
  U8 *src = "U0 Main()\n{\n  I64 i = 1;\n  switch (i) {\n    case 1: continue;\n  }\n}\n";
  U8 *out;
  I64 res;
  Bool ok;

  CleanupContinueSwitch;
  if (!FileWrite(CONT_SWITCH_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  res = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " CONT_SWITCH_FILE " > " CONT_SWITCH_OUT " 2>&1");
  out = FileRead(CONT_SWITCH_OUT);
  ok = res != 0 && out && strstr(out, "continue, not inside a loop") &&
       !strstr(out, "NULL param");
  if (!ok && out) "%s\n", out;
  Free(out);
  CleanupContinueSwitch;
  return ok;
}

I32 Main()
{
  "Test - continue in switch: ";
  I64 correct = 0, tests = 0;

  tests++; if (ForSwitch == 33) correct++;
  tests++; if (NestedSwitch == 5203) correct++;
  tests++; if (WhileSwitch == 9) correct++;
  tests++; if (DoWhileSwitch == 12) correct++;
  tests++; if (BreakStaysInSwitch == 2) correct++;
  tests++; if (TryAroundSwitch == 122) correct++;
  tests++; if (CatchDepth == 0) correct++;
  tests++; if (SwitchAroundTry == 33) correct++;
  tests++; if (CatchDepth == 0) correct++;
  tests++; if (LoopInsideTry == 3) correct++;
  tests++; if (CatchDepth == 0) correct++;
  tests++; if (ContinueWithoutLoopFails) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
