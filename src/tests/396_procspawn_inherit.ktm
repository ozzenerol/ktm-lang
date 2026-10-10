#include "testhelper.ktm"

/* On macOS a ProcSpawn child had no stdin, stdout or stderr when they
 * were PROC_INHERIT (the default): POSIX_SPAWN_CLOEXEC_DEFAULT closes
 * every fd the file actions don't name, 0-2 included. Each case runs in a
 * forked child whose stdin, stdout and stderr are pipes of this test, so
 * what an inherited fd carries can be checked: a program writing to an
 * inherited stdout/stderr, reading an inherited stdin, alone and mixed
 * with PROC_PIPE / PROC_NULL / PROC_STDOUT, ProcRun's inherited stderr,
 * and a parent whose own stdin is closed. */

extern "c" U32 alarm(U32 seconds);
extern "c" U0 _exit(I32 code);
extern "c" I32 fflush(U0 *stream);
extern "c" I32 pipe(I32 *fds);
extern "c" I32 dup2(I32 fd, I32 fd2);

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

/* Spawn sh -c script with the given modes; when out/err is PROC_PIPE,
 * that stream must equal want_out/want_err. The exit code of sh, or 100+
 * when a piped stream differs. */
I32 Spawn(U8 *script, I64 in, I64 out, I64 err, U8 *want_out=NULL, U8 *want_err=NULL)
{
  U8 *argv[4];
  argv[0] = "sh";
  argv[1] = "-c";
  argv[2] = script;
  argv[3] = NULL;
  ProcOpts o;
  MemSet(&o, 0, sizeof(ProcOpts));
  o.in = in;
  o.out = out;
  o.err = err;
  Proc *p = ProcSpawn(argv, &o);
  if (!p) return 99;
  U8 *gout = NULL, *gerr = NULL;
  I64 rc = ProcCommunicate(p, NULL, 0, &gout, NULL, &gerr);
  ProcRelease(p);
  if (want_out && (!gout || StrCmp(gout, want_out))) return 101;
  if (want_err && (!gerr || StrCmp(gerr, want_err))) return 102;
  return rc;
}

I32 AllInherit()
{
  return Spawn("read x; echo \"out:$x\"; echo \"err:$x\" >&2", PROC_INHERIT, PROC_INHERIT, PROC_INHERIT);
}
I32 OutPiped()
{
  return Spawn("read x; echo o; echo \"e:$x\" >&2", PROC_INHERIT, PROC_PIPE, PROC_INHERIT, "o\n");
}
I32 ErrPiped()
{
  return Spawn("cat; echo out; echo err >&2", PROC_NULL, PROC_INHERIT, PROC_PIPE, NULL, "err\n");
}
I32 ErrToStdout()
{
  return Spawn("echo a; echo b >&2", PROC_NULL, PROC_INHERIT, PROC_STDOUT);
}
I32 InOnly()
{
  return Spawn("cat", PROC_INHERIT, PROC_PIPE, PROC_PIPE, "data\n", "");
}
I32 ErrOnly()
{
  return Spawn("echo x; echo y >&2", PROC_NULL, PROC_NULL, PROC_INHERIT);
}
I32 RunErr()
{
  U8 *argv[4];
  argv[0] = "sh";
  argv[1] = "-c";
  argv[2] = "echo o; echo e >&2; exit 3";
  argv[3] = NULL;
  I64 code;
  U8 *out = ProcRun(argv, &code);
  I32 rc = 0;
  if (!out || StrCmp(out, "o") || code != 3) rc = 1;
  Free(out);
  return rc;
}
I32 ClosedStdin()
{
  close(0);           // our stdin is closed: the child just doesn't get one
  return Spawn("echo ok", PROC_INHERIT, PROC_INHERIT, PROC_INHERIT);
}

/* Run fn in a child whose stdin holds `input` and whose stdout/stderr we
 * capture. Its exit code, or 200+ if killed. */
I32 InChild(I32 (*fn)(), U8 *input, U8 *out, U8 *err)
{
  I32 pi[2], po[2], pe[2];
  I64 n, no = 0, ne = 0;
  pipe(pi);
  pipe(po);
  pipe(pe);
  fflush(NULL);
  I32 pid = fork();
  if (pid == 0) {
    alarm(20);
    dup2(pi[0], 0);
    dup2(po[1], 1);
    dup2(pe[1], 2);
    close(pi[0]); close(pi[1]); close(po[0]); close(po[1]); close(pe[0]); close(pe[1]);
    I32 rc = fn();
    fflush(NULL);
    _exit(rc);
  }
  close(pi[0]);
  close(po[1]);
  close(pe[1]);
  write(pi[1], input, StrLen(input));
  close(pi[1]);
  /* small outputs: read stdout to EOF, then stderr */
  while ((n = read(po[0], out + no, 255 - no)) > 0) no += n;
  while ((n = read(pe[0], err + ne, 255 - ne)) > 0) ne += n;
  out[no] = 0;
  err[ne] = 0;
  close(po[0]);
  close(pe[0]);
  I32 status = 0;
  waitpid(pid, &status, 0);
  if (status & 0x7f) return 200 + (status & 0x7f);
  return (status >> 8) & 0xff;
}

U0 Case(U8 *what, I32 (*fn)(), U8 *input, U8 *want_out, U8 *want_err)
{
  U8 out[256], err[256];
  I32 rc = InChild(fn, input, out, err);
  Bool ok = rc == 0 && !StrCmp(out, want_out) && !StrCmp(err, want_err);
  Check(ok, what);
  if (!ok) "    exit %d, stdout [%s], stderr [%s]\n", rc, out, err;
}

I32 Main()
{
  "Test - ProcSpawn PROC_INHERIT: ";
  Case("all inherited: reads stdin, writes stdout and stderr", &AllInherit,
       "hello\n", "out:hello\n", "err:hello\n");
  Case("stdout piped, stdin and stderr inherited", &OutPiped, "hi\n", "", "e:hi\n");
  Case("stdin /dev/null, stdout inherited, stderr piped", &ErrPiped, "unread\n", "out\n", "");
  Case("stderr into an inherited stdout (2>&1)", &ErrToStdout, "", "a\nb\n", "");
  Case("only stdin inherited", &InOnly, "data\n", "", "");
  Case("only stderr inherited", &ErrOnly, "", "", "y\n");
  Case("ProcRun: stderr inherited", &RunErr, "", "", "e\n");
  Case("our stdin closed: the child starts anyway", &ClosedStdin, "", "ok\n", "");
  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
