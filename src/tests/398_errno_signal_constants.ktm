#include "testhelper.ktm"

/* ENOENT, EACCES and ECHILD were missing although the ProcSpawn docs name
 * them; so were ENETUNREACH, EHOSTUNREACH, EADDRNOTAVAIL, EMFILE, ENFILE
 * and the job-control signals SIGCONT, SIGTSTP, SIGSTOP, SIGTTIN and
 * SIGTTOU. Every errno and signal constant in std.ktmh is compared with the
 * platform's C headers (a C program compiled here), and the new ones are
 * checked against what the system really does where that is cheap. */

#define EC_C   "398-consts.c"
#define EC_EXE "398-consts"
#define EC_OUT "398-consts.out"
#define EC_NOX "398-noexec"

#define N_CONSTS 37

U8 *g_names[N_CONSTS];
I64 g_values[N_CONSTS];
I64 g_n;

U0 Add(U8 *name, I64 value)
{
  g_names[g_n] = name;
  g_values[g_n] = value;
  g_n++;
}

/* Every constant, in the order the C program prints them. */
U0 Fill()
{
  g_n = 0;
  Add("ENOENT", ENOENT); Add("EINTR", EINTR); Add("EBADF", EBADF);
  Add("ECHILD", ECHILD); Add("EACCES", EACCES); Add("EINVAL", EINVAL);
  Add("ENFILE", ENFILE); Add("EMFILE", EMFILE); Add("EPIPE", EPIPE);
  Add("EAGAIN", EAGAIN); Add("EWOULDBLOCK", EWOULDBLOCK);
  Add("EINPROGRESS", EINPROGRESS); Add("EADDRINUSE", EADDRINUSE);
  Add("EADDRNOTAVAIL", EADDRNOTAVAIL); Add("ENETUNREACH", ENETUNREACH);
  Add("ECONNRESET", ECONNRESET); Add("ETIMEDOUT", ETIMEDOUT);
  Add("ECONNREFUSED", ECONNREFUSED); Add("EHOSTUNREACH", EHOSTUNREACH);
  Add("SIGHUP", SIGHUP); Add("SIGINT", SIGINT); Add("SIGQUIT", SIGQUIT);
  Add("SIGKILL", SIGKILL); Add("SIGUSR1", SIGUSR1); Add("SIGUSR2", SIGUSR2);
  Add("SIGPIPE", SIGPIPE); Add("SIGALRM", SIGALRM); Add("SIGTERM", SIGTERM);
  Add("SIGCHLD", SIGCHLD); Add("SIGCONT", SIGCONT); Add("SIGSTOP", SIGSTOP);
  Add("SIGTSTP", SIGTSTP); Add("SIGTTIN", SIGTTIN); Add("SIGTTOU", SIGTTOU);
  Add("SA_RESTART", SA_RESTART); Add("SA_RESETHAND", SA_RESETHAND);
  Add("SA_NODEFER", SA_NODEFER);
}

/* Compile and run a C program printing "NAME value" for each constant;
 * the number of mismatches (-1 if it can't run). */
I64 CompareWithC()
{
  U8 *src = NULL, *out, *line, *sp;
  I64 i, bad = 0, n = 0;
  src = CatPrint(src, "#include <errno.h>\n#include <signal.h>\n#include <stdio.h>\n"
                      "int main(void) {\n");
  for (i = 0; i < g_n; i++)
    src = CatPrint(src, "  printf(\"%s %%ld\\n\", (long)%s);\n", g_names[i], g_names[i]);
  src = CatPrint(src, "  return 0;\n}\n");
  FileWrite(EC_C, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC);
  Free(src);
  if (System("cc -w -o " EC_EXE " " EC_C " > " EC_OUT " 2>&1 && ./" EC_EXE " > " EC_OUT) != 0) {
    "could not build the C program\n";
    return -1;
  }
  out = FileRead(EC_OUT);
  if (!out) return -1;
  line = out;
  for (i = 0; i < g_n && line && *line; i++) {
    sp = StrFirstOcc(line, " ");
    if (!sp || Atoi(sp + 1) != g_values[i]) {
      "%s: std.ktmh %d, C %d\n", g_names[i], g_values[i], Atoi(sp + 1);
      bad++;
    }
    n++;
    line = StrFirstOcc(line, "\n");
    if (line) line++;
  }
  Free(out);
  System("rm -f " EC_C " " EC_EXE " " EC_OUT);
  if (n != g_n) return -1;
  return bad;
}

