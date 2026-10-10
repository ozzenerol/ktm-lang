#include "testhelper.ktm"

/* Several declarators in one global declaration - `I64 a = 1, b = 2;`
 * at file scope - failed with "unexpected character `,`" and left the
 * later names undefined. Inside a function it always worked. */

class Point
{
  I64 x, y;
};

I64 g_a = 1, g_b = 2;
I64 g_c, g_d = 5, g_e;
U8 *g_s = "x", *g_t, g_u = 'A';      /* `*` binds per declarator */
I64 g_arr[3] = {1,2,3}, g_n = 3;
Point g_p, g_q;
Point g_r = {7,8}, *g_pp;
static I64 g_st1 = 10, g_st2 = 20;
public I64 g_pb1 = 30, *g_pb2, g_pb3[2];
F64 g_f1 = 1.5, g_f2 = 2.25, g_f3;
I64 g_i1 = 4, g_i2 = g_i1 * 3;       /* uses an earlier declarator */
U8 g_buf[8] = "hi", *g_bp;
auto g_au1 = 6, g_au2 = 7;

/* Top-level statements run before Main and see every declarator. */
I64 g_top = g_a + g_b + g_d + g_arr[2] + g_st2;
g_t = g_s;
g_pp = &g_r;
g_pb2 = &g_pb1;
g_pb3[1] = 99;
g_c = g_a + 100;
g_p.x = 11;
g_q.y = 22;
g_f3 = g_f1 + g_f2;

I32 Main()
{
  I64 correct = 0, tests = 13;

  "Test - several declarations in one global statement: ";

  if (g_a + g_b == 3) correct++;
  if (g_c == 101 && g_d == 5 && g_e == 0) correct++;
  if (g_s[0] == 'x' && g_t == g_s && g_u == 'A' && sizeof(g_u) == 1) correct++;
  if (g_arr[0] == 1 && g_arr[1] == 2 && g_arr[2] == 3 && g_n == 3) correct++;
  if (g_p.x == 11 && g_q.y == 22 && sizeof(g_q) == 16) correct++;
  if (g_r.x == 7 && g_pp->y == 8) correct++;
  if (g_st1 == 10 && g_st2 == 20) correct++;
  if (g_pb1 == 30 && *g_pb2 == 30 && g_pb3[1] == 99 && sizeof(g_pb3) == 16) correct++;
  if (g_f1 == 1.5 && g_f2 == 2.25 && g_f3 == 3.75) correct++;
  if (g_i1 == 4 && g_i2 == 12) correct++;
  if (g_buf[0] == 'h' && sizeof(g_buf) == 8 && g_bp == NULL) correct++;
  if (g_au1 + g_au2 == 13) correct++;
  if (g_top == 31) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
