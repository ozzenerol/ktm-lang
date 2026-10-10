#include "testhelper.ktm"

/* Reading an element of an array of `I64 *` / `I32 *` / `F64 *` (or any
 * pointer to a non-U8, non-class type), or dereferencing an `I64 **`,
 * returned the address of the element instead of loading it: `*pb[0]`
 * printed an address and `I64 *t = pb[0]` gave `&pb`. `&p` on a local
 * `I64 *p` returned p's value instead of its address, which hid the bug
 * for `I64 **pp = &p; **pp` but broke `*pp = ...` and out-parameters. */

class Holder
{
  I64 *ptrs[3];
  F64 *fptrs[2];
  I64 n;
};

class Pt
{
  I64 x, y;
};

I64 g_a = 11, g_b = 22;
I64 *g_arr[2];
I64 *g_p = &g_a;
I64 **g_pp = &g_p;

U0 SetOut(I64 **out) { *out = &g_b; }
I64 ReadPP(I64 **pp) { return **pp; }
I64 SumAll(I64 **ptrs, I64 n)
{
  I64 i, s = 0;
  for (i = 0; i < n; i++) s += *ptrs[i];
  return s;
}

I32 Main()
{
  I64 correct = 0, tests = 30, i;

  "Test - pointer array element load: ";

  /* Every pointer element type, local array. */
  I8 v8 = -8; U8 vu8 = 200; I16 v16 = -1600; U16 vu16 = 60000;
  I32 v32 = -320000; U32 vu32 = 4000000000; I64 v64 = 5; U64 vu64 = 77;
  F64 vf = 1.5;
  I8 *p8[2]; U8 *pu8[2]; I16 *p16[2]; U16 *pu16[2];
  I32 *p32[2]; U32 *pu32[2]; I64 *p64[2]; U64 *pu64[2]; F64 *pf[2];
  p8[1] = &v8; pu8[1] = &vu8; p16[1] = &v16; pu16[1] = &vu16;
  p32[1] = &v32; pu32[1] = &vu32; p64[1] = &v64; pu64[1] = &vu64;
  pf[1] = &vf;
  if (*p8[1] == -8 && *pu8[1] == 200) correct++;
  if (*p16[1] == -1600 && *pu16[1] == 60000) correct++;
  if (*p32[1] == -320000 && *pu32[1] == 4000000000) correct++;
  if (*p64[1] == 5 && *pu64[1] == 77) correct++;
  if (*pf[1] == 1.5) correct++;

  /* The element itself is the stored pointer, not the array's address. */
  I64 *t = p64[1];
  if (t == &v64 && t != &p64) correct++;
  if (p32[1] == &v32 && pf[1] == &vf) correct++;

  /* Variable index and writes through an element. */
  I64 b0 = 1, b1 = 2, b2 = 3;
  I64 *pb[3];
  pb[0] = &b0; pb[1] = &b1; pb[2] = &b2;
  I64 sum = 0;
  for (i = 0; i < 3; i++) sum += *pb[i];
  if (sum == 6) correct++;
  *pb[0] = 7;
  *pb[2] += 10;
  (*pb[1])++;
  if (b0 == 7 && b1 == 3 && b2 == 13) correct++;
  pb[1] = pb[2];
  if (*pb[1] == 13) correct++;
  if (&pb[1] - &pb[0] == 1 && *(&pb[2])[0] == 13) correct++;
  *pf[1] = 2.25;
  if (vf == 2.25) correct++;

  /* Pointer-to-pointer. */
  I64 a = 5, b = 6;
  I64 *p = &a;
  I64 **pp = &p;
  if (**pp == 5 && *pp == &a && pp != p) correct++;
  *pp = &b;
  if (p == &b && *p == 6 && a == 5) correct++;
  **pp = 60;
  if (b == 60) correct++;
  I64 *r = NULL;
  SetOut(&r);
  if (r == &g_b && *r == 22) correct++;
  if (ReadPP(&p) == 60) correct++;
  I64 **heap = MAlloc(sizeof(I64 *) * 2);
  heap[0] = &a;
  heap[1] = &b;
  if (*heap[0] == 5 && *heap[1] == 60 && SumAll(heap, 2) == 65) correct++;
  Free(heap);
  if (SumAll(pb, 3) == 33) correct++;

  /* Arrays of pointers to pointers. */
  I64 **ppa[2];
  ppa[0] = &p;
  if (**ppa[0] == 60 && *ppa[0] == &b) correct++;

  /* `U0 *` elements. */
  U0 *vp[2];
  vp[0] = &a;
  vp[1] = &vf;
  I64 *back = vp[0];
  F64 *fback = vp[1];
  if (back == &a && *back == 5 && *fback == 2.25) correct++;

  /* Class pointer and U8 pointer elements unchanged. */
  Pt pt;
  pt.x = 3; pt.y = 4;
  Pt *pts[1];
  pts[0] = &pt;
  U8 *strs[2];
  strs[0] = "hi";
  if (pts[0]->y == 4 && strs[0][1] == 'i' && *strs[0] == 'h') correct++;

  /* Global array of pointers and a global pointer-to-pointer. */
  g_arr[0] = &g_a;
  g_arr[1] = &g_b;
  if (*g_arr[0] == 11 && *g_arr[1] == 22) correct++;
  *g_arr[1] = 23;
  if (g_b == 23 && g_arr[1] == &g_b) correct++;
  if (**g_pp == 11 && *g_pp == &g_a) correct++;

  /* Pointer arrays as class fields, by value and through a pointer. */
  Holder h;
  Holder *hp = &h;
  h.ptrs[0] = &a; h.ptrs[1] = &b; h.ptrs[2] = &b0;
  h.fptrs[0] = &vf;
  if (*h.ptrs[0] == 5 && *h.ptrs[2] == 7) correct++;
  if (*hp->ptrs[1] == 60 && *hp->fptrs[0] == 2.25) correct++;
  *hp->ptrs[0] = 50;
  I64 *hx = hp->ptrs[0];
  if (a == 50 && hx == &a) correct++;

  /* `&p` of a pointer local is its address. */
  I64 **addr = &p;
  if (addr != p && *addr == p) correct++;

  /* `*&x` and `&*p` still cancel. */
  if (*&a == 50 && &*p == p) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
