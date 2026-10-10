// TRUE when the U8 checks pass
U8 *src = "U0 Main()
{
  I64 x = 3;
  \"%d\\n\", x(F64)(I64);
}
";
U8 *msg = "expected 'I64 *' got 'U8'";
U0 Main()
{
  "%d %d\n", src != NULL, StrLen(msg) > 0;
}
