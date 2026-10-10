#include "testhelper.ktm"

/* Processes (process.ktm): ProcSpawn runs argv without a shell; exit codes
 * and signals from ProcWait; ENOENT for a missing program; PROC_NULL,
 * PROC_STDOUT, cwd and env; ProcDone; ProcKill of a whole process group;
 * ProcCommunicate moving 1 MiB through stdin/stdout while 1 MiB goes to
 * stderr (no deadlock); a child that never reads its stdin (no SIGPIPE);
 * no fd of ours leaking into the child; spawns from 4 threads at once.
 * Only sh, cat, echo, printf, pwd, yes and head are used: all exist on
 * Linux and macOS. alarm() ends the test instead of hanging the suite.
 * Under QEMU user emulation (docker/test.sh arm64 on an x86 host) glibc's
 * posix_spawn can't use clone(CLONE_VM|CLONE_VFORK): a missing program
 * then shows up as exit 127 instead of ENOENT and the process group isn't
 * made, so "missing program" and "ProcKill of a process group" fail
 * there; native arm64 Linux (Docker on the Mac) passes. */

extern "c" U32 alarm(U32 seconds);
extern "c" U0 _exit(I32 code);
extern "c" I32 fflush(U0 *stream);
extern "c" I32 setenv(U8 *name, U8 *value, I32 overwrite);


ProcOpts *Opts(I64 in, I64 out, I64 err)
{
  ProcOpts *o = CAlloc(sizeof(ProcOpts));
  o->in = in;
  o->out = out;
  o->err = err;
  return o;
}

/* Run argv with stdout piped; its output (Free it) and exit code. */
U8 *Out(U8 **argv, ProcOpts *o, I64 *code)
{
  U8 *out = NULL;
  Proc *p = ProcSpawn(argv, o);
  if (!p) return NULL;
  *code = ProcCommunicate(p, NULL, 0, &out);
  ProcRelease(p);
  return out;
}

Bool ExitCodes()
{
  U8 *a0[] = {"sh", "-c", "exit 0", NULL};
  U8 *a3[] = {"sh", "-c", "exit 3", NULL};
  U8 *a255[] = {"sh", "-c", "exit 255", NULL};
  U8 *k[] = {"sh", "-c", "kill -9 $$", NULL};
  Proc *p;
  I64 r0, r3, r255, rk;
  p = ProcSpawn(a0);   r0 = ProcWait(p);   ProcRelease(p);
  p = ProcSpawn(a3);   r3 = ProcWait(p);   ProcRelease(p);
  p = ProcSpawn(a255); r255 = ProcWait(p); ProcRelease(p);
  p = ProcSpawn(k);
  rk = ProcWait(p);
  Bool killed = rk == -1 && p->term_signal == 9 && p->done;
  Bool again = ProcWait(p) == -1;                 /* stored, no second wait */
  ProcRelease(p);
  return r0 == 0 && r3 == 3 && r255 == 255 && killed && again;
}

Bool Missing()
{
  U8 *a[] = {"ktm-no-such-program-379", NULL};
  U8 *b[] = {"sh", "-c", "exit 7", NULL};
  ProcOpts o;
  MemSet(&o, 0, sizeof(ProcOpts));
  if (ProcSpawn(a) != NULL || Errno != ENOENT) return FALSE;
  o.no_path = TRUE;                               /* "sh" is not a path */
  if (ProcSpawn(b, &o) != NULL || Errno != ENOENT) return FALSE;
  b[0] = "/bin/sh";
  Proc *p = ProcSpawn(b, &o);
  if (!p) return FALSE;
  I64 rc = ProcWait(p);
  ProcRelease(p);
  return rc == 7;
}

Bool NoShell()
{// every argument arrives as it was, nothing is expanded or split
  U8 *a[] = {"printf", "%s|", "a b", "it's", "$HOME", ";", "*", "", "\"q\"", NULL};
  I64 code = -2;
  U8 *s = Out(a, Opts(PROC_NULL, PROC_PIPE, PROC_INHERIT), &code);
  Bool ok = s && !StrCmp(s, "a b|it's|$HOME|;|*||\"q\"|") && code == 0;
  Free(s);
  U8 *e[] = {"echo", "hello", NULL};
  s = ProcRun(e, &code);
  ok = ok && s && !StrCmp(s, "hello") && code == 0; /* newline removed */
  Free(s);
  return ok;
}

