#include "testhelper.ktm"

/* A string in a char-array slot of an initialiser - a row of
 * `U8 names[2][8]` or a `U8 name[8]` class field - is the string's
 * bytes, zero-filled to the slot. Globals stored a pointer to the
 * literal there, class fields warned "Incompatible value" and stored a
 * pointer (locals too), and local rows copied the escapes undecoded
 * (`"a\nb"` as `a`, `\`, `n`, `b`). */

#define STR_INIT_FILE "__155_init_char_array_strings.ktm"
#define STR_INIT_OUT  "__155_init_char_array_strings.out"

class C155
{
  I64 id;
  U8 name[8];
  I64 z;
};

class Outer155
{
  U8 tag[4];
  C155 c;
  U8 *p;
};

U8 g_155_names[2][8] = {"ab","cd"};
U8 g_155_esc[3][4] = {"a\nb","\x41","\0z"};
U8 g_155_top[8] = "a\tb";
C155 g_155_c = {1,"xy",3};
C155 g_155_ce = {4,"\x42\tq",5};
C155 g_155_full = {6,"1234567",7};
C155 g_155_arr[2] = {{1,"one",2},{3,"two",4}};
Outer155 g_155_o = {"tg",{8,"in",9},"ptr"};

/* The bytes of `s` at its slot of `n` bytes: `want` then zeros. */
Bool SlotIs(U8 *s, I64 n, U8 *want, I64 want_len)
{
  I64 i;
  for (i = 0; i < n; i++) {
    if (i < want_len) {
      if (s[i] != want[i]) return FALSE;
    } else if (s[i] != 0) {
      return FALSE;
    }
  }
  return TRUE;
}

Bool TestGlobalRows(U0)
{
  Bool ok = TRUE;
  if (!SlotIs(g_155_names[0], 8, "ab", 2)) ok = FALSE;
  if (!SlotIs(g_155_names[1], 8, "cd", 2)) ok = FALSE;
  if (!SlotIs(g_155_esc[0], 4, "a\nb", 3)) ok = FALSE;
  if (!SlotIs(g_155_esc[1], 4, "A", 1)) ok = FALSE;
  if (g_155_esc[2][0] != 0 || g_155_esc[2][1] != 'z' ||
      g_155_esc[2][2] != 0 || g_155_esc[2][3] != 0) ok = FALSE;
  if (!SlotIs(g_155_top, 8, "a\tb", 3)) ok = FALSE;
  return ok;
}

Bool TestLocalRows(U0)
{
  U8 names[2][8] = {"ab","cd"};
  U8 esc[3][4] = {"a\nb","\x41","\0z"};
  U8 top[8] = "a\tb";
  Bool ok = TRUE;
  if (!SlotIs(names[0], 8, "ab", 2)) ok = FALSE;
  if (!SlotIs(names[1], 8, "cd", 2)) ok = FALSE;
  if (!SlotIs(esc[0], 4, "a\nb", 3)) ok = FALSE;
  if (!SlotIs(esc[1], 4, "A", 1)) ok = FALSE;
  if (esc[2][0] != 0 || esc[2][1] != 'z' ||
      esc[2][2] != 0 || esc[2][3] != 0) ok = FALSE;
  if (!SlotIs(top, 8, "a\tb", 3)) ok = FALSE;
  return ok;
}

Bool TestGlobalFields(U0)
{
  Bool ok = TRUE;
  if (g_155_c.id != 1 || g_155_c.z != 3) ok = FALSE;
  if (!SlotIs(g_155_c.name, 8, "xy", 2)) ok = FALSE;
  if (g_155_ce.id != 4 || g_155_ce.z != 5) ok = FALSE;
  if (!SlotIs(g_155_ce.name, 8, "B\tq", 3)) ok = FALSE;
  if (g_155_full.id != 6 || g_155_full.z != 7) ok = FALSE;
  if (!SlotIs(g_155_full.name, 8, "1234567", 7)) ok = FALSE;
  if (g_155_arr[0].id != 1 || g_155_arr[0].z != 2) ok = FALSE;
  if (g_155_arr[1].id != 3 || g_155_arr[1].z != 4) ok = FALSE;
  if (!SlotIs(g_155_arr[0].name, 8, "one", 3)) ok = FALSE;
  if (!SlotIs(g_155_arr[1].name, 8, "two", 3)) ok = FALSE;
  if (!SlotIs(g_155_o.tag, 4, "tg", 2)) ok = FALSE;
  if (g_155_o.c.id != 8 || g_155_o.c.z != 9) ok = FALSE;
  if (!SlotIs(g_155_o.c.name, 8, "in", 2)) ok = FALSE;
  /* A pointer field still points at the literal. */
  if (StrCmp(g_155_o.p, "ptr")) ok = FALSE;
  return ok;
}