/* waitpid's WCONTINUED flag and the status of a continued child */
#ifdef IS_LINUX
#define WCONTINUED_ 8
#define CONTINUED_STATUS 0xffff
#else
#define WCONTINUED_ 0x10
#define CONTINUED_STATUS 0x137f
#endif

/* waitpid(pid, flags | WNOHANG) for up to 5 s: a discarded signal must
 * fail the test, not hang it */
Bool WaitFor(I32 pid, I32 *st, I32 flags)
{
  for (I64 i = 0; i < 500; i++) {
    if (waitpid(pid, st, flags | 1) == pid) return TRUE;
    poll(NULL, 0, 10);
  }
  "no status change for pid %d\n", pid;
  return FALSE;
}

/* sig stops a running child (the kernel reports it as the stop signal)
 * and SIGCONT makes it run again. The child has its own process group:
 * in an orphaned group (no parent outside it in the session, as under
 * ssh) SIGTSTP, SIGTTIN and SIGTTOU are discarded. */
Bool StopsAndContinues(I32 pid, I32 sig)
{
  I32 st = 0;
  kill(pid, sig);
  if (!WaitFor(pid, &st, 2)) return FALSE;                  /* WUNTRACED */
  if ((st & 0xff) != 0x7f || ((st >> 8) & 0xff) != sig) {
    "signal %d: wait status %x\n", sig, st;
    return FALSE;
  }
  kill(pid, SIGCONT);
  if (!WaitFor(pid, &st, WCONTINUED_)) return FALSE;
  if (st != CONTINUED_STATUS) {
    "SIGCONT after %d: wait status %x\n", sig, st;
    return FALSE;
  }
  return TRUE;
}

I32 Main()
{
  "Test - errno and signal constants: ";
  I64 correct = 0, tests = 0, bad;
  U8 *argv[3];
  ProcOpts o;
  Proc *p;
  I32 fd, st;
  sockaddr_in sa;

  Fill();
  tests++;
  bad = CompareWithC();
  if (bad == 0) correct++;
  else "Failed - %d constants differ from C\n", bad;

  /* ENOENT: a missing file and a missing program */
  SetErrno(0);
  fd = open("398-no-such-dir/file", O_RDONLY, 0);
  tests++; if (fd == -1 && Errno() == ENOENT) correct++;
  MemSet(&o, 0, sizeof(ProcOpts));
  o.in = o.out = o.err = PROC_NULL;
  argv[0] = "ktm-398-no-such-program";
  argv[1] = NULL;
  p = ProcSpawn(argv, &o);
  tests++; if (!p && Errno() == ENOENT) correct++;

  /* EACCES: exec of a file without execute permission (root too) */
  FileWrite(EC_NOX, "#!/bin/sh\n", 10, O_CREAT | O_RDWR | O_TRUNC);
  argv[0] = "./" EC_NOX;
  o.no_path = TRUE;
  p = ProcSpawn(argv, &o);
  tests++; if (!p && Errno() == EACCES) correct++;
  else "Failed - EACCES: got %d\n", Errno();
  Rm(EC_NOX);
  o.no_path = FALSE;

  /* EADDRNOTAVAIL: bind to an address this host doesn't have (TEST-NET-1) */
  fd = socket(AF_INET, SOCK_STREAM, 0);
  MemSet(&sa, 0, sizeof(sockaddr_in));
  sa.sin_family = AF_INET;
  inet_pton(AF_INET, "192.0.2.1", &sa.sin_addr);
  SetErrno(0);
  tests++;
  if (bind(fd, (&sa)(sockaddr *), sizeof(sockaddr_in)) == -1 &&
      Errno() == EADDRNOTAVAIL) correct++;
  else "Failed - EADDRNOTAVAIL: got %d\n", Errno();
  close(fd);

  /* the job-control signals do what their names say */
  argv[0] = "sleep";
  argv[1] = "10";
  argv[2] = NULL;
  o.new_group = TRUE;
  p = ProcSpawn(argv, &o);
  tests++;
  if (p && StopsAndContinues(p->pid, SIGSTOP) && StopsAndContinues(p->pid, SIGTSTP) &&
      StopsAndContinues(p->pid, SIGTTIN) && StopsAndContinues(p->pid, SIGTTOU))
    correct++;
  else "Failed - stop/continue signals\n";
  if (p) {
    ProcKill(p, SIGKILL);
    ProcWait(p);
    tests++; if (p->term_signal == SIGKILL) correct++;
    ProcRelease(p);
  }

  /* ECHILD: nothing left to wait for */
  SetErrno(0);
  tests++; if (waitpid(-1, &st, 1) == -1 && Errno() == ECHILD) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
