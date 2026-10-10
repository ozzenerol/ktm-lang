I64 Check(I64 x)
{
  if (x < 0) throw('Neg');
  if (x > 9) throw('TooBig');
  return x;
}

I64 Run(I64 x)
{
  try {
    Check(x);
  } catch {
    if (Fs->except_ch == 'Neg') return -1;
    return Fs->except_ch == 'TooBig';
  }
  return 0;
}

U0 Main()
{
  I64 e = 5;
  try { Check(-e); } catch { "%d\n", Fs->except_ch == 'Neg' && e == 5; }
  "%d %d %d\n", Run(-3), Run(12), Run(4);
}
