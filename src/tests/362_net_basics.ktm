#include "testhelper.ktm"

/* The basics socket code needs had no stdlib support: byte order
 * (Htons/Ntohs/Htonl/Ntohl), the C errno (Errno/SetErrno) and its E*
 * names, the MSG_* send/recv flags, SHUT_* and poll(2) with pollfd. */

extern "c" I32 socketpair(I32 d, I32 t, I32 p, I32 *sv);

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

U0 ByteOrder()
{
  U16 port = 0x1234;
  U32 ip = 0x7F000001;
  Check(Htons(0x1234) == 0x3412, "Htons(0x1234)");
  Check(Htons(0x00FF) == 0xFF00, "Htons(0x00FF)");
  Check(Htons(0xFF00) == 0x00FF, "Htons(0xFF00)");
  Check(Ntohs(Htons(port)) == port, "Ntohs(Htons(x))");
  Check(Htonl(0x12345678) == 0x78563412, "Htonl(0x12345678)");
  Check(Htonl(0x80) == 0x80000000, "Htonl(0x80): high bit set");
  Check(Htonl(0x80000000) == 0x80, "Htonl(0x80000000)");
  Check(Ntohl(Htonl(ip)) == ip, "Ntohl(Htonl(x))");
  /* inet_addr gives network order */
  Check(inet_addr("127.0.0.1") == Htonl(ip), "Htonl matches inet_addr");
}

U0 ErrnoValues()
{
  I32 fd, srv;
  sockaddr_in sa;
  U32 len = sizeof(sockaddr_in);

  SetErrno(0);
  Check(Errno() == 0, "SetErrno(0)");
  SetErrno(EINVAL);
  Check(Errno() == EINVAL, "SetErrno(EINVAL)");

  Check(close(-1) == -1 && Errno() == EBADF, "close(-1): EBADF");

  /* Nothing bound on the port: the connection is refused (a bound socket
   * that isn't listening refuses on Linux but times out on macOS) */
  srv = socket(AF_INET, SOCK_STREAM, 0);
  MemSet(&sa, 0, sizeof(sockaddr_in));
#ifndef IS_LINUX
  sa.sin_len = sizeof(sockaddr_in);
#endif
  sa.sin_family = AF_INET;
  sa.sin_port = Htons(0);
  sa.sin_addr.s_addr = Htonl(0x7F000001);
  Check(bind(srv, &sa, sizeof(sockaddr_in)) == 0, "bind 127.0.0.1:0");
  Check(getsockname(srv, &sa, &len) == 0 && Ntohs(sa.sin_port) != 0,
      "getsockname gives a port");
  close(srv);
  fd = socket(AF_INET, SOCK_STREAM, 0);
  Check(connect(fd, &sa, sizeof(sockaddr_in)) == -1 &&
      Errno() == ECONNREFUSED, "connect: ECONNREFUSED");
  close(fd);
}

U0 FlagsAndPoll()
{
  I32 sv[2];
  U8 buf[8];
  pollfd p[2];

  Check(sizeof(pollfd) == 8, "sizeof(pollfd)");
  Check(socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0, "socketpair");

  /* Nothing to read: MSG_DONTWAIT returns at once */
  Check(recv(sv[0], buf, 1, MSG_DONTWAIT) == -1 && Errno() == EAGAIN,
      "recv MSG_DONTWAIT: EAGAIN");
  Check(EWOULDBLOCK == EAGAIN, "EWOULDBLOCK");

  p[0].fd = sv[0];
  p[0].events = POLLIN;
  p[0].revents = 0x7F;
  p[1].fd = -1;   /* skipped */
  p[1].events = POLLIN;
  Check(poll(p, 2, 0) == 0 && p[0].revents == 0, "poll: nothing to read");
  p[0].events = POLLOUT;
  Check(poll(p, 1, 0) == 1 && p[0].revents & POLLOUT, "poll: POLLOUT");

  Check(send(sv[1], "hi", 2, 0) == 2, "send");
  p[0].events = POLLIN;
  Check(poll(p, 1, 1000) == 1 && p[0].revents & POLLIN, "poll: POLLIN");
  Check(recv(sv[0], buf, 2, MSG_PEEK) == 2, "recv MSG_PEEK");
  Check(recv(sv[0], buf, 2, MSG_WAITALL) == 2 && buf[0] == 'h' &&
      buf[1] == 'i', "recv after MSG_PEEK reads the same data");

  /* SHUT_WR: the peer reads EOF */
  Check(shutdown(sv[1], SHUT_WR) == 0, "shutdown SHUT_WR");
  Check(recv(sv[0], buf, 1, 0) == 0, "EOF after SHUT_WR");
  close(sv[0]);
  close(sv[1]);

  /* MSG_NOSIGNAL: a write to a closed peer fails with EPIPE instead of
   * killing the process with SIGPIPE */
  Check(socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0, "socketpair 2");
  close(sv[1]);
  Check(send(sv[0], "x", 1, MSG_NOSIGNAL) == -1 && Errno() == EPIPE,
      "send MSG_NOSIGNAL to a closed peer: EPIPE");
  close(sv[0]);
}

I32 Main()
{
  "Test - net basics: ";
  ByteOrder;
  ErrnoValues;
  FlagsAndPoll;
  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
