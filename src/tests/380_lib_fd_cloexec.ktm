#include "testhelper.ktm"

/* The fds the library opens (sockets from the Net* helpers, accepted
 * connections, FileRead/FileWrite's file (also while FileRead reads a
 * FIFO to its end), Shlurp's temporary file) were
 * inherited by every program started with System, Sh, Shlurp or exec:
 * a child kept a listening socket or a client connection open. They are
 * close-on-exec now. Checked with F_GETFD, and by asking a shell started
 * with Shlurp which of the fds it has. */

extern "c" I32 mkfifo(U8 *path, U32 mode);
extern "c" U32 alarm(U32 seconds);

#define FIFO "./ktm-380.fifo"
#define USOCK "./ktm-380.sock"
#define BIG (256 * 1024)

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

Bool CloseOnExec(I32 fd)
{
  I32 fl = fcntl(fd, F_GETFD);
  return fl != -1 && (fl & FD_CLOEXEC) != 0;
}

/* Does a shell started by Shlurp have fd open? (`[ -e /dev/fd/N ]` opens
 * nothing, so the child can't mistake one of its own fds for ours) */
Bool ChildHas(I32 fd)
{
  U8 *out = Shlurp("[ -e /dev/fd/%d ] && echo yes", fd);
  Bool has = out && !StrCmp(out, "yes");
  Free(out);
  return has;
}

U0 Kind(I32 fd, U8 *what)
{
  U8 buf[128];
  if (fd < 0) {
    Check(FALSE, what);
    return;
  }
  StrPrint(buf, "%s: FD_CLOEXEC", what);
  Check(CloseOnExec(fd), buf);
  StrPrint(buf, "%s: not inherited", what);
  Check(!ChildHas(fd), buf);
}

U8 *g_big;
U0 *WriteFifo(U0 *arg)
{// Blocks in FileWrite until main drains the fifo
  FileWrite(FIFO, g_big, BIG, O_WRONLY);
  return NULL;
}

U0 FileWriteFd()
{
  I32 rd, probe, n, i;
  pthread_t *th;
  U8 buf[4096];
  I64 got = 0;

  unlink(FIFO);
  if (mkfifo(FIFO, 0x1A4) != 0) {
    Check(FALSE, "mkfifo");
    return;
  }
  /* Non-blocking so this open doesn't wait for a writer */
  rd = open(FIFO, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
  Check(rd != -1, "open the fifo for reading");
  /* The fd FileWrite will get: the lowest free one */
  probe = open("/dev/null", O_RDONLY | O_CLOEXEC);
  close(probe);
  g_big = MAlloc(BIG);
  MemSet(g_big, 'x', BIG);
  pthread_create(&th, NULL, &WriteFifo);

  /* FileWrite has the fifo open once that fd exists; the pipe then fills
   * (it holds far less than BIG) and its write waits with the fd open.
   * (poll can't tell: on macOS it misses data already in a fifo.) */
  for (i = 0; i < 500 && fcntl(probe, F_GETFD) == -1; i++) poll(NULL, 0, 10);
  Check(fcntl(probe, F_GETFD) != -1, "FileWrite opened the fifo");
  Check(CloseOnExec(probe), "FileWrite: FD_CLOEXEC");
  Check(!ChildHas(probe), "FileWrite: not inherited");

  /* Drain to EOF before the join, with blocking reads: EOF comes when
   * FileWrite has written everything and closed its end. (alarm in Main
   * ends the test if this ever stalls.) */
  fcntl(rd, F_SETFL, fcntl(rd, F_GETFL) & ~O_NONBLOCK);
  while ((n = read(rd, buf, sizeof(buf))) > 0) got += n;
  pthread_join(th);
  Check(got == BIG, "the whole buffer arrived");
  close(rd);
  unlink(FIFO);
  Free(g_big);
}

/* FileRead's fd: the FIFO fd that isn't `except` */
I32 FifoFd(I32 except)
{
  cStat st;
  I32 fd;
  for (fd = 3; fd < 256; fd++) {
    if (fd != except && fstat(fd, &st) == 0 && (st.st_mode & S_IFMT) == S_IFIFO)
      return fd;
  }
  return -1;
}

U8 *g_read;
I64 g_read_len;
U0 *ReadFifo(U0 *arg)
{// Blocks in FileRead (reading to EOF) until main closes its end
  g_read = FileRead(FIFO, &g_read_len);
  return NULL;
}

U0 FileReadFd()
{
  I32 wr = -1, fd = -1, i;
  pthread_t *th;

  unlink(FIFO);
  if (mkfifo(FIFO, 0x1A4) != 0) {
    Check(FALSE, "mkfifo 2");
    return;
  }
  pthread_create(&th, NULL, &ReadFifo);
  /* A non-blocking open for writing works once FileRead has it open */
  for (i = 0; i < 500 && wr == -1; i++) {
    wr = open(FIFO, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (wr == -1) poll(NULL, 0, 10);
  }
  Check(wr != -1, "FileRead opened the fifo");
  /* Never leave the thread waiting in open(): a blocking open for writing
   * returns as soon as it has the fifo open */
  if (wr == -1) wr = open(FIFO, O_WRONLY | O_CLOEXEC);
  /* Nothing written yet: FileRead waits in its read with the fd open */
  for (i = 0; i < 200 && fd == -1; i++) {
    fd = FifoFd(wr);
    if (fd == -1) poll(NULL, 0, 10);
  }
  Check(fd != -1 && CloseOnExec(fd), "FileRead: FD_CLOEXEC");
  Check(fd != -1 && !ChildHas(fd), "FileRead: not inherited");
  write(wr, "fifo data", 9);
  close(wr);
  pthread_join(th);
  Check(g_read && g_read_len == 9 && !StrCmp(g_read, "fifo data"),
      "FileRead got the data");
  Free(g_read);
  unlink(FIFO);
}

I32 Main()
{
  I32 plain, srv, cli, acc, usrv, ucli, uacc, udp, port;
  "Test - library fds are close-on-exec: ";
  alarm(60);   /* a hang ends the test (SIGALRM) instead of the suite */

  /* The check works: an fd opened without O_CLOEXEC is inherited */
  plain = open("/dev/null", O_RDONLY);
  Check(plain != -1 && !CloseOnExec(plain), "plain open has no FD_CLOEXEC");
  Check(ChildHas(plain), "the child sees an inheritable fd");
  close(plain);

  srv = NetCreateServer(0, "127.0.0.1", 4);
  Kind(srv, "NetCreateServer");
  port = NetGetSocketPort(srv);
  cli = NetConnect("127.0.0.1", port);
  Kind(cli, "NetConnect");
  acc = NetAccept(srv);
  Kind(acc, "NetAccept");

  unlink(USOCK);
  usrv = NetCreateUnixServerSocket(USOCK, 4);
  Kind(usrv, "NetCreateUnixServerSocket");
  ucli = NetCreateUnixClientSocket(USOCK);
  Kind(ucli, "NetCreateUnixClientSocket");
  uacc = NetAccept(usrv);
  Kind(uacc, "NetAccept on a unix socket");

  udp = NetCreateUdpSocket(0, "127.0.0.1");
  Kind(udp, "NetCreateUdpSocket");

  close(udp);
  close(uacc); close(ucli); close(usrv);
  unlink(USOCK);
  close(acc); close(cli); close(srv);

  FileWriteFd;
  FileReadFd;

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