Bool Redirects()
{
  I64 code;
  U8 *cat[] = {"cat", NULL};
  U8 *s = Out(cat, Opts(PROC_NULL, PROC_PIPE, PROC_INHERIT), &code);
  Bool ok = s && !StrCmp(s, "") && code == 0;     /* stdin /dev/null: EOF */
  Free(s);

  U8 *both[] = {"sh", "-c", "echo out; echo err >&2", NULL};
  s = Out(both, Opts(PROC_NULL, PROC_PIPE, PROC_STDOUT), &code);
  ok = ok && s && !StrCmp(s, "out\nerr\n");
  Free(s);
  s = Out(both, Opts(PROC_NULL, PROC_PIPE, PROC_NULL), &code);
  ok = ok && s && !StrCmp(s, "out\n");
  Free(s);

  U8 *out = NULL, *err = NULL;
  I64 olen = -1, elen = -1;
  Proc *p = ProcSpawn(both, Opts(PROC_NULL, PROC_PIPE, PROC_PIPE));
  ProcCommunicate(p, NULL, 0, &out, &olen, &err, &elen);
  ok = ok && !StrCmp(out, "out\n") && olen == 4 && !StrCmp(err, "err\n") && elen == 4;
  ProcRelease(p);
  Free(out);
  Free(err);

  /* a stream that isn't piped gives NULL */
  p = ProcSpawn(both, Opts(PROC_NULL, PROC_PIPE, PROC_NULL));
  err = "x";
  ProcCommunicate(p, NULL, 0, &out, NULL, &err, &elen);
  ok = ok && err == NULL && elen == 0;
  ProcRelease(p);
  Free(out);
  return ok;
}

Bool CwdEnv()
{
  I64 code;
  U8 *pwd[] = {"pwd", NULL};
  ProcOpts *o = Opts(PROC_NULL, PROC_PIPE, PROC_INHERIT);
  o->cwd = "/";
  U8 *s = Out(pwd, o, &code);
  Bool ok = s && !StrCmp(s, "/\n");
  Free(s);

  U8 *show[] = {"sh", "-c", "echo \"$KTM_379|$HOME\"", NULL};
  U8 *env[] = {"KTM_379=replaced", NULL};
  o = Opts(PROC_NULL, PROC_PIPE, PROC_INHERIT);
  o->env = env;
  s = Out(show, o, &code);
  ok = ok && s && !StrCmp(s, "replaced|\n");      /* HOME is gone */
  Free(s);

  setenv("KTM_379", "inherited", 1);              /* set at run time */
  s = Out(show, Opts(PROC_NULL, PROC_PIPE, PROC_INHERIT), &code);
  ok = ok && s && StrLen(s) > 10 && !StrNCmp(s, "inherited|", 10);
  Free(s);
  return ok;
}

Bool DoneAndKill()
{
  U8 *rd[] = {"sh", "-c", "read x; exit 4", NULL};
  Proc *p = ProcSpawn(rd, Opts(PROC_PIPE, PROC_INHERIT, PROC_INHERIT));
  Bool ok = !ProcDone(p);                         /* waiting for a line */
  write(p->in_fd, "go\n", 3);
  ok = ok && ProcWait(p) == 4 && ProcDone(p);
  ok = ok && !ProcKill(p);                        /* reaped: refused */
  ProcRelease(p);

  /* the group: sh and its background sleep both hold stdout; only
   * killing the whole group lets stdout reach EOF */
  U8 *tree[] = {"sh", "-c", "sleep 30 & sleep 30", NULL};
  ProcOpts *o = Opts(PROC_NULL, PROC_PIPE, PROC_INHERIT);
  o->new_group = TRUE;
  p = ProcSpawn(tree, o);
  ok = ok && ProcKill(p, SIGKILL);
  U8 *out;
  I64 rc = ProcCommunicate(p, NULL, 0, &out);     /* returns at EOF */
  ok = ok && rc == -1 && p->term_signal == SIGKILL && !StrCmp(out, "");
  Free(out);
  ProcRelease(p);
  return ok;
}

#define MIB (1024 * 1024)

Bool NoDeadlock()
{// 1 MiB in and out through cat while 1 MiB of 'y' lines goes to stderr
  U8 *a[] = {"sh", "-c", "yes | head -c 1048576 >&2 & cat; wait", NULL};
  U8 *in = MAlloc(MIB), *out = NULL, *err = NULL;
  I64 i, olen = 0, elen = 0;
  for (i = 0; i < MIB; i++) in[i] = 'a' + i % 23;
  Proc *p = ProcSpawn(a, Opts(PROC_PIPE, PROC_PIPE, PROC_PIPE));
  I64 rc = ProcCommunicate(p, in, MIB, &out, &olen, &err, &elen);
  Bool ok = rc == 0 && olen == MIB && elen == MIB && !memcmp(in, out, MIB);
  ok = ok && err[0] == 'y' && err[1] == '\n' && err[MIB - 1] == '\n';
  ProcRelease(p);
  Free(in);
  Free(out);
  Free(err);

  /* output dropped (NULL) is still drained */
  U8 *big[] = {"sh", "-c", "yes | head -c 1048576", NULL};
  p = ProcSpawn(big, Opts(PROC_NULL, PROC_PIPE, PROC_INHERIT));
  ok = ok && ProcCommunicate(p, NULL, 0, NULL) == 0;
  ProcRelease(p);
  return ok;
}

