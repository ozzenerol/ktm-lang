/* Comments get the new names: U8 TRUE class x(I64) */
#define ONE 1(I64)
#define SHOUT "TRUE and NULL are words here\n"
#define SQL "SELECT * FROM t WHERE x IS NULL"

// I64 in a line comment
U0 Main()
{
  U8 *s = "plain text(words)";
  I64 n = ONE + 1;
  "%s|%s\n", s, SQL;
  "%d\n", n;
}
