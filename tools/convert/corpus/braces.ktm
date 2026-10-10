I64 F(I64 a, I64 b)
{
  I64 r = 0;
  if (a) r = 1;
  if (a > 1)
    r = 2;
  else if (b)
    r = 3;
  else
    r = 4;
  for (I64 i = 0; i < 3; i++)
    if (i == b) r += 10; else r += 100;
  while (r > 1000) r--;
  I64 k = 0;
  while (k++ < 3);
  do r++; while (r < 5);
  try { throw(1); } catch r += 7;
  return r + k;
}
U0 Main() { "%d %d %d\n", F(0, 0), F(2, 1), F(1, 5); }
