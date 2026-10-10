#include "testhelper.ktm"

/* ktm syntax v0.2: named variadic parameters. `T ...name` takes zero or
 * more extra arguments, each checked and converted like an argument of
 * type T; `...name` takes them untyped, as 64-bit slots like the old
 * argv. Inside, `name.len` is the count, `name[i]` element i (of type
 * T, `&name[i]` works) and `for (auto v : name)` iterates; `name` can't be
 * assigned. `...name` as a call's extra argument passes them on. The
 * function has no implicit argc/argv. The old `(...)` keeps working. */

i64 Sum(i64 ...nums)
{
  i64 t = 0;
  for (auto n : nums) t += n;
  return t;
}

f64 Avg(f64 ...xs)
{
  if (!xs.len) return 0;
  f64 t = 0;
  for (i64 i = 0; i < xs.len; i++) t += xs[i];
  return t / xs.len;
}

i64 TotalLen(u8 *...strs)
{
  i64 t = 0;
  for (auto s : strs) t += StrLen(s);
  return t;
}

i64 Narrow(i32 ...v)
{
  i64 t = 0;
  for (auto x : v) t += x;
  return t;
}

f64 Floats(f32 ...v)
{
  f64 t = 0;
  for (auto x : v) t += x;
  return t;
}

/* untyped: the slots of the old argv, read with a postfix cast */
U8 g_buf[128];
i64 Untyped(u8 *tag, ...args)
{
  StrPrint(g_buf, "%s:%d", tag, args.len);
  if (args.len >= 3) {
    StrPrint(g_buf + StrLen(g_buf), ":%d:%.2f:%s", args[0], args[1](F64), args[2](u8 *));
  }
  return args.len;
}

/* a fixed and a default parameter before */
i64 WithDefault(i64 a, i64 b = 10, i64 ...rest)
{
  return a + b + Sum(...rest) * 100 + rest.len * 10000;
}

/* spread */
i64 Total(i64 ...nums)
{
  return Sum(...nums);
}

/* &name[i] and writing an element */
i64 Doubled(i64 ...nums)
{
  for (i64 i = 0; i < nums.len; i++) {
    i64 *p = &nums[i];
    *p *= 2;
  }
  return Sum(...nums);
}

/* recursion: each call has its own extras */
i64 Depth(i64 n, i64 ...extra)
{
  if (n == 0) return Sum(...extra);
  return Depth(n - 1, n, n * 10) + extra.len;
}

/* no implicit argc / argv: a parameter of that name is not shadowed */
i64 ArgcParam(i64 argc, i64 ...rest)
{
  return argc * 100 + rest.len;
}

#define VA_SRC "./ktm-420-varargs.ktm"
#define VA_OUT "./ktm-420-varargs.out"

/* `source` fails to compile with exactly one error, `want` */
Bool Fails(U8 *source, U8 *want)
{
  System("rm -f " VA_SRC " " VA_OUT);
  FileWrite(VA_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " VA_SRC " -o /dev/null > " VA_OUT " 2>&1");
  U8 *out = FileRead(VA_OUT);
  System("rm -f " VA_SRC " " VA_OUT);
  I64 errors = 0;
  U8 *p = out;
  while (p && (p = strstr(p, "error:"))) {
    errors++;
    p++;
  }
  Bool ok = out && errors == 1 && strstr(out, want);
  if (!ok) "\n%s\n----\n%s\n", source, out;
  Free(out);
  return ok;
}

/* a call at file scope */
i64 g_file_scope = Sum(4, 5, 6);

I32 Main()
{
  "Test - syntax: named varargs: ";
  I64 correct = 0, tests = 0;

  /* zero, one and many */
  tests++; if (Sum() == 0 && Sum(7) == 7 && Sum(1, 2, 3, 4) == 10) correct++;
  /* typed: converted to the element type */
  tests++; if (Avg() == 0 && Avg(1, 2.5, 3) == 6.5 / 3) correct++;
  tests++; if (Sum(1.9, 2) == 3) correct++;
  tests++; if (TotalLen("ab", "cde", "") == 5 && TotalLen() == 0) correct++;
  tests++; if (Narrow(1, 2, -3, 100) == 100) correct++;
  tests++; if (Floats(1.5, 2, 0.25) == 3.75) correct++;
  /* untyped */
  tests++;
  if (Untyped("t") == 0 && !StrCmp(g_buf, "t:0") &&
      Untyped("u", 42, 2.5, "s") == 3 && !StrCmp(g_buf, "u:3:42:2.50:s"))
    correct++;
  /* a default before the extras */
  tests++;
  if (WithDefault(1) == 11 && WithDefault(1, 2) == 3 &&
      WithDefault(1, 2, 3, 4) == 1 + 2 + 700 + 20000)
    correct++;
  /* spread, nested calls, an element written through &name[i] */
  tests++; if (Total() == 0 && Total(4, 5, 6) == 15) correct++;
  tests++; if (Sum(Sum(1, 2), Sum(), Sum(3)) == 6) correct++;
  tests++; if (Doubled(1, 2, 3) == 12) correct++;
  tests++; if (Depth(3) == 15) correct++;
  tests++; if (ArgcParam(3, 9, 9) == 302) correct++;
  /* through a function pointer */
  i64 (*fp)(i64 ...n) = &Sum;
  tests++; if (fp() == 0 && fp(10, 20, 30) == 60) correct++;
  /* in a loop: the call site's array is reused each time */
  i64 acc = 0;
  for (i64 k = 0; k < 5; k++) acc += Sum(k, k, k);
  tests++; if (acc == 30) correct++;
  tests++; if (g_file_scope == 15) correct++;
  /* the old form is unchanged */
  tests++; if (!StrCmp(MStrPrint("%d-%s", 5, "x"), "5-x")) correct++;

  /* compile errors */
  tests++;
  if (Fails("i64 F(i64 ...n, i64 x) { return 0; }\n",
            "`...n` must be the last parameter")) correct++;
  tests++;
  if (Fails("i64 F(i64 ...) { return 0; }\n",
            "a variadic parameter needs a name")) correct++;
  tests++;
  if (Fails("i64 S(i64 ...n) { return 0; }\ni64 G() { return S(1, \"x\"); }\n",
            "argument 2 of `S`: expected i64, got u8[2]")) correct++;
  tests++;
  if (Fails("i64 S(i64 ...n) { return 0; }\ni64 G(f64 ...m) { return S(...m); }\n",
            "cannot spread `...m` (f64) into `S`, whose extra arguments are i64")) correct++;
  tests++;
  if (Fails("i64 S(i64 ...n) { n = NULL; return 0; }\n",
            "cannot assign to the variadic parameter 'n'")) correct++;
  tests++;
  if (Fails("class P { i64 a; };\ni64 S(P ...n) { return 0; }\n",
            "the elements of `...n` must be numbers or pointers")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
