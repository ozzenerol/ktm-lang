// pending: fallthrough; and Y8 switch semantics need B1 and the flip
// drop-breaks: S3's pass after the Y8 flip
I64 Grade(I64 s)
{
  I64 r = 0;
  switch (s) {
    case 0:
      r = 1;
      break;
    case 1:
    case 2:
      r = 2;
    case 3:
      r += 10;
      break;
    case 4: break;
    case 5: r = 5; break;
    case 6: {
      r = 6;
      break;
    }
    case 7:
      if (s) break;
      r = 7;
    default:
      r += 100;
      break;
  }
  return r;
}
U0 Main() { for (I64 i = 0; i < 9; i++) "%d ", Grade(i); "\n"; }
