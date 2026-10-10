#include "testhelper.ktm"

/* A lone `;` in a function body ended the body: the next statement was
 * "Syntax error ... perhaps you meant `}`?". `case 1: ;` and
 * `default: ;` crashed the compiler, and `for (i = 0; i < n; i++) ;`
 * (an empty body) was dropped whole, init included. The empty statement
 * now works everywhere a statement can be, without swallowing the next
 * one. */

I64 g_calls = 0;
U0 Hit() { g_calls++; }

I64 AtStart() { ; return 1; }
I64 InMiddle() { I64 x = 2; ; x++; ;; x++; return x; }
U0 AtEnd() { Hit; ; }
U0 OnlyEmpty() { ; ;; }
I64 Nested()
{
  I64 n = 0;
  { ; }
  { ; n++; ; }
  { { ;; } n++; }
  return n;
}

I64 EmptyBodies()
{
  I64 i = 0, n = 0;
  if (1) ;
  if (0) n += 100; else ;
  while (i++ < 3) ;
  n += i;                       /* 4 */
  for (i = 0; i < 5; i++) ;
  n += i;                       /* 5 */
  I64 j = 0;
  do ; while (j++ < 2);
  n += j;                       /* 3 */
  return n;
}

I64 InSwitch(I64 v)
{
  I64 r = 0;
  switch (v) {
    case 1: ; r = 10; break;
    case 2: ; ; break;
    case 3: r = 30; ; break;
    default: ; r = -1;
  }
  return r;
}

I64 AfterLabel()
{
  I64 n = 0;
  goto L;
  n = 99;
L: ;
  n++;
  return n;
}

I32 Main()
{
  "Test - empty statement in a body: ";
  I64 correct = 0, tests = 9;

  if (AtStart == 1) correct++;
  if (InMiddle == 4) correct++;
  AtEnd;
  OnlyEmpty;
  if (g_calls == 1) correct++;
  if (Nested == 2) correct++;
  if (EmptyBodies == 12) correct++;
  if (InSwitch(1) == 10 && InSwitch(2) == 0 && InSwitch(3) == 30 &&
      InSwitch(7) == -1) correct++;
  if (AfterLabel == 1) correct++;

  /* `;;` right after a declaration doesn't take the next statement */
  I64 k = 5;;
  ;;
  k++;
  if (k == 6) correct++;

  /* The REPL takes them too */
  if (System("printf 'I64 x = 2;\\n;\\n;;\\n\"%%d\\\\n\", x + 1;\\n' | "
             "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
             "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
             "-repl 2>&1 | grep -qx '3'") == 0) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
