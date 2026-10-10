#include "testhelper.ktm"

/* The compile-time check of a StrPrint-family format string against its
 * arguments only knew C's conversions: `StrPrint(NULL, "%Q %d", "a", 1)`
 * was an error ("expects 1 argument but 2"), as were %T %D %C %F %p, and
 * an unknown conversion was counted together with the next one. It now
 * follows StrPrint: flags `-0+ #`, `*` and `.*` taking an argument, length
 * modifiers, and %d %i %u %o %x %X %c %s %f %e %g %p %Q %D %T %C %F %%; an
 * unknown conversion is a warning and takes no argument. The error was
 * also placed at `file:0` when the format was not the first or second
 * argument (CatLenPrint, StrNPrint); it is now at the format string. */

#define FMT_SRC "./ktm-261-fmt.ktm"
#define FMT_OUT "./ktm-261-fmt.out"

/* Compile `source` and return what ktm printed */
U8 *Diagnose(U8 *source)
{
  System("rm -f " FMT_SRC " " FMT_OUT);
  FileWrite(FMT_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " FMT_SRC " -o /dev/null > " FMT_OUT " 2>&1");
  U8 *out = FileRead(FMT_OUT);
  System("rm -f " FMT_SRC " " FMT_OUT);
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

#define MAIN "U0 Main() {\n  U8 *s = NULL; I64 n = 0; I64 a[4];\n"
#define END "}\n"

I32 Main()
{
  "Test - format check of ktm conversions: ";
  I64 tests = 0;
  I64 correct = 0;

  tests++;
  if (Prints(MAIN "  s = StrPrint(NULL, \"%Q %d\", \"a\", 1);\n" END, 0, 0, ""))
    correct++;
  else "Failed - %%Q\n";

  tests++;
  if (Prints(MAIN "  s = StrPrint(NULL, \"%T %D %C %F %p %% %Q\", 1, 2, 3, \"f\", 4, \"q\");\n"
             "  s = CatPrint(s, \"%i %u %o %x %X %c %s %f %e %g\", 1, 2, 3, 4, 5, 6, \"s\", 1.0, 2.0, 3.0);\n" END,
             0, 0, ""))
    correct++;
  else "Failed - every conversion\n";

  tests++;
  if (Prints(MAIN "  s = StrPrint(NULL, \"%-08.3f|%+d|% d|%#x|%ld|%lld|%hhu|%zd|%jd|%td|%Lf|%qd|%5%\", "
             "1.0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1.5, 11);\n" END, 0, 0, ""))
    correct++;
  else "Failed - flags and length modifiers\n";

  tests++;
  if (Prints(MAIN "  s = StrPrint(NULL, \"%*d|%.*s|%-*.*f\", 5, 1, 2, \"abc\", 8, 2, 1.0);\n" END,
             0, 0, ""))
    correct++;
  else "Failed - * and .*\n";

  tests++;
  if (Prints(MAIN "  s = StrPrint(NULL, \"%Q %d\", \"a\");\n" END, 1, 0,
             "printf format expects 2 arguments but 1 was supplied\n"
             " --> ./ktm-261-fmt.ktm:3:22\n"))
    correct++;
  else "Failed - too few\n";

  tests++;
  if (Prints(MAIN "  s = StrPrint(NULL, \"%*d\", 1);\n" END, 1, 0,
             "printf format expects 2 arguments but 1 was supplied"))
    correct++;
  else "Failed - * needs its argument\n";

  tests++;
  if (Prints(MAIN "  s = StrPrint(NULL, \"%k %d\", 1);\n" END, 0, 1,
             "Unknown format conversion `%k`"))
    correct++;
  else "Failed - unknown conversion\n";

  /* The format as the third argument, after a long one */
  tests++;
  if (Prints(MAIN "  s = CatLenPrint(s, &a[(n + 1) * 2 - sizeof(a) / 16], \"%d %d\", 1);\n" END,
             1, 0, " --> ./ktm-261-fmt.ktm:3:56\n"))
    correct++;
  else "Failed - CatLenPrint location\n";

  tests++;
  if (Prints(MAIN "  s = CatPrint(s,\n    \"%d %s\", 1);\n" END, 1, 0,
             " --> ./ktm-261-fmt.ktm:4:5\n"))
    correct++;
  else "Failed - format on the next line\n";

  /* StrNPrint (format third) once the library has it */
  U8 *probe = Diagnose(MAIN "  U8 b[8];\n  StrNPrint(b, 8, \"%d\", 1);\n" END);
  Bool has_strnprint = probe && !strstr(probe, "not been defined") &&
                       !strstr(probe, "not defined");
  Free(probe);
  tests++;
  if (!has_strnprint ||
      Prints(MAIN "  U8 b[16];\n  StrNPrint(b, sizeof(b), \"%d %Q\", 1);\n" END, 1, 0,
             " --> ./ktm-261-fmt.ktm:4:27\n"))
    correct++;
  else "Failed - StrNPrint\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
