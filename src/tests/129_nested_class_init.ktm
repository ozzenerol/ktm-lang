#include "testhelper.ktm"

/* A nested `{ ... }` in a class initialiser initialises the next field,
 * which can be a class or an array: `Bar b = {1, {2}};`. ktm used to
 * recurse with the element type of the class (NULL) and segfault
 * (exit 139, no diagnostic). */

#define NESTED_INIT_FILE "__129_nested_class_init.ktm"
#define NESTED_INIT_OUT  "__129_nested_class_init.out"

class Foo129
{
  I64 a;
  I64 b;
};

class Bar129
{
  I64 x;
  Foo129 f;
  I64 y;
};

class Arr129
{
  I64 v[3];
  I64 w;
};

class Deep129
{
  Foo129 fs[2];
  Bar129 bar;
};

class Top129
{
  Deep129 d;
  I64 z;
};

class Named129
{
  U8 *name;
  F64 weight;
};

class Mixed129
{
  I32 a;
  Named129 n;
  U8 c;
  I16 s;
};

Bar129 g_bar = {1, {2, 3}, 4};
Arr129 g_arr = {{5, 6, 7}, 8};
Top129 g_top = {{{{1, 2}, {3, 4}}, {5, {6, 7}, 8}}, 9};
Bar129 g_bars[2] = {{1, {2, 3}, 4}, {5, {6, 7}, 8}};
Foo129 g_foos[2] = {{10, 11}, {12, 13}};

U0 CleanupNestedInitTest(U0)
{
  Rm(NESTED_INIT_FILE);
  Rm(NESTED_INIT_OUT);
  Rm("./a.out");
}

/* Compile `source`: ktm must exit with status 1 (not crash) and print
 * `expected`. */
