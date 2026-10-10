#include "testhelper.ktm"

/* ListDeque takes from the front (FIFO with ListAppend) and ListPop
 * from the back (LIFO with ListAppend); a single-worker ThreadPool runs
 * jobs in submission order. */

I64 g_order[8];
I64 g_n = 0;

U0 RecordJob(U0 *priv, U0 *argv)
{
  g_order[g_n++] = argv(I64);
}

I32 Main()
{
  "Test - ListDeque FIFO order: ";
  I64 correct = 0, tests = 7, i;
  List *l = ListNew();

  /* Empty and NULL lists */
  if (ListDeque(l) == NULL && ListPop(l) == NULL && ListDeque(NULL) == NULL)
    correct++;

  ListAppend(l, 1(U0 *));
  ListAppend(l, 2(U0 *));
  ListAppend(l, 3(U0 *));
  if (ListDeque(l)(I64) == 1 && ListDeque(l)(I64) == 2 &&
      ListDeque(l)(I64) == 3 && ListDeque(l) == NULL && ListEmpty(l)) correct++;

  /* Interleaved append / deque keeps FIFO order */
  Bool ok = TRUE;
  I64 next_in = 0, next_out = 0;
  for (i = 0; i < 50; i++) {
    ListAppend(l, next_in++(U0 *));
    ListAppend(l, next_in++(U0 *));
    if (ListDeque(l)(I64) != next_out++) ok = FALSE;
  }
  while (!ListEmpty(l)) {
    if (ListDeque(l)(I64) != next_out++) ok = FALSE;
  }
  if (ok && next_out == 100 && ListCount(l) == 0) correct++;

  /* ListPop takes from the back */
  ListAppend(l, 1(U0 *));
  ListAppend(l, 2(U0 *));
  ListAppend(l, 3(U0 *));
  if (ListPop(l)(I64) == 3 && ListCount(l) == 2) correct++;
  /* ListPrepend puts at the front, where ListDeque takes from */
  ListPrepend(l, 0(U0 *));
  if (ListDeque(l)(I64) == 0 && ListDeque(l)(I64) == 1 &&
      ListPop(l)(I64) == 2 && ListEmpty(l)) correct++;
  ListRelease(l, NULL);

  /* One worker: the jobs run in the order they were queued */
  ThreadPool *pool = ThreadPoolNew(1);
  for (i = 0; i < 8; i++) {
    ThreadPoolEnqueue(pool, i(U0 *), &RecordJob);
  }
  ThreadPoolWait(pool);
  if (g_n == 8) correct++;
  ok = TRUE;
  for (i = 0; i < 8; i++) {
    if (g_order[i] != i) ok = FALSE;
  }
  if (ok) correct++;
  ThreadPoolRelease(pool);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
