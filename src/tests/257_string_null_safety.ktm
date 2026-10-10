#include "testhelper.ktm"

/* String functions crashed on NULL. Now: StrCmp/StrNCmp/StrNICmp sort
 * NULL before every string (two NULLs are equal), StrFirstOcc/StrLastOcc/
 * FileExtDot return NULL, IsDotC/IsDotZ FALSE, Atoi 0, the *Rem functions
 * leave dst empty, and StrCpy with a NULL src copies "" (on arm64 too).
 * (StrTrimStart/End and WildMatch got the same in their own fixes.) */

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - string NULL safety: ";

  tests++;
  if (StrCmp(NULL, NULL) == 0 && StrCmp(NULL, "") < 0 && StrCmp("", NULL) > 0 &&
      StrCmp(NULL, "a") < 0 && StrCmp("a", NULL) > 0) correct++;
  tests++;
  if (StrNCmp(NULL, NULL, 3) == 0 && StrNCmp(NULL, "a", 3) < 0 &&
      StrNCmp("a", NULL, 3) > 0 && StrNICmp(NULL, NULL, 3) == 0 &&
      StrNICmp(NULL, "A", 1) < 0 && StrNICmp("a", NULL, 1) > 0) correct++;
  /* ordinary comparisons are unchanged */
  tests++;
  if (StrCmp("abc", "abd") < 0 && StrCmp("b", "a") > 0 && StrCmp("x", "x") == 0 &&
      StrNCmp("abcX", "abcY", 3) == 0 && StrNCmp("abc", "abd", 3) < 0 &&
      StrNICmp("HeLLo", "hello", 5) == 0 && StrNICmp("a", "B", 1) < 0 &&
      StrNCmp("a", "b", 0) == 0) correct++;

  tests++;
  if (StrFirstOcc(NULL, ".") == NULL && StrFirstOcc("a.b", NULL) == NULL &&
      StrLastOcc(NULL, ".") == NULL && StrLastOcc("a.b", NULL) == NULL &&
      FileExtDot(NULL) == NULL && !IsDotC(NULL) && !IsDotZ(NULL) &&
      Atoi(NULL) == 0 && Atoi("42") == 42) correct++;

  U8 dst[16];
  StrCpy(dst, "junk");
  FileExtRem(NULL, dst);
  Bool ok = dst[0] == '\0';
  StrCpy(dst, "junk");
  StrFirstRem(NULL, "/", dst);
  ok = ok && dst[0] == '\0';
  StrCpy(dst, "junk");
  StrLastRem(NULL, "/", dst);
  ok = ok && dst[0] == '\0';
  StrCpy(dst, "junk");
  StrCpy(dst, NULL);
  ok = ok && dst[0] == '\0';
  StrCpy(NULL, "x");   /* does nothing */
  tests++; if (ok) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
