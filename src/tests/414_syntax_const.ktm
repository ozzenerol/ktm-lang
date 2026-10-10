#include "testhelper.ktm"

/* ktm syntax v0.2: `const` marks a DECLARATION read-only, it is not a
 * type qualifier like C's. An initializer is required (except on
 * parameters). A const of integer type whose initializer is an integer
 * constant expression is itself a compile-time constant: usable in
 * array sizes, case labels and other const initializers. Assigning to
 * a const (`=`, a compound assignment, `++`/`--`), including a const
 * parameter, is a compile error, and so is `const` anywhere but a
 * declaration (a cast, a struct field, a return type, after the type). */

const i64 MAX_USERS = 4;
const u8 *VERSION = "0.2.0";
pub const f64 PI = 3.14159;
const i64 A = 10;
const i64 B = A * 2;        /* chain: a const built from another const */

i64 g_users[MAX_USERS];     /* array size from a const */

i64 TakeConst(const i64 n) {
  return n + 1;             /* reading a const param works */
}

i64 ClassifyUser(i64 n) {
  switch (n) {
    case MAX_USERS: return 1;   /* case label from a const */
    default:        return 0;
  }
}

i64 GetTwo() { return 2; }

#define DIAG_SRC "./ktm-414-const.ktm"
#define DIAG_OUT "./ktm-414-const.out"

U8 *Diagnose(U8 *source)
{
  System("rm -f " DIAG_SRC " " DIAG_OUT);
  FileWrite(DIAG_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " DIAG_SRC " -o /dev/null > " DIAG_OUT " 2>&1");
  U8 *out = FileRead(DIAG_OUT);
  System("rm -f " DIAG_SRC " " DIAG_OUT);
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

/* ktm exits non-zero, reports exactly `errors` "error:"s and the
 * text `want` appears `wants` times. */
Bool Fails(U8 *source, I64 errors, U8 *want, I64 wants)
{
  U8 *out = Diagnose(source);
  Bool ok = out && Count(out, "error:") == errors && Count(out, want) == wants;
  if (!ok) "\n%s\n----\n%s\n", source, out;
  Free(out);
  return ok;
}

I32 Main()
{
  "Test - syntax: const: ";
  I64 correct = 0, tests = 0;

  /* values: plain, pointer, pub, chain */
  tests++; if (MAX_USERS == 4) correct++;
  tests++; if (!StrCmp(VERSION, "0.2.0")) correct++;
  tests++; if (PI > 3.14 && PI < 3.15) correct++;
  tests++; if (B == 20) correct++;

  /* array size from a const */
  tests++; if (sizeof(g_users) == MAX_USERS * sizeof(i64)) correct++;

  /* case label from a const */
  tests++; if (ClassifyUser(4) == 1 && ClassifyUser(5) == 0) correct++;

  /* const param: reading it works */
  tests++; if (TakeConst(9) == 10) correct++;

  /* local const: any initializer, read-only afterwards */
  {
    const i64 n = GetTwo() * 2;
    tests++; if (n == 4) correct++;
  }

  /* rule 1: const needs an initializer */
  tests++;
  if (Fails("const i64 X;\nI32 main() { return 0; }\n",
            1, "const 'X' needs an initializer", 1))
    correct++;

  /* rule 1: a const param does not need one (no error at all) */
  tests++;
  if (Fails("i64 f(const i64 n) { return n; }\nI32 main() { return f(1); }\n",
            0, "error:", 0))
    correct++;

  /* rule 2: every assignment form to a const global */
  tests++;
  if (Fails(
"const i64 X = 1;
U0 f() {
  X = 2; X += 1; X -= 1; X *= 2; X /= 2; X %= 2;
  X <<= 1; X >>= 1; X &= 1; X ^= 1; X |= 1;
  X++; ++X; X--; --X;
}
I32 main() { return 0; }
", 15, "cannot assign to const 'X'", 15))
    correct++;

  /* rule 2: assigning to a const parameter */
  tests++;
  if (Fails("U0 f(const i64 n) { n = 1; }\nI32 main() { return 0; }\n",
            1, "cannot assign to const 'n'", 1))
    correct++;

  /* rule 4: const on a cast */
  tests++;
  if (Fails("I32 main() { i64 x = (const i64)1; return 0; }\n",
            1, "const is only allowed on declarations", 1))
    correct++;

  /* rule 4: const on a struct field */
  tests++;
  if (Fails("class C { const i64 x; };\nI32 main() { return 0; }\n",
            1, "const is only allowed on declarations", 1))
    correct++;

  /* rule 4: const on a function's return type */
  tests++;
  if (Fails("const i64 f() { return 1; }\nI32 main() { return 0; }\n",
            1, "const is only allowed on declarations", 1))
    correct++;

  /* rule 4: const after the type */
  tests++;
  if (Fails("i64 const x = 1;\nI32 main() { return 0; }\n",
            1, "const is only allowed on declarations", 1))
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
