#include "testhelper.ktm"

/* A value converted to Bool kept only its low byte: 0x100, 0x800 and 0.5
 * became FALSE, -1 stayed -1 and a pointer gave its low byte. As in C it
 * is now 0 or 1 (value != 0) for arguments, assignments, initialisers,
 * return values, class fields, casts, default arguments and ++/--. Bool
 * stays one byte. Library code that kept other values in a Bool (Rm's and
 * RmDir's flags, JsonParseBool's -1 for an error) uses an I64 now. */

class C
{
  U8 a;
  Bool b;
  U8 c;
};

Bool g_int = 0x100;
Bool g_flt = 0.5;

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

/* The byte as stored: exactly 0 or 1 */
Bool Is(Bool b, I64 want)
{
  return (&b)(U8 *)[0] == want;
}

I64 Arg(Bool b) { return (&b)(U8 *)[0]; }
I64 DefArg(Bool b = 0x800) { return (&b)(U8 *)[0]; }
Bool RetInt(I64 x) { return x; }
Bool RetU64(U64 x) { return x; }
Bool RetFlt(F64 x) { return x; }
Bool RetPtr(U8 *x) { return x; }

U0 Args(U8 *p)
{
  U64 h = 0x100, big = 0x800;
  I64 m = -1;
  F64 f = 0.5;
  F32 f32 = 0.25;
  I64 (*fp)(Bool) = &Arg;
  Check(Arg(h) == 1, "arg 0x100");
  Check(Arg(big) == 1, "arg 0x800");
  Check(Arg(m) == 1, "arg -1");
  Check(Arg(f) == 1, "arg 0.5");
  Check(Arg(f32) == 1, "arg F32 0.25");
  Check(Arg(p) == 1, "arg pointer");
  Check(Arg(0) == 0 && Arg(0.0) == 0 && Arg(NULL) == 0, "arg 0");
  Check(Arg(0x100) == 1 && Arg(0.5) == 1, "arg constants");
  Check(fp(big) == 1, "arg through a function pointer");
  Check(DefArg() == 1, "default 0x800, called with ()");
  Check(DefArg == 1, "default 0x800, called without ()");
  Check(DefArg(0x200) == 1, "0x200 for a defaulted Bool");
}

U0 Assign(U8 *p)
{
  U64 h = 0x100, big = 0x800;
  I64 m = -1;
  F64 f = 0.5, nz = -0.0, nan = 0.0 / 0.0;
  Bool b, z = 0, *bp = &z;

  b = h;   Check(Is(b, 1), "assign 0x100");
  b = big; Check(Is(b, 1), "assign 0x800");
  b = m;   Check(Is(b, 1), "assign -1");
  b = f;   Check(Is(b, 1), "assign 0.5");
  b = p;   Check(Is(b, 1), "assign pointer");
  b = 0;   Check(Is(b, 0), "assign 0");
  b = nz;  Check(Is(b, 0), "assign -0.0");
  b = nan; Check(Is(b, 1), "assign NaN");
  Check((b = 0x800) == 1, "the assignment's value is 1");
  *bp = 0x400; Check(Is(z, 1), "store through a Bool *");

  Bool i1 = h, i2 = f, i3 = p, i4 = -1;
  Check(Is(i1, 1) && Is(i2, 1) && Is(i3, 1) && Is(i4, 1), "initialisers");
  Check(Is(g_int, 1) && Is(g_flt, 1), "global initialisers");

  b = 0; b |= 0x100; Check(Is(b, 1), "|= 0x100");
  b = 1; b += 1;     Check(Is(b, 1), "+= 1");
  b = 1; b++;        Check(Is(b, 1), "++ on TRUE");
  b = 0; ++b;        Check(Is(b, 1), "++ on FALSE");
  b = 1; b--;        Check(Is(b, 0), "-- on TRUE");
  b = 0; b--;        Check(Is(b, 1), "-- on FALSE");
}

