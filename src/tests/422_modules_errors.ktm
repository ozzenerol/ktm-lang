#include "testhelper.ktm"

/* Modules (M1): the errors, each a fixture under src/tests/modules/errors.
 * A name not marked pub is private to its module (functions, types); a
 * module can't use the main program's names (`helper` is unknown inside
 * geo, reported in geo's file); a missing module, an import cycle, a file
 * whose `module` line doesn't match its directory or that has none, a
 * module found twice (here: next to the program and in KTM_PATH), a
 * declaration hiding an import prefix and `module` in the main program
 * are errors; an import never used is a warning. */

#define ERR_OUT "./ktm-422-modules.out"
#define FIXTURES "${KTM_TEST_SRC:-.}/modules/errors/"

I64 Count(U8 *s, U8 *what)
{
  I64 n = 0;
  while (s && (s = strstr(s, what))) {
    n++;
    s++;
  }
  return n;
}

/* Compiling fixture `name` reports `errors` errors, `warnings` warnings,
 * and `want` */
Bool Reports(U8 *name, U8 *env, I64 errors, I64 warnings, U8 *want)
{
  U8 *cmd = MStrPrint("%s $(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                      "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                      "-S " FIXTURES "%s/main.ktm -o /dev/null > " ERR_OUT " 2>&1",
                      env, name);
  System("rm -f " ERR_OUT);
  System(cmd);
  U8 *out = FileRead(ERR_OUT);
  Bool ok = out && Count(out, "error:") == errors &&
            Count(out, "warning:") == warnings && strstr(out, want);
  if (!ok) "\n%s\n----\n%s\n", name, out;
  Free(out);
  Free(cmd);
  System("rm -f " ERR_OUT);
  return ok;
}

I32 Main()
{
  "Test - modules: errors: ";
  I64 correct = 0, tests = 0;

  tests++; if (Reports("private_fn", "", 1, 1, "`square` is private to module geo")) correct++;
  tests++; if (Reports("private_type", "", 1, 0, "`Hidden` is private to module geo")) correct++;
  tests++; if (Reports("main_name", "", 1, 1, "`helper` has not been defined")) correct++;
  tests++; if (Reports("main_name", "", 1, 1, "main_name/geo/geo.ktm:3:30")) correct++;
  tests++; if (Reports("missing", "", 1, 0, "module nothere not found")) correct++;
  tests++; if (Reports("cycle", "", 1, 0, "circular import: a -> b -> a")) correct++;
  tests++;
  if (Reports("wrong_name", "", 1, 0,
              "this file is in module geo, so it starts with `module geo;`, not `module geom;`"))
    correct++;
  tests++;
  if (Reports("no_module_line", "", 1, 0, "every file of module geo starts with `module geo;`"))
    correct++;
  tests++;
  if (Reports("twice", "KTM_PATH=" FIXTURES "twice/other", 1, 0, "module dup is found twice"))
    correct++;
  tests++; if (Reports("hides", "", 1, 1, "`geo` hides the module imported as geo")) correct++;
  tests++; if (Reports("unused", "", 0, 1, "module geo is imported but not used")) correct++;
  tests++;
  if (Reports("module_in_main", "", 1, 0, "`module foo;` in a file that isn't part of a module"))
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
