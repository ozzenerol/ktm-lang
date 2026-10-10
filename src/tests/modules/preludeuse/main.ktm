/* Test 437's fixture: the prelude's names (Error, the integer limits,
 * EXIT_OK/EXIT_FAIL) need no import and no prefix, in the program and in
 * a module. prelude/ here is not the prelude: that is the install's. */
import lim;

i32 main() {
  Error e;
  e.code = I64_MAX;
  printf("%d %d %d\n", e.code == I64_MAX, lim.limits(), lim.caught());
  printf("%s %d\n", typeof(e), lim.make(5).code);
  return EXIT_OK;
}
