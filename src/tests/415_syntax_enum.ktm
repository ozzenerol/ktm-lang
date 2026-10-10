#include "testhelper.ktm"

/* ktm syntax v0.2: scoped enums. `enum Name [: IntType] { member [=
 * constexpr], ... };` at file scope. Members are integer constants, only
 * reachable as `Name.member`; the underlying type defaults to i32 and
 * sizeof/alignof follow it; an enum value converts to any integer type
 * implicitly, but an integer (or another enum) needs a cast to become one. */

enum Color { red, green, blue };
enum Status : u16 { ok = 200, notFound = 404, gone };
pub enum Mode { read = 1, write = 2, both = read | write };

struct Pixel { Color c; };

Color NextColor(Color c) { return (Color)((c + 1) % 3); }

Color g_colors[2] = {Color.green, Color.blue};

#define AT_SRC "./ktm-415-enum.ktm"
#define AT_OUT "./ktm-415-enum.out"

/* Compile `source`, which must FAIL to compile, and return ktm's output. */
U8 *Diagnose(U8 *source)
{
  System("rm -f " AT_SRC " " AT_OUT);
  FileWrite(AT_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " AT_SRC " -o /dev/null > " AT_OUT " 2>&1");
  U8 *out = FileRead(AT_OUT);
  System("rm -f " AT_SRC " " AT_OUT);
  return out;
}

/* `body`, wrapped as a whole program, must fail to compile with `want`
 * somewhere in ktm's output. */
Bool Fails(U8 *body, U8 *want)
{
  U8 *src = MStrPrint("%s\ni32 main() { return 0; }\n", body);
  U8 *out = Diagnose(src);
  Bool ok = out && strstr(out, "error:") && strstr(out, want);
  if (!ok) "\n%s\n----\n%s\n", body, out;
  Free(out);
  Free(src);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - scoped enums: ";

  /* default, explicit and mixed values; `: u16`; bitwise-or of earlier
   * members used unqualified inside the braces */
  tests++;
  if (Color.red == 0 && Color.green == 1 && Color.blue == 2 &&
      Status.ok == 200 && Status.notFound == 404 && Status.gone == 405 &&
      Mode.read == 1 && Mode.write == 2 && Mode.both == 3)
    correct++;
  else "\n  enum member values\n";

  /* sizeof/alignof follow the underlying type; typeof names the enum */
  tests++;
  Color c = Color.green;
  if (sizeof(Color) == 4 && sizeof(Status) == 2 &&
      alignof(Status) == 2 && !StrCmp(typeof(c), "Color"))
    correct++;
  else "\n  sizeof/alignof/typeof\n";

  /* switch on an enum, with enum case labels */
  tests++;
  i64 hit = -1;
  switch (c) {
    case Color.red:   hit = 0; break;
    case Color.green: hit = 1; break;
    case Color.blue:  hit = 2; break;
  }
  if (hit == 1) correct++;
  else "\n  switch on an enum\n";

  /* an enum member is a compile-time constant: array size */
  tests++;
  i64 sizes[Color.blue + 1];
  i64 n_sizes = 0;
  for (i64 i = 0; i < Color.blue + 1; i++) { sizes[i] = i; n_sizes++; }
  if (n_sizes == 3 && sizes[Color.blue] == 2) correct++;
  else "\n  enum member as an array size\n";

  /* a struct field of enum type */
  tests++;
  Pixel p;
  p.c = Color.red;
  if (p.c == Color.red) correct++;
  else "\n  struct field of enum type\n";

  /* a parameter and a return value of enum type */
  tests++;
  if (NextColor(Color.red) == Color.green &&
      NextColor(Color.blue) == Color.red)
    correct++;
  else "\n  enum parameter and return value\n";

  /* an integer needs a cast to become an enum */
  tests++;
  Color d = (Color)2;
  if (d == Color.blue) correct++;
  else "\n  cast from an integer to an enum\n";

  /* an enum converts to any integer type implicitly; arithmetic and
   * comparisons with integers work */
  tests++;
  i32 asInt = c;
  i64 plusOne = c + 1;
  if (asInt == 1 && plusOne == 2 && c < Color.blue && c != Color.red)
    correct++;
  else "\n  enum -> integer, implicit\n";

  /* initialiser lists, local and global, same as an i32 array would */
  tests++;
  Color arr[3] = {Color.red, Color.green, Color.blue};
  if (arr[0] == Color.red && arr[1] == Color.green && arr[2] == Color.blue &&
      g_colors[0] == Color.green && g_colors[1] == Color.blue)
    correct++;
  else "\n  initialiser list of enum elements\n";

  /* a ternary with both branches the same enum keeps that enum's type;
   * mixed enum/integer branches give an integer, like other arithmetic */
  tests++;
  Color t1 = n_sizes > 0 ? Color.blue : Color.red;
  i64 t2 = n_sizes > 0 ? Color.blue : 7;
  if (t1 == Color.blue && !StrCmp(typeof(t1), "Color") && t2 == 2)
    correct++;
  else "\n  ternary with enum branches\n";

  /* plain assignment and a cast-reassignment stay fine; ++, -- and a
   * compound assignment on an enum are errors (checked below) */
  tests++;
  c = Color.green;
  c = (Color)(c + 1);
  if (c == Color.blue) correct++;
  else "\n  plain enum assignment and cast-reassignment\n";

  /* compile errors */

  tests++;
  if (Fails("enum Empty { };", "has no members")) correct++;
  else "\n  empty enum\n";

  tests++;
  if (Fails("enum Dup { a, b, a };", "already has a member")) correct++;
  else "\n  duplicate enum member\n";

  tests++;
  if (Fails("enum Small : u8 { big = 300 };", "does not fit")) correct++;
  else "\n  enum member value too large for its type\n";

  tests++;
  if (Fails("enum C415a { x, y };\ni32 g() { return C415a.z; }",
            "no member"))
    correct++;
  else "\n  unknown enum member\n";

  tests++;
  if (Fails("enum C415b { x, y };\ni32 g() { C415b v = 1; return v; }",
            "Cannot convert `i64` to enum `C415b` without a cast"))
    correct++;
  else "\n  integer to enum needs a cast\n";

  tests++;
  if (Fails("enum C415c { x, y };\ni32 g(C415c v) { return v; }\n"
            "i32 h() { return g(1); }",
            "Cannot convert `i64` to enum `C415c` without a cast"))
    correct++;
  else "\n  integer argument to an enum parameter needs a cast\n";

  tests++;
  if (Fails("enum C415d { x, y };\nenum C415e { p, q };\n"
            "i32 g() { C415d v = C415e.p; return v; }",
            "Cannot convert `C415e` to enum `C415d` without a cast"))
    correct++;
  else "\n  one enum assigned to another needs a cast\n";

  /* returning an integer from a function returning an enum: an error,
   * same as assignment */
  tests++;
  if (Fails("enum C415f { x, y };\nC415f g() { return 2; }",
            "Cannot convert `i64` to enum `C415f` without a cast"))
    correct++;
  else "\n  integer return value needs a cast\n";

  /* two different enums comparing against each other */
  tests++;
  if (Fails("enum C415g { x, y };\nenum C415h { p, q };\n"
            "i32 g() { C415g a = C415g.x; C415h b = C415h.p; "
            "if (a == b) return 1; return 0; }",
            "Cannot compare enum `C415g` with enum `C415h`"))
    correct++;
  else "\n  comparing two different enums\n";

  /* a real class mismatch still gets the old, generic message, not an
   * enum-flavoured one */
  tests++;
  if (Fails("class C415i { i64 x; };\nclass C415j { i64 x; };\n"
            "i32 take(C415i a) { return a.x; }\n"
            "i32 g() { C415j b; return take(b); }",
            "Incompatible function argument, expected 'C415i' got 'C415j'"))
    correct++;
  else "\n  a real class mismatch keeps its own message\n";

  /* enum E {}; struct E {}; (and the reverse) both redefine the name */
  tests++;
  if (Fails("enum C415k { x };\nstruct C415k { i64 v; };",
            "`C415k` has already been defined"))
    correct++;
  else "\n  struct redefining an enum name\n";

  tests++;
  if (Fails("struct C415l { i64 v; };\nenum C415l { x };",
            "`C415l` has already been defined"))
    correct++;
  else "\n  enum redefining a struct name\n";

  /* ++, --, and a compound assignment on an enum are errors, like
   * C++'s `enum class` */
  tests++;
  if (Fails("enum C415m { x, y };\n"
            "i32 g() { C415m v = C415m.x; v++; return 0; }",
            "Cannot do arithmetic assignment on enum `C415m`"))
    correct++;
  else "\n  ++ on an enum\n";

  tests++;
  if (Fails("enum C415n { x, y };\n"
            "i32 g() { C415n v = C415n.x; --v; return 0; }",
            "Cannot do arithmetic assignment on enum `C415n`"))
    correct++;
  else "\n  -- on an enum\n";

  tests++;
  if (Fails("enum C415o { x, y };\n"
            "i32 g() { C415o v = C415o.x; v += 1; return 0; }",
            "Cannot do arithmetic assignment on enum `C415o`"))
    correct++;
  else "\n  += on an enum\n";

  /* assigning to a member itself: `Name.member = x` */
  tests++;
  if (Fails("enum C415p { x, y };\ni32 g() { C415p.x = 1; return 0; }",
            "Cannot assign to enum member `C415p.x`"))
    correct++;
  else "\n  assigning to an enum member\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
