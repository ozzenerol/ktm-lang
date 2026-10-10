#include "testhelper.ktm"

/* NetAccept returned Bool, so a socket number over 255 was truncated, and
 * NetCreateServer/NetConnect returned 0 (or a getaddrinfo error code) on
 * failure, which looks like a valid socket. They return the I32 socket,
 * or -1 on failure, now. */

/* macOS starts processes with a soft limit of 256 descriptors; raise it
 * so the test can open sockets numbered above 255 there too. */
#ifdef IS_MACOS
  #define RLIMIT_NOFILE 8
#else
  #define RLIMIT_NOFILE 7
#endif
extern "c" I32 getrlimit(I32 resource, U64 *rlim);
extern "c" I32 setrlimit(I32 resource, U64 *rlim);

U0 RaiseFdLimit(U64 want)
{
  U64 rl[2]; /* rlim_cur, rlim_max */
  if (getrlimit(RLIMIT_NOFILE, rl) == -1 || rl[0] >= want) return;
  rl[0] = want;
  if (rl[1] < want) rl[0] = rl[1];
  setrlimit(RLIMIT_NOFILE, rl);
}

/* The port a listening socket is bound to (sin_port is at offset 2 in
 * both the BSD and the Linux layout) */
I64 BoundPort(I32 fd)
{
  U8 sa[128];
  U32 len = sizeof(sa);
  if (getsockname(fd, sa(sockaddr *), &len) == -1) return -1;
  return sa[2] << 8 | sa[3];
}

I32 Main()
{
  I64 correct = 0, tests = 12, i, port;
  I32 fillers[300], srv, cli, acc, srv2;
  U8 msg[8];
  "Test - NetAccept returns the whole socket number: ";

  /* push new descriptors above 255 (the large frame also made the
   * arm64 JIT load I32 locals without sign extension) */
  RaiseFdLimit(1024);
  for (i = 0; i < 300; i++) fillers[i] = open("/dev/null", O_RDONLY);
  if (fillers[299] > 255) correct++;

  srv = NetCreateServer(0, "127.0.0.1", 4);   /* port 0: any free port */
  if (srv > 255) correct++;
  port = BoundPort(srv);
  if (port > 0) correct++;

  cli = NetConnect("127.0.0.1", port);
  if (cli > 255) correct++;
  acc = NetAccept(srv);
  if (acc > 255 && acc != cli && acc != srv) correct++;

  /* the accepted socket really is the other end of `cli` */
  if (write(cli, "hi", 2) == 2 && read(acc, msg, 2) == 2 && !StrNCmp(msg, "hi", 2)) correct++;
  close(acc);

  /* non-blocking accept with nothing pending fails with -1 */
  NetSetSocketNonBlocking(srv);
  if (NetAccept(srv) == -1) correct++;
  if (NetAccept(srv, TRUE) == -1) correct++;

  /* failures are -1, never 0 */
  if (NetAccept(fillers[0]) == -1) correct++;            /* not a socket */
  srv2 = NetCreateServer(port, "127.0.0.1", 4);          /* port in use */
  if (srv2 == -1) correct++;
  else close(srv2);
  close(cli);
  close(srv);
  if (NetConnect("127.0.0.1", port) == -1) correct++;    /* nobody listening */
  if (NetCreateServer(0, "not-an-address!", 4) == -1) correct++; /* getaddrinfo fails */

  for (i = 0; i < 300; i++) close(fillers[i]);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