U0 Fields(U8 *p)
{
  U64 big = 0x800;
  C c;
  C d = {7, 0x200, 9};
  Bool arr[4] = {0x100, 0, -1, 0.5};

  Check(sizeof(Bool) == 1 && sizeof(C) == 3, "Bool is one byte");
  Check((&c.b)(U8 *) - (&c)(U8 *) == 1 && (&c.c)(U8 *) - (&c)(U8 *) == 2,
      "field offsets");
  c.a = 7; c.c = 9;
  c.b = big; Check(Is(c.b, 1) && c.a == 7 && c.c == 9, "field 0x800");
  c.b = p;   Check(Is(c.b, 1) && c.a == 7 && c.c == 9, "field pointer");
  Check(Is(d.b, 1) && d.a == 7 && d.c == 9, "class initialiser");
  Check(Is(arr[0], 1) && Is(arr[1], 0) && Is(arr[2], 1) && Is(arr[3], 1),
      "array initialiser");
}

U0 Returns(U8 *p)
{
  Check(Is(RetInt(0x100), 1), "return 0x100");
  Check(Is(RetU64(0x800), 1), "return 0x800");
  Check(Is(RetInt(-1), 1), "return -1");
  Check(Is(RetFlt(0.5), 1), "return 0.5");
  Check(Is(RetPtr(p), 1), "return a pointer");
  Check(Is(RetInt(0), 0) && Is(RetPtr(NULL), 0), "return 0");
}

U0 Casts(U8 *p)
{
  U64 big = 0x800;
  I64 m = -1;
  F64 f = 0.5;
  Bool b;
  b = big(Bool); Check(Is(b, 1), "cast 0x800");
  b = m(Bool);   Check(Is(b, 1), "cast -1");
  b = f(Bool);   Check(Is(b, 1), "cast 0.5");
  b = p(Bool);   Check(Is(b, 1), "cast pointer");
  /* Constant casts, folded by the compiler */
  b = 0x100(Bool); Check(Is(b, 1), "constant cast 0x100");
  b = 0.5(Bool);   Check(Is(b, 1), "constant cast 0.5");
  Check(0x100(Bool) == 1 && (-1)(Bool) == 1 && 0(Bool) == 0,
      "constant casts in expressions");
}

extern "c" I32 access(U8 *path, I32 mode);
Bool Exists(U8 *path) { return access(path, 0) == 0; }

I64 Narrow8(I8 x) { return x; }
I64 NarrowU8(U8 x) { return x; }

U0 Library()
{
  /* The other byte types still keep the low byte */
  I8 c = 0x181;
  Check(Narrow8(0x181) == -127 && NarrowU8(0x1FF) == 255 && c == -127,
      "I8 and U8 still truncate");

  /* RM_VERBOSE alone must not act like RM_RECURSE (2 as a Bool is 1) */
  MkDir("./ktm-388/sub", MKDIR_DEFAULT, TRUE);
  FileWrite("./ktm-388/sub/f", "x", 1);
  Check(RmDir("./ktm-388", RM_VERBOSE) != 0 && Exists("./ktm-388/sub/f"),
      "RmDir with RM_VERBOSE doesn't recurse");
  Check(RmDir("./ktm-388", RM_RECURSE) == 0 && !Exists("./ktm-388"),
      "RmDir with RM_RECURSE");

  /* JsonParseBool reports a bad literal (it returned -1 through a Bool) */
  Json *j = JsonParse("[trux]");
  Check(!JsonOk(j) && j->state->error == JSON_INVALID_LITERAL,
      "[trux] is JSON_INVALID_LITERAL");
  JsonRelease(j);
}

I32 Main()
{
  U8 *p = "x";
  "Test - conversions to Bool: ";
  Args(p);
  Assign(p);
  Fields(p);
  Returns(p);
  Casts(p);
  Library;
  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
