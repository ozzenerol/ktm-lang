#include "testhelper.ktm"
#include "run_shared.ktm"

/* The test runners had no time limit: one hanging test (a deadlock)
 * blocked the whole suite until someone killed it. RunTests now kills a
 * test after KTM_TEST_TIMEOUT seconds, together with everything it
 * started, reports it as FAILED "timed out" and goes on. Here it runs two
 * tests with a 2 s limit: "hang" starts a shell that sleeps for 20 s,
 * "quick" just prints. */

#define HANG_SECS 20

U8 *g_test_dir;   /* this test's working directory */

/* RunTests' hook: what each of the two tests does */
U0 RunTest(U8 *name, I64 idx)
{
  if (!StrCmp(name, "hang")) {
    /* the shell writes its pid, then becomes the sleep */
    U8 *cmd = MStrPrint("echo $$ > '%s/__381_pid'; exec sleep %d",
                        g_test_dir, HANG_SECS);
    System(cmd);
    Free(cmd);
    "hang finished\n";
  } else {
    "quick ran\n";
  }
}

Bool Contains(U8 *s, U8 *what)
{
  return s && strstr(s, what) != NULL;
}

I32 Main()
{
  "Test - runner timeout: ";
  I64 correct = 0, tests = 0, len = 0, began, took, i;
  I32 pid, status, sleep_pid = 0;
  g_target = "";
  g_test_dir = AbsPath(".");
  unlink("__381_pid");
  unlink("__381_out");

  setenv("KTM_TEST_TIMEOUT", "2", 1);
  setenv("JOBS", "2", 1);
  PtrVec *names = PtrVecNew;
  PtrVecPush(names, StrNew("hang"));
  PtrVecPush(names, StrNew("quick"));

  /* RunTests in a child, its output in __381_out */
  began = NowMs();
  fflush(NULL);
  pid = fork();
  if (pid == 0) {
    I32 fd = creat("__381_out", 0x1A4);
    dup2(fd, 1);
    dup2(fd, 2);
    close(fd);
    RunTests(names);
    fflush(NULL);
    _exit(0);
  }
  waitpid(pid, &status, 0);
  took = NowMs() - began;
  U8 *out = FileRead("__381_out", &len);

  tests++;
  if (Contains(out, "FAILED") && Contains(out, "timed out after 2 s: hang"))
    correct++;
  else "no timeout reported ";

  tests++; if (Contains(out, "quick ran")) correct++; else "quick test missing ";
  tests++; if (!Contains(out, "hang finished")) correct++; else "hang was not stopped ";
  /* not before the limit, and not as long as the sleep */
  tests++;
  if (took >= 2000 && took < (HANG_SECS - 5) * 1000) correct++;
  else "took %d ms ", took;

  /* the sleep the hanging test started was killed too */
  U8 *pid_text = FileRead("__381_pid");
  if (pid_text) sleep_pid = Atoi(pid_text);
  for (i = 0; i < 300 && sleep_pid > 0 && kill(sleep_pid, 0) == 0; i++) {
    usleep(10000);
  }
  tests++;
  if (sleep_pid > 0 && kill(sleep_pid, 0) != 0) correct++;
  else "sleep %d still running ", sleep_pid;

  if (correct != tests && out) "\n%s", out;
  Free(out);
  Free(pid_text);
  PtrVecRelease(names, &Free);
  unlink("__381_pid");
  unlink("__381_out");
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
