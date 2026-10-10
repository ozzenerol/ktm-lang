#include "testhelper.ktm"

/* fn(...) types, three gaps:
 * - a `*` after the return type is part of it: `fn(u8 *) u8 *` returns
 *   a u8 *. Before, it made a pointer to the function pointer, so a
 *   function returning a pointer could not be written in the fn form.
 *   A pointer to a function pointer now puts the fn type in
 *   parentheses, `(fn(i64) i64) *slot`, in declarations, parameters,
 *   fields, casts, sizeof and typeof, which also print it that way.
 * - parameters can have defaults, `fn(i64 a, i64 b = 10) i64`, filled
 *   in by a call through it as for `i64 (*f)(i64 a, i64 b = 10)`.
 * - `...name` as the first parameter (`fn(...args) i64`) is a type at
 *   the start of a statement, in if/for initialisers, sizeof and
 *   typeof; it was taken for a call. */

#define AT_SRC "./ktm-433-fnret.ktm"
#define AT_OUT "./ktm-433-fnret.out"

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

/* ktm's output for body has want in it */
Bool Prints(U8 *body, U8 *want)
{
  U8 *src = MStrPrint(
"i64 t(i64 x) { return x; }
fn(i64) i64 op = &t;
%s
i32 main() { return 0; }
", body);
  U8 *out = Diagnose(src);
  Bool ok = out && strstr(out, want) != NULL;
  if (!ok) "\n%s\n----\n%s\n", body, out;
  Free(out);
  Free(src);
  return ok;
}

u8 *Dup(u8 *s) { return s + 1; }
void *Work(void *a) { return a; }
i64 Twice(i64 x) { return x * 2; }
i64 Add(i64 a, i64 b) { return a + b; }
i64 SumN(...args)
{
  i64 t = 0;
  for (i64 i = 0; i < args.len; i++) t += args[i];
  return t;
}
i64 SumT(i64 ...nums)
{
  i64 t = 0;
  for (auto n : nums) t += n;
  return t;
}
i32 Count(u8 *f, ...args) { return args.len; }
i64 Va(...) { return argc; }

/* return types, pointers to function pointers */
fn(u8 *) u8 * g_dup = &Dup;
fn(i64) i64 g_op = &Twice;
(fn(i64) i64) *g_slot = &g_op;
class Box { fn(void *) void * w; (fn(i64) i64) *slot; fn(i64 a, i64 b = 10) i64 k; };
i64 CallSlot((fn(i64) i64) *s, i64 x) { return (*s)(x); }
fn(u8 *) u8 * PickDup() { return &Dup; }

/* defaults */
fn(i64 a, i64 b = 10) i64 g_k = &Add;
i64 CallK(fn(i64, i64 = 100) i64 k, i64 x) { return k(x); }

/* a parameter named fn is still called, varargs passed on */
i64 Fwd(fn(...args) i64 fn, ...args) { return fn(...args); }

I32 Main()
{
  "Test - fn types: return pointers, defaults, ...name: ";
  I64 correct = 0, tests = 0;

  /* a `*` after the return type is part of it */
  fn(u8 *) u8 * d = &Dup;
  fn(void *) void * w = &Work;
  tests++; if (!StrCmp(d("xab"), "ab") && !StrCmp(g_dup("xy"), "y")) correct++;
  tests++; if (w(7(void *))(i64) == 7 && PickDup()("zq")[0] == 'q') correct++;

  /* (fn(...) R) * is a pointer to a function pointer */
  (fn(i64) i64) *slot = &g_op;
  Box b;
  b.w = &Work;
  b.slot = &g_op;
  b.k = &Add;
  tests++;
  if ((*slot)(4) == 8 && (*g_slot)(5) == 10 && CallSlot(slot, 6) == 12 &&
      (*b.slot)(7) == 14)
    correct++;
  void *raw = slot;
  tests++;
  if ((*raw((fn(i64) i64) *))(9) == 18 && (*((fn(i64) i64) *)raw)(10) == 20)
    correct++;
  tests++;
  if (sizeof((fn(i64) i64) *) == 8 && sizeof(fn(u8 *) u8 *) == 8 && sizeof(Box) == 24)
    correct++;

  /* typeof prints them as written */
  tests++;
  if (!StrCmp(typeof(d), "fn(u8*) u8 *") && !StrCmp(typeof(slot), "(fn(i64) i64) *") &&
      !StrCmp(typeof(b.slot), "(fn(i64) i64) *") && !StrCmp(typeof(g_op), "fn(i64) i64"))
    correct++;
  else "\n  %s | %s | %s\n", typeof(d), typeof(slot), typeof(b.slot);

  /* defaults: local, global, field, parameter */
  fn(i64 a, i64 b = 10) i64 k = &Add;
  tests++;
  if (k(1) == 11 && k(1, 2) == 3 && g_k(2) == 12 && b.k(3) == 13 && CallK(&Add, 4) == 104)
    correct++;
  tests++;
  if (!StrCmp(typeof(k), "fn(i64, i64) i64")) correct++;
  else "\n  %s\n", typeof(k);

  /* varargs, named or not, typed or not, first or not */
  fn(...) i64 va = &Va;
  fn(...args) i64 sn = &SumN;
  fn(i64 ...) i64 st = &SumT;
  fn(i64 ...nums) i64 st2 = &SumT;
  fn(u8 *, ...args) i32 cnt = &Count;
  tests++;
  if (va(1, 2) == 2 && sn(3, 4) == 7 && st(5, 6) == 11 && st2(7, 8) == 15 &&
      cnt("x", 1, 2, 3) == 3 && Fwd(&SumN, 1, 2, 3) == 6)
    correct++;
  I64 seen = 0;
  if (fn(...args) i64 q = &SumN; q(1) == 1) seen++;
  for (fn(i64 ...nums) i64 r = &SumT; r(2) == 2; ) { seen++; break; }
  tests++; if (seen == 2) correct++;
  tests++;
  if (sizeof(fn(...args) i64) == 8 && sizeof(fn(u8 *, ...args) i32) == 8 &&
      !StrCmp(typeof(fn(...args) i64), "fn(...args) i64") &&
      !StrCmp(typeof(fn(u8 *, ...args) i32), "fn(u8*, ...args) i32") &&
      !StrCmp(typeof(fn(i64 ...nums) i64), "fn(i64 ...) i64") &&
      !StrCmp(typeof(fn(...) i64), "fn(...) i64"))
    correct++;

  /* the `*` belongs to the return type: a function pointer is not a
   * function returning an i64 *, nor a pointer to one */
  tests++;
  if (Prints("fn(i64) i64 *bad = &t;", "cannot convert fn(i64) i64 to fn(i64) i64 *"))
    correct++;
  tests++;
  if (Prints("(fn(u8 *) i64) *bad = &op;",
             "cannot convert (fn(i64) i64) * to (fn(u8*) i64) *"))
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
