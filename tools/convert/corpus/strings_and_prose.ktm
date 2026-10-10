/* Programs in strings split around a macro, a shell-quoted program, an
 * #if branch with prose in it, and a guard around TRUE */
#define HDR "x.ktmh"

#ifndef TRUE
#define TRUE 1
#endif

#if 0
Don't compile this: it's prose, and the code after it still converts.
U0 Hidden() { "hidden\n"; }
U0 Main() { Hidden; }
#endif

U0 Main()
{
  U8 *prog = "#include \"" HDR "\"\nU0 Main() { \"%d\\n\", 1(I64); }\n";
  U8 *sh = "printf 'U0 Main() { U8 s[3]; }\\n' > /dev/null";
  I64 ok = StrLen(prog) > 0 && StrLen(sh) > 0;
  "%d %d\n", ok, TRUE;
}
