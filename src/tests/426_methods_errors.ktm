#include "testhelper.ktm"

/* Methods (Y10): the compile errors. A method and a field can't share a
 * name, a method is defined once, only on a struct or union, only by
 * the code that defines the type (not on a stdlib type from a program),
 * `self` is const and can't be redeclared, a method is called (or its
 * address taken with `&User.name`), `.` reaches through one pointer only
 * (`pp.x` on a `T **` is an error), and -transpile doesn't support them. */

#define M_SRC "./ktm-426-methods.ktm"
#define M_OUT "./ktm-426-methods.out"

/* `source` compiled with `flags` fails with exactly one error, `want` */
Bool Fails(U8 *source, U8 *want, U8 *flags = "-S")
{
  System("rm -f " M_SRC " " M_OUT);
  FileWrite(M_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  U8 *cmd = MStrPrint("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                      "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                      "%s " M_SRC " -o /dev/null > " M_OUT " 2>&1", flags);
  System(cmd);
  Free(cmd);
  U8 *out = FileRead(M_OUT);
  System("rm -f " M_SRC " " M_OUT);
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

#define USER "struct User { u8 *name; i64 age; };\n"

i32 Main()
{
  "Test - methods: errors: ";
  i64 correct = 0, tests = 0;

  tests++;
  if (Fails(USER "void User.age() {}\n",
            "`User` has a field named `age`: a method cannot have the same name")) correct++;
  tests++;
  if (Fails("struct B { i64 x; };\nstruct D : B { i64 y; };\nvoid D.x() {}\n",
            "`D` has a field named `x`")) correct++;
  tests++;
  if (Fails(USER "void User.f() {}\nvoid User.f() {}\n",
            "Cannot redefine function: User.f")) correct++;
  tests++;
  if (Fails("void i64.f() {}\n",
            "`i64` is not a struct: only a struct or union can have methods")) correct++;
  tests++;
  if (Fails("void Nope.f() {}\n", "`Nope` is not a struct")) correct++;
  tests++;
  if (Fails("void StrMap.count() {}\n",
            "methods of `StrMap` can only be defined by the standard library")) correct++;
  tests++;
  if (Fails(USER "void User.f() { self = NULL; }\n",
            "cannot assign to const 'self'")) correct++;
  tests++;
  if (Fails(USER "void User.f(i64 self) {}\n", "variable self already declared")) correct++;
  tests++;
  if (Fails(USER "void User.f() {}\nvoid G() { User u; u.f; }\n",
            "`User.f` is a method: call it with `(...)`, or take its address with `&User.f`"))
    correct++;
  tests++;
  if (Fails(USER "void User.f() {}\nvoid G() { User.f(); }\n",
            "Unexpected number of arguments 0 in call to User.f(), expected 1 args")) correct++;
  tests++;
  if (Fails(USER "void User.f() {}\nvoid G() { i64 x = User.f; }\n",
            "`User.f` needs its `self`, as in `User.f(p)`")) correct++;
  tests++;
  if (Fails(USER "void User.f(i64 x) {}\nvoid G() { User u; u.f(1, 2); }\n",
            "in call to User.f()")) correct++;
  tests++;
  if (Fails(USER "void G() { User.nope(NULL); }\n", "`User` has no method `nope`")) correct++;
  tests++;
  if (Fails(USER "void G() { User u; User *p = &u; User **pp = &p; pp.age = 1; }\n",
            "Expected as class however got pointer type `User ** pp`")) correct++;
  tests++;
  if (Fails(USER "void User.f() {}\nvoid G() { User u; User *p = &u; User **pp = &p; pp.f(); }\n",
            "Expected as class however got pointer type `User ** pp`")) correct++;
  tests++;
  if (Fails(USER "void User.f() {}\n", "methods are not supported with -transpile",
            "-transpile")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