Bool CompileFailsWith(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupNestedInitTest;

  if (!FileWrite(NESTED_INIT_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " NESTED_INIT_FILE " > " NESTED_INIT_OUT " 2>&1");

  out = FileRead(NESTED_INIT_OUT);
  if (!out) {
    CleanupNestedInitTest;
    return FALSE;
  }

  /* System gives the exit code: 1 for a compile error. */
  ok = res == 1 && strstr(out, expected) != NULL;
  if (!ok) "status %d\n%s\n", res, out;

  Free(out);
  CleanupNestedInitTest;
  return ok;
}

Bool TestIssueRepro(U0)
{
  Bar129 b = {1, {2}};
  return b.x == 1 && b.f.a == 2;
}

Bool TestLocalNested(U0)
{
  Bar129 b = {1, {2, 3}, 4};
  return b.x == 1 && b.f.a == 2 && b.f.b == 3 && b.y == 4;
}

Bool TestLocalArrayField(U0)
{
  Arr129 c = {{5, 6, 7}, 8};
  return c.v[0] == 5 && c.v[1] == 6 && c.v[2] == 7 && c.w == 8;
}

Bool TestLocalDeep(U0)
{
  Top129 t = {{{{1, 2}, {3, 4}}, {5, {6, 7}, 8}}, 9};
  return t.d.fs[0].a == 1 && t.d.fs[0].b == 2 &&
         t.d.fs[1].a == 3 && t.d.fs[1].b == 4 &&
         t.d.bar.x == 5 && t.d.bar.f.a == 6 && t.d.bar.f.b == 7 &&
         t.d.bar.y == 8 && t.z == 9;
}

Bool TestLocalArrayOfClasses(U0)
{
  Bar129 bars[2] = {{1, {2, 3}, 4}, {5, {6, 7}, 8}};
  Foo129 foos[2] = {{10, 11}, {12, 13}};
  return bars[0].x == 1 && bars[0].f.a == 2 && bars[0].f.b == 3 &&
         bars[0].y == 4 && bars[1].x == 5 && bars[1].f.a == 6 &&
         bars[1].f.b == 7 && bars[1].y == 8 &&
         foos[0].a == 10 && foos[0].b == 11 &&
         foos[1].a == 12 && foos[1].b == 13;
}

/* Fields of different widths, a string and a float in the nested class */
Bool TestLocalMixed(U0)
{
  Mixed129 m = {-1, {"name", 2.5}, 200, -3};
  return m.a == -1 && !StrCmp(m.n.name, "name") && m.n.weight == 2.5 &&
         m.c == 200 && m.s == -3;
}

Bool TestGlobals(U0)
{
  return g_bar.x == 1 && g_bar.f.a == 2 && g_bar.f.b == 3 && g_bar.y == 4 &&
         g_arr.v[0] == 5 && g_arr.v[1] == 6 && g_arr.v[2] == 7 &&
         g_arr.w == 8 &&
         g_top.d.fs[0].a == 1 && g_top.d.fs[0].b == 2 &&
         g_top.d.fs[1].a == 3 && g_top.d.fs[1].b == 4 &&
         g_top.d.bar.x == 5 && g_top.d.bar.f.a == 6 &&
         g_top.d.bar.f.b == 7 && g_top.d.bar.y == 8 && g_top.z == 9 &&
         g_bars[1].x == 5 && g_bars[1].f.a == 6 && g_bars[1].f.b == 7 &&
         g_bars[1].y == 8 && g_foos[1].a == 12 && g_foos[1].b == 13;
}

Bool TestTooManyInNested(U0)
{
  return CompileFailsWith(
"class Foo { i64 a; i64 b; };
class Bar { i64 x; Foo f; };
void Main() { Bar b = {1, {2, 3, 4}}; }
", "More initialisers than class fields for class: Foo");
}

Bool TestTooManyFields(U0)
{
  return CompileFailsWith(
"class Foo { i64 a; i64 b; };
Foo g = {1, 2, {3}};
void Main() {}
", "More initialisers than class fields for class: Foo");
}

Bool TestScalarField(U0)
{
  return CompileFailsWith(
"class Foo { i64 a; };
class Bar { i64 x; Foo f; };
void Main() { Bar b = {{1}, {2}}; }
", "Cannot use an initialiser list for field x of type i64");
}

Bool TestTooManyInArrayField(U0)
{
  return CompileFailsWith(
"class Arr { i64 v[2]; i64 w; };
Arr g = {{1, 2, 3}, 4};
void Main() {}
", "Invalid array initializer: expected 2 items but got 3");
}

Bool TestScalarElement(U0)
{
  return CompileFailsWith(
"void Main() { i64 a[2] = {{{1}}, {2}}; }
", "Cannot use an initialiser list for an element of type i64");
}

I32 Main()
{
  "Test - Nested class initialiser: \n";
  I64 tests = 12;
  I64 correct = 0;

  if (TestIssueRepro()) correct++;
  else "Failed - TestIssueRepro()\n";

  if (TestLocalNested()) correct++;
  else "Failed - TestLocalNested()\n";

  if (TestLocalArrayField()) correct++;
  else "Failed - TestLocalArrayField()\n";

  if (TestLocalDeep()) correct++;
  else "Failed - TestLocalDeep()\n";

  if (TestLocalArrayOfClasses()) correct++;
  else "Failed - TestLocalArrayOfClasses()\n";

  if (TestLocalMixed()) correct++;
  else "Failed - TestLocalMixed()\n";

  if (TestGlobals()) correct++;
  else "Failed - TestGlobals()\n";

  if (TestTooManyInNested()) correct++;
  else "Failed - TestTooManyInNested()\n";

  if (TestTooManyFields()) correct++;
  else "Failed - TestTooManyFields()\n";

  if (TestScalarField()) correct++;
  else "Failed - TestScalarField()\n";

  if (TestTooManyInArrayField()) correct++;
  else "Failed - TestTooManyInArrayField()\n";

  if (TestScalarElement()) correct++;
  else "Failed - TestScalarElement()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