Bool TestLocalFields(U0)
{
  C155 c = {1,"xy",3};
  C155 ce = {4,"\x42\tq",5};
  C155 full = {6,"1234567",7};
  C155 arr[2] = {{1,"one",2},{3,"two",4}};
  Outer155 o = {"tg",{8,"in",9},"ptr"};
  Bool ok = TRUE;
  if (c.id != 1 || c.z != 3) ok = FALSE;
  if (!SlotIs(c.name, 8, "xy", 2)) ok = FALSE;
  if (ce.id != 4 || ce.z != 5) ok = FALSE;
  if (!SlotIs(ce.name, 8, "B\tq", 3)) ok = FALSE;
  if (full.id != 6 || full.z != 7) ok = FALSE;
  if (!SlotIs(full.name, 8, "1234567", 7)) ok = FALSE;
  if (arr[0].id != 1 || arr[0].z != 2) ok = FALSE;
  if (arr[1].id != 3 || arr[1].z != 4) ok = FALSE;
  if (!SlotIs(arr[0].name, 8, "one", 3)) ok = FALSE;
  if (!SlotIs(arr[1].name, 8, "two", 3)) ok = FALSE;
  if (!SlotIs(o.tag, 4, "tg", 2)) ok = FALSE;
  if (o.c.id != 8 || o.c.z != 9) ok = FALSE;
  if (!SlotIs(o.c.name, 8, "in", 2)) ok = FALSE;
  if (StrCmp(o.p, "ptr")) ok = FALSE;
  return ok;
}

U0 CleanupStrInitTest(U0)
{
  Rm(STR_INIT_FILE);
  Rm(STR_INIT_OUT);
  Rm("./a.out");
}

/* Compile `source`; return ktm's exit status and its output in `*out`. */
I64 CompileSource(U8 *source, U8 **out)
{
  I64 res;
  CleanupStrInitTest;
  *out = NULL;
  if (!FileWrite(STR_INIT_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return -1;
  }
  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " STR_INIT_FILE " > " STR_INIT_OUT " 2>&1");
  *out = FileRead(STR_INIT_OUT);
  CleanupStrInitTest;
  return res;
}

/* A string field initialiser compiles without a diagnostic. */
Bool TestNoWarning(U0)
{
  U8 *out;
  I64 res = CompileSource(
"class C { I64 id; U8 name[8]; I64 z; };
C g = {1,\"xy\",3};
U0 Main() { C c = {1,\"xy\",3}; }
", &out);
  Bool ok = res == 0 && out && !strstr(out, "warning") && !strstr(out, "error");
  if (!ok) "status %d\n%s\n", res, out;
  Free(out);
  return ok;
}

/* A string that doesn't fit the field with its NUL is an error. */
Bool TestFieldTooLong(U8 *source, U8 *loc)
{
  U8 *out, *first;
  I64 res = CompileSource(source, &out);
  U8 *msg = "Invalid array initializer: expected 4 items but got 5";
  Bool ok = res == 1 && out;
  if (ok) {
    first = strstr(out, "error: ");
    if (!first || StrNCmp(first + 7, msg, StrLen(msg))) ok = FALSE;
    else if (!strstr(first, loc)) ok = FALSE;
    else if (strstr(first + 7, "error: ")) ok = FALSE;
  }
  if (!ok) "status %d\n%s\n", res, out;
  Free(out);
  return ok;
}

I32 Main()
{
  "Test - String initialisers for char-array slots: ";
  I64 tests = 7;
  I64 correct = 0;

  if (TestGlobalRows()) correct++;
  else "Failed - TestGlobalRows()\n";

  if (TestLocalRows()) correct++;
  else "Failed - TestLocalRows()\n";

  if (TestGlobalFields()) correct++;
  else "Failed - TestGlobalFields()\n";

  if (TestLocalFields()) correct++;
  else "Failed - TestLocalFields()\n";

  if (TestNoWarning()) correct++;
  else "Failed - TestNoWarning()\n";

  if (TestFieldTooLong(
"class D { U8 n[4]; I64 z; };
D g = {\"abcd\",1};
U0 Main() {}
", "2:8")) correct++;
  else "Failed - TestFieldTooLong(global)\n";

  if (TestFieldTooLong(
"class D { U8 n[4]; I64 z; };
U0 Main() { D d = {\"abcd\",1}; }
", "2:20")) correct++;
  else "Failed - TestFieldTooLong(local)\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
