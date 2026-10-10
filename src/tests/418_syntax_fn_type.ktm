#include "testhelper.ktm"

/* ktm syntax v0.2: `fn ( [type {, type}] [, ...] ) RetType` is the new way
 * to write a function pointer type: `fn(i64) i64` is exactly the C
 * declarator `i64 (*)(i64)`, same size, same calls, same argument checks.
 * `*` after the whole construct makes a pointer to it, not to RetType.
 * `fn` is a CONTEXTUAL keyword (old code uses it as a plain identifier):
 * it starts a type only where a type can start, and only when followed by
 * `(` with a parameter list that is empty, `...`-only or starts with a
 * type name, and (checked past the `)` only for the empty/`...`-only
 * cases, where the `)` is right there) a type follows the `)`. See
 * parseFnStartsType in src/prslib.c. */

i64 Twice(i64 x) { return x * 2; }
i64 AddOne(i64 x) { return x + 1; }

/* old C declarator, kept working */
i64 (*g_oldOp)(i64) = &Twice;

/* the new form: a variable */
fn(i64) i64 g_newOp = &Twice;

/* an array of them */
fn(i64, u8 *) void g_handlers[4];
U0 Handler0(i64 n, u8 *s) { "h0 %d %s\n", n, s; }

/* a struct field */
struct Server { fn(i32) void onAccept; };
U0 OnAccept(i32 fd) { "accepted %d\n", fd; }

/* a function returning a function pointer */
fn(i64) i64 Pick(Bool sq) {
  if (sq) return &Twice;
  return &AddOne;
}

/* a parameter */
i64 Apply(fn(i64) i64 f, i64 x) { return f(x); }

/* nested: a function taking an fn-type and returning an fn-type */
fn(i64) i64 ComposeImpl(fn(i64) i64 g) { return &Twice; }
fn(fn(i64) i64) fn(i64) i64 g_compose = &ComposeImpl;

/* no parameters */
fn() void g_tick;
U0 Tick() { "tick\n"; }

/* varargs */
fn(u8 *, ...) i32 g_logger;

#define AT_SRC "./ktm-418-fn.ktm"
#define AT_OUT "./ktm-418-fn.out"

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
  "Test - fn(...) function types: ";

  /* a variable of the new form, called */
  tests++;
  if (g_newOp(5) == 10) correct++;
  else "\n  fn(...) variable, called\n";

  /* the old declarator form still works */
  tests++;
  if (g_oldOp(5) == 10) correct++;
  else "\n  old declarator form still works\n";

  /* equality with the old declarator form: no cast either way, same
   * calls, same argument checks (test 413) */
  tests++;
  fn(i64) i64 viaOld = g_oldOp;
  i64 (*viaNew)(i64) = g_newOp;
  if (viaOld(3) == 6 && viaNew(3) == 6 &&
      Apply(g_oldOp, 4) == 8 && Apply(g_newOp, 4) == 8)
    correct++;
  else "\n  fn(...) and the old declarator are the same type\n";

  /* an array of fn(...) */
  tests++;
  g_handlers[0] = &Handler0;
  g_handlers[0](7, "x");
  if (g_handlers[0] == &Handler0) correct++;
  else "\n  array of fn(...)\n";

  /* a struct field of fn(...) type */
  tests++;
  Server srv;
  srv.onAccept = &OnAccept;
  srv.onAccept(9);
  if (srv.onAccept == &OnAccept) correct++;
  else "\n  struct field of fn(...) type\n";

  /* a function returning fn(...), called through the result */
  tests++;
  fn(i64) i64 p1 = Pick(true);
  fn(i64) i64 p2 = Pick(false);
  if (p1(5) == 10 && p2(5) == 6) correct++;
  else "\n  function returning fn(...)\n";

  /* a parameter of fn(...) type */
  tests++;
  if (Apply(&Twice, 6) == 12) correct++;
  else "\n  fn(...) parameter\n";

  /* nested fn(...) types: a parameter and a return type */
  tests++;
  fn(i64) i64 composed = g_compose(&Twice);
  if (composed(5) == 10) correct++;
  else "\n  nested fn(...) types\n";

  /* no parameters */
  tests++;
  g_tick = &Tick;
  g_tick();
  if (g_tick == &Tick) correct++;
  else "\n  fn() with no parameters\n";

  /* varargs */
  tests++;
  if (sizeof(g_logger) == 8) correct++;
  else "\n  fn(..., ...) varargs\n";

  /* pointer to a function pointer */
  tests++;
  fn(i64) i64 *slot = &g_newOp;
  if ((*slot)(8) == 16) correct++;
  else "\n  pointer to a function pointer\n";

  /* a cast */
  tests++;
  auto casted = (fn(i64) i64)g_oldOp;
  if (casted(9) == 18) correct++;
  else "\n  cast to fn(...)\n";

  /* sizeof is 8, comparisons with null work */
  tests++;
  fn(i64) i64 none = null;
  if (sizeof(fn(i64) i64) == 8 && none == null) correct++;
  else "\n  sizeof and null\n";

  /* typeof prints the new form, for both the new and old declarator */
  tests++;
  if (!StrCmp(typeof(g_newOp), "fn(i64) i64") &&
      !StrCmp(typeof(g_oldOp), "fn(i64) i64"))
    correct++;
  else "\n  typeof prints the new form\n";

  /* `fn` stays a plain identifier everywhere it was one before */
  tests++;
  i64 (*fn)(i64) = &Twice;
  fn = &AddOne;
  if (fn(5) == 6) correct++;
  else "\n  fn as a plain identifier (old code)\n";

  /* compile errors */

  tests++;
  if (Fails("fn(i64) x;", "return type")) correct++;
  else "\n  fn(...) with no return type\n";

  tests++;
  if (Fails("fn i64 x;", "`(`")) correct++;
  else "\n  fn without parens\n";

  tests++;
  if (Fails("fn(i64 x;", "`)`")) correct++;
  else "\n  fn(...) missing a closing paren\n";

  /* an fn(...) parameter is checked like any function pointer parameter
   * (test 413): an integer argument warns, same as the old declarator
   * form would, not a hard error */
  tests++;
  {
    U8 *src = MStrPrint(
      "i32 Take(fn(i64) i64 f) { return 0; }\n"
      "i32 g() { return Take(2); }\n"
      "i32 main() { return g(); }\n");
    U8 *out = Diagnose(src);
    if (out && strstr(out, "warning:") &&
        strstr(out, "makes a pointer from an integer without a cast"))
      correct++;
    else "\n  an integer argument through an fn(...) parameter\n%s\n", out;
    Free(out);
    Free(src);
  }

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