I32 ChildNoSigpipe()
{// the child exits at once; 1 MiB for its stdin must not kill us
  U8 *a[] = {"sh", "-c", "exit 0", NULL};
  U8 *in = CAlloc(MIB);
  Proc *p = ProcSpawn(a, Opts(PROC_PIPE, PROC_INHERIT, PROC_INHERIT));
  I64 rc = ProcCommunicate(p, in, MIB, NULL);
  ProcRelease(p);
  return rc;                      /* 0 */
}

Bool NoLeak()
{
  I32 srv = NetCreateServer(0, "127.0.0.1", 4);  /* inheritable socket */
  U8 *cat[] = {"cat", NULL};
  Proc *keep = ProcSpawn(cat, Opts(PROC_PIPE, PROC_PIPE, PROC_INHERIT));
  if (srv < 3 || !keep) return FALSE;
  /* our pipe ends are close-on-exec */
  Bool ok = (fcntl(keep->in_fd, F_GETFD) & FD_CLOEXEC) &&
            (fcntl(keep->out_fd, F_GETFD) & FD_CLOEXEC);
  U8 *script = MStrPrint("for fd in %d %d %d; do (: <&$fd) 2>/dev/null && echo open || echo closed; done",
                         srv, keep->in_fd, keep->out_fd);
  U8 *a[] = {"sh", "-c", script, NULL};
  I64 code;
  U8 *s = Out(a, Opts(PROC_NULL, PROC_PIPE, PROC_INHERIT), &code);
  ok = ok && s && !StrCmp(s, "closed\nclosed\nclosed\n");
  Free(s);
  Free(script);
  ProcRelease(keep);              /* cat sees EOF and ends */
  close(srv);
  return ok;
}

U0 *Spawner(U0 *arg)
{
  I64 id = arg(I64), j, code, *bad = MAlloc(sizeof(I64));
  *bad = 0;
  for (j = 0; j < 25; j++) {
    U8 *want = MStrPrint("t%d-%d", id, j);
    U8 *a[] = {"echo", want, NULL};
    U8 *s = ProcRun(a, &code);
    if (!s || StrCmp(s, want) || code != 0) *bad += 1;
    Free(s);
    Free(want);
  }
  return bad;
}

Bool Threads()
{
  pthread_t *t[4];
  I64 i, bad = 0, *r;
  for (i = 0; i < 4; i++) pthread_create(&t[i], NULL, &Spawner, i(U0 *));
  for (i = 0; i < 4; i++) {
    pthread_join(t[i], &r);
    bad += *r;
    Free(r);
  }
  return bad == 0;
}

I32 RunInChild(I32 (*fn)())
{// fn's result, or 100 + the signal that killed it
  fflush(NULL);
  I32 pid = fork();
  if (pid == 0) {
    alarm(30);
    _exit(fn());
  }
  I32 status = 0;
  waitpid(pid, &status, 0);
  if (status & 0x7f) return 100 + (status & 0x7f);
  return (status >> 8) & 0xff;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  alarm(120);
  "Test - processes: ";

  tests++;
  if (ExitCodes) correct++;
  else "\n  exit codes / killed by a signal\n";

  tests++;
  if (Missing) correct++;
  else "\n  missing program: ENOENT / no_path\n";

  tests++;
  if (NoShell) correct++;
  else "\n  argv reaches the program unchanged\n";

  tests++;
  if (Redirects) correct++;
  else "\n  PROC_NULL / PROC_STDOUT / both pipes\n";

  tests++;
  if (CwdEnv) correct++;
  else "\n  cwd and env\n";

  tests++;
  if (DoneAndKill) correct++;
  else "\n  ProcDone / ProcKill of a process group\n";

  tests++;
  if (NoDeadlock) correct++;
  else "\n  1 MiB both ways plus 1 MiB on stderr\n";

  tests++;
  I32 rc = RunInChild(&ChildNoSigpipe);
  if (rc == 0) correct++;
  else "\n  writing to a child that exited (%d)\n", rc;

  tests++;
  if (NoLeak) correct++;
  else "\n  our fds leak into the child\n";

  tests++;
  if (Threads) correct++;
  else "\n  spawning from 4 threads\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
