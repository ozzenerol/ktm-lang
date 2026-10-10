#include "testhelper.ktm"

/* Gcd used to return the wrong answer on x86_64 (Gcd(12, 18) was 18):
 * its assembly loop copied the divisor the wrong way round. Gcd uses
 * the absolute values of its arguments, Gcd(0, n) == |n|,
 * Gcd(0, 0) == 0 and the result is never negative. */

/* Slow but obviously correct: the largest d dividing both */
I64 RefGcd(I64 a, I64 b)
{
  I64 d;
  if (a < 0) a = -a;
  if (b < 0) b = -b;
  if (a == 0) return b;
  if (b == 0) return a;
  d = a;
  if (b < d) d = b;
  while (d > 1) {
    if (a % d == 0 && b % d == 0) return d;
    d--;
  }
  return 1;
}

I32 Main()
{
  I64 correct = 0, tests = 18, a, b, bad;
  U64 two63 = 1 << 63;

  "Test - Gcd: ";

  if (Gcd(12, 18) == 6) correct++;
  else "Failed - Gcd(12, 18) = %d\n", Gcd(12, 18);
  if (Gcd(18, 12) == 6) correct++;
  else "Failed - Gcd(18, 12) = %d\n", Gcd(18, 12);
  if (Gcd(7, 3) == 1) correct++;
  else "Failed - Gcd(7, 3) = %d\n", Gcd(7, 3);
  if (Gcd(48, 180) == 12 && Gcd(17, 17) == 17 && Gcd(1, 99) == 1) correct++;
  else "Failed - Gcd(48, 180) / Gcd(17, 17) / Gcd(1, 99)\n";

  /* Zero */
  if (Gcd(0, 5) == 5 && Gcd(5, 0) == 5) correct++;
  else "Failed - Gcd(0, 5) = %d, Gcd(5, 0) = %d\n", Gcd(0, 5), Gcd(5, 0);
  if (Gcd(0, -5) == 5 && Gcd(-5, 0) == 5) correct++;
  else "Failed - Gcd(0, -5) = %d, Gcd(-5, 0) = %d\n", Gcd(0, -5), Gcd(-5, 0);
  if (Gcd(0, 0) == 0) correct++;
  else "Failed - Gcd(0, 0) = %d\n", Gcd(0, 0);

  /* Negatives use absolute values */
  if (Gcd(-12, 18) == 6 && Gcd(12, -18) == 6 && Gcd(-12, -18) == 6) correct++;
  else "Failed - Gcd with negatives: %d %d %d\n",
         Gcd(-12, 18), Gcd(12, -18), Gcd(-12, -18);

  /* Large values and the I64 limits */
  if (Gcd(I64_MAX, I64_MAX) == I64_MAX) correct++;
  else "Failed - Gcd(I64_MAX, I64_MAX)\n";
  if (Gcd(1 << 62, 3 << 40) == 1 << 40) correct++;
  else "Failed - Gcd(2^62, 3 * 2^40)\n";
  /* |I64_MIN| is 2^63: it only fits in the U64 result */
  if (Gcd(I64_MIN, 0) == two63 && Gcd(0, I64_MIN) == two63) correct++;
  else "Failed - Gcd(I64_MIN, 0)\n";
  if (Gcd(I64_MIN, I64_MIN) == two63) correct++;
  else "Failed - Gcd(I64_MIN, I64_MIN)\n";
  if (Gcd(I64_MIN, -1) == 1 && Gcd(-1, I64_MIN) == 1 && Gcd(I64_MIN, 1) == 1) correct++;
  else "Failed - Gcd(I64_MIN, +-1)\n";
  if (Gcd(I64_MIN, 6) == 2 && Gcd(I64_MIN, -(1 << 20)) == 1 << 20) correct++;
  else "Failed - Gcd(I64_MIN, 6) / Gcd(I64_MIN, -2^20)\n";
  if (Gcd(I64_MIN, I64_MAX) == 1) correct++;
  else "Failed - Gcd(I64_MIN, I64_MAX)\n";

  /* Consecutive Fibonacci numbers: Euclid's worst case, coprime */
  I64 f0 = 4660046610375530309, f1 = 7540113804746346429;
  if (Gcd(f1, f0) == 1 && Gcd(f0, f1) == 1 && Gcd(f0 * 1, 2 * 1597) == 1) correct++;
  else "Failed - Gcd of Fibonacci numbers\n";

  /* Every pair in [-60, 60] against the reference */
  bad = 0;
  for (a = -60; a <= 60; a++) {
    for (b = -60; b <= 60; b++) {
      if (Gcd(a, b) != RefGcd(a, b)) {
        if (bad < 5) "Failed - Gcd(%d, %d) = %d, want %d\n",
                         a, b, Gcd(a, b), RefGcd(a, b);
        bad++;
      }
    }
  }
  if (bad == 0) correct++;

  /* Pairs with common factors, scaled up */
  bad = 0;
  for (a = 1; a <= 40; a++) {
    for (b = 1; b <= 40; b++) {
      if (Gcd(a * 1000003, b * 1000003) != RefGcd(a, b) * 1000003) bad++;
    }
  }
  if (bad == 0) correct++;
  else "Failed - %d scaled pairs\n", bad;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
