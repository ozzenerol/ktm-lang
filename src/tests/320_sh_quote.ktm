#include "testhelper.ktm"

/* ShQuote(s) single-quotes s for sh, so text can go into Sh and Shlurp
 * commands as one word with nothing expanded. Each string goes through
 * Shlurp("printf '%%s.' %s", q) and must come back unchanged (the "."
 * keeps Shlurp from dropping a trailing newline). */

extern "c" U8 *mkdtemp(U8 *template);
extern "c" I32 access(U8 *path, I32 mode);

Bool RoundTrip(U8 *s)
{
  U8 *q = ShQuote(s);
  U8 *got = Shlurp("printf '%%s.' %s", q);
  U8 *want = StrPrint(NULL, "%s.", s);
  Bool ok = got && !StrCmp(got, want);
  if (!ok) "round trip of [%s] via [%s]: got [%s]\n", s, q, got;
  Free(q);
  Free(got);
  Free(want);
  return ok;
}

Bool QuoteIs(U8 *s, U8 *want)
{
  U8 *q = ShQuote(s);
  Bool ok = !StrCmp(q, want);
  if (!ok) "ShQuote: got [%s] want [%s]\n", q, want;
  Free(q);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 8, i, bad;
  U8 dir[64], path[128], all[256], *q, *cmd;
  "Test - ShQuote: ";

  if (QuoteIs("abc", "'abc'") && QuoteIs("", "''") && QuoteIs(NULL, "''") &&
      QuoteIs("it's", "'it'\\''s'") && QuoteIs("''", "''\\'''\\'''"))
    correct++;

  if (RoundTrip("hello world") && RoundTrip("  two  spaces ") &&
      RoundTrip("") && RoundTrip("-n") && RoundTrip("%s %d %%"))
    correct++;

  if (RoundTrip("it's") && RoundTrip("'") && RoundTrip("''") &&
      RoundTrip("'a'b'") && RoundTrip("\"double\" quotes") &&
      RoundTrip("mixed '\"' quotes")) correct++;

  /* Nothing is expanded: variables, command substitution, globs, ~ */
  if (RoundTrip("$HOME") && RoundTrip("${HOME}") && RoundTrip("`echo hi`") &&
      RoundTrip("$(echo hi)") && RoundTrip("*") && RoundTrip("~") &&
      RoundTrip("a;b|c&d>e<f") && RoundTrip("!$?#")) correct++;

  if (RoundTrip("\\") && RoundTrip("back\\slash\\") && RoundTrip("\\'") &&
      RoundTrip("a\nb") && RoundTrip("trailing newline\n") &&
      RoundTrip("\n\n") && RoundTrip("tab\there")) correct++;

  /* Every byte but NUL */
  for (i = 1; i < 256; i++) all[i - 1] = i;
  all[255] = '\0';
  if (RoundTrip(all)) correct++;

  /* A string that would run a command if it weren't quoted */
  StrCpy(dir, "./ktm-shquote-XXXXXX");
  if (mkdtemp(dir)) {
    StrPrint(path, "%s/pwned", dir);
    U8 *evil = StrPrint(NULL, "x'; touch %s; echo '$(touch %s)`touch %s`",
                        path, path, path);
    q = ShQuote(evil);
    I64 st = Sh("printf '%%s' %s > /dev/null", q);
    if (st == 0 && RoundTrip(evil) && access(path, 0) != 0) correct++;
    else "command ran: status %d\n", st;
    Free(q);
    Free(evil);
    Sh("rm -rf '%s'", dir);
  } else {
    "mkdtemp failed\n";
  }

  /* One word to the shell, spaces and all */
  q = ShQuote("a b  c");
  cmd = StrPrint(NULL, "test %s = 'a b  c'", q);
  bad = Sh(cmd);
  if (bad == 0 && Sh("set -- %s; test $# -eq 1", q) == 0) correct++;
  else "Sh with a quoted word: %d\n", bad;
  Free(cmd);
  Free(q);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
