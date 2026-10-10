#include "testhelper.ktm"

/* A range-based for loop whose body is a single statement without braces
 * crashed the compiler: the body was assumed to be a compound statement
 * and the loop variable's declaration was prepended to its statement
 * list. */

class Container
{
  I64 *entries;
  I64 size;
};

class StaticContainer
{
  I64 entries[4];
  I64 size;
};

class Holder
{
  IntVec *v;
};

class BoxInt
{
  I64 i;
};

BoxInt *BInt(I64 i)
{
  BoxInt *b = MAlloc(sizeof(BoxInt));
  b->i = i;
  return b;
}

I64 g_acc = 0;

U0 Accum(I64 v)
{
  g_acc += v;
}

I32 Main()
{
  I64 correct = 0, tests = 17, i;

  "Test - range-based for without braces: ";

  I64 arr[] = {1, 2, 3, 4};
  I64 sum = 0;

  /* Plain statement body over a fixed-size array. */
  for (auto v : arr) sum += v;
  if (sum == 10) correct++;

  /* Print statement body. */
  I64 small[] = {7, 8, 9};
  for (auto v : small) "%d", v;
  " ";
  correct++;

  /* `if` statement body. */
  sum = 0;
  for (auto v : arr) if (v % 2 == 0) sum += v;
  if (sum == 6) correct++;

  /* `if`/`else` body containing break. */
  I64 n = 0;
  for (auto v : arr) if (v == 3) break; else n++;
  if (n == 2) correct++;

  /* `break` and `continue` as the whole body. */
  n = 0;
  for (auto v : arr) break;
  for (auto v : arr) continue;
  correct++;

  /* `if` with continue keeps iterating. */
  sum = 0;
  for (auto v : arr) if (v % 2) continue; else sum += v;
  if (sum == 6) correct++;

  /* Nested brace-less range loops. */
  sum = 0;
  for (auto a : arr) for (auto b : arr) sum += a * b;
  if (sum == 100) correct++;

  /* Function call body. */
  g_acc = 0;
  for (auto v : arr) Accum(v);
  if (g_acc == 10) correct++;

  /* F64 array. */
  F64 farr[] = {0.5, 1.5, 2.0};
  F64 fsum = 0.0;
  for (auto f : farr) fsum += f;
  if (fsum == 4.0) correct++;

  /* Pointer to a class with `entries` and `size`. */
  Container *c = MAlloc(sizeof(Container));
  c->entries = MAlloc(sizeof(I64) * 4);
  c->size = 0;
  c->entries[c->size++] = 11;
  c->entries[c->size++] = 22;
  c->entries[c->size++] = 33;
  sum = 0;
  for (auto it : c) sum += it;
  if (sum == 66) correct++;
  Free(c->entries);
  Free(c);

  /* Class whose `entries` is an array. */
  StaticContainer *sc = MAlloc(sizeof(StaticContainer));
  sc->size = 0;
  sc->entries[sc->size++] = 5;
  sc->entries[sc->size++] = 6;
  sc->entries[2] = 100;
  sc->entries[3] = 1000;
  sum = 0;
  for (auto it : sc) sum += it;
  if (sum == 11) correct++;

  /* Array field reached through a class reference: all 4 elements. */
  sum = 0;
  for (auto it : sc->entries) sum += it;
  if (sum == 1111) correct++;
  Free(sc);

  /* IntVec, directly and through a class reference. */
  IntVec *iv = IntVecNew();
  IntVecPush(iv, 10);
  IntVecPush(iv, 20);
  IntVecPush(iv, 30);
  sum = 0;
  for (auto it : iv) sum += it;
  if (sum == 60) correct++;

  Holder h;
  h.v = iv;
  sum = 0;
  for (I64 it : h.v) sum += it;
  if (sum == 60) correct++;
  IntVecRelease(iv);

  /* PtrVec with a typed loop variable. */
  PtrVec *pv = PtrVecNew();
  PtrVecPush(pv, BInt(1));
  PtrVecPush(pv, BInt(4));
  PtrVecPush(pv, BInt(8));
  sum = 0;
  for (BoxInt *b : pv) sum += b->i;
  if (sum == 13) correct++;
  for (BoxInt *b : pv) Free(b);
  PtrVecRelease(pv);

  /* An empty body: `;` parses to no statement at all. */
  for (auto v : arr) ;
  for (F64 f : farr) ;
  correct++;

  /* Ordinary brace-less for loops are unchanged. */
  sum = 0;
  for (i = 0; i < 4; i++) sum += arr[i];
  if (sum == 10) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
