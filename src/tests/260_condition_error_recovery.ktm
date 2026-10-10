#include "testhelper.ktm"

/* An error inside the parentheses of an if, while, do-while, for or
 * switch was recovered from by skipping to the next `;`: in
 * `if (F(1, 2)) a; else b;` that is the one before the `else`, which was
 * then reported as a second, bogus error ("Keyword 'else' cannot be used
 * in this context"). Now the rest of the parentheses is skipped and the
 * statement is parsed on, else branches and bodies included, so only the
 * real errors are reported - including real ones in the bodies. Covers
 * argument-count and type errors, undefined names and a missing `)`. */

#define IFR_SRC "./ktm-260-ifr.ktm"
#define IFR_OUT "./ktm-260-ifr.out"

/* Compile `source` and return what ktm printed */
U8 *Diagnose(U8 *source)
{
  System("rm -f " IFR_SRC " " IFR_OUT);
  FileWrite(IFR_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " IFR_SRC " -o /dev/null > " IFR_OUT " 2>&1");
  U8 *out = FileRead(IFR_OUT);
  System("rm -f " IFR_SRC " " IFR_OUT);
  return out;
}

I64 Count(U8 *s, U8 *what)
{
  I64 n = 0;
  while (s && (s = strstr(s, what))) {
    n++;
    s++;
  }
  return n;
}

/* ktm prints `errors` errors, `warnings` warnings, and `want` */
Bool Prints(U8 *source, I64 errors, I64 warnings, U8 *want)
{
  U8 *out = Diagnose(source);
  Bool ok = out && Count(out, "error:") == errors &&
            Count(out, "warning:") == warnings && strstr(out, want);
  if (!ok) "got: '%s'\n", out;
  Free(out);
  return ok;
}

#define PRE "class P { I64 x; };\nU0 *F(I64 a) { return NULL; }\nU0 Main() {\n  I64 y = 1; P p;\n"

I32 Main()
{
  "Test - error recovery in conditions: ";
  I64 tests = 0;
  I64 correct = 0;

  tests++;
  if (Prints(PRE "  if (F(1, 2)) y = 1;\n  else y = 2;\n}\n", 1, 0,
             "Unexpected number of arguments 2 in call to F()"))
    correct++;
  else "Failed - the report\n";

  tests++;
  if (Prints(PRE "  if (zz > 1) y = 1;\n  else if (y) y = 2;\n  else y = 3;\n}\n",
             1, 0, "260-ifr.ktm:5:7\n"))
    correct++;
  else "Failed - undefined name, else-if chain\n";

  tests++;
  if (Prints(PRE "  if (y) y = 1;\n  else if (p + 1) y = 2;\n  else y = 3;\n}\n",
             1, 0, "260-ifr.ktm:6:14\n"))
    correct++;
  else "Failed - type error in an else-if\n";

  tests++;
  if (Prints(PRE "  if (y == 1 {\n    y = 2;\n  } else {\n    y = 3;\n  }\n}\n",
             1, 0, "Expected ';' or ')' in `if (...)`, got `{`\n --> ./ktm-260-ifr.ktm:5:14\n"))
    correct++;
  else "Failed - missing ) before {\n";

  tests++;
  if (Prints(PRE "  if (y == 1 && (y > 0 {\n    y = 2;\n  } else {\n    y = zz;\n  }\n}\n",
             2, 0, "260-ifr.ktm:8:9\n"))
    correct++;
  else "Failed - missing ) and an error in the else body\n";

  tests++;
  if (Prints(PRE "  if (F((1, 2)) y = 1;\n  else y = 2;\n}\n", 1, 0, "260-ifr.ktm:5:11\n"))
    correct++;
  else "Failed - unbalanced parentheses\n";

  tests++;
  if (Prints(PRE "  while (zz) { y = qq; }\n  y = 4;\n}\n", 2, 0, "260-ifr.ktm:5:20\n"))
    correct++;
  else "Failed - while, and its body still checked\n";

  tests++;
  if (Prints(PRE "  while () y++;\n  y = 4;\n}\n", 1, 0,
             "Expected the `while` condition, got `)`"))
    correct++;
  else "Failed - empty while\n";

  tests++;
  if (Prints(PRE "  do { y++; } while (zz);\n  y = 1 2;\n}\n", 2, 0, "260-ifr.ktm:6:9\n"))
    correct++;
  else "Failed - do-while\n";

  tests++;
  if (Prints(PRE "  for (y = 0; zz < 3; y++) { y++; }\n  y = 1 2;\n}\n", 2, 0,
             "260-ifr.ktm:6:9\n"))
    correct++;
  else "Failed - for\n";

  tests++;
  if (Prints(PRE "  switch (zz) { case 1: y = 2; break; default: y = qq; }\n}\n",
             2, 0, "260-ifr.ktm:5:52\n"))
    correct++;
  else "Failed - switch\n";

  tests++;
  if (Prints(PRE "  switch [zz] { case 1: y = 2; }\n  y = 1 2;\n}\n", 2, 0, "260-ifr.ktm:6:9\n"))
    correct++;
  else "Failed - switch []\n";

  tests++;
  if (Prints(PRE "  if (I64 k = qq; k > 1) y = k;\n  else y = 3;\n}\n", 1, 0, "260-ifr.ktm:5:15\n"))
    correct++;
  else "Failed - if with an init statement\n";

  /* An error in a range for's body is not taken for one in its header */
  tests++;
  if (Prints("U0 Main() {\n  I64 xs[3];\n  for (I64 x : xs) { x = zz; }\n  I64 y = 1 2;\n}\n",
             2, 0, "260-ifr.ktm:4:13\n"))
    correct++;
  else "Failed - range for body\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
