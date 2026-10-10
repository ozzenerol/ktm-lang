#include "testhelper.ktm"

/* NetConnect gave up when connecting to the first address a name resolved
 * to failed, instead of trying the next one: "localhost" resolving to ::1
 * and then 127.0.0.1 could not reach a server listening on 127.0.0.1 only
 * (NetCreateServer did the same when SO_REUSEADDR failed). The two
 * family checks need NetConnect to reach IPv6 addresses, ::1 to be
 * bindable and localhost to resolve to both families; otherwise they are
 * skipped. Only loopback addresses and kernel-chosen ports are used. */

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

I64 Port(I32 sockfd)
{
  sockaddr_storage ss;
  U32 len = sizeof(sockaddr_storage);
  if (sockfd == -1 || getsockname(sockfd, (&ss)(sockaddr *), &len) == -1) return -1;
  U16 p = (&ss)(sockaddr_in *)->sin_port;
  return ((p & 0xff) << 8) | (p >> 8);
}

I32 Listen6()
{// A server on ::1 only, -1 if IPv6 isn't there
  sockaddr_in6 a;
  I32 fd = socket(AF_INET6, SOCK_STREAM, 0);
  if (fd == -1) return -1;
  MemSet(&a, 0, sizeof(sockaddr_in6));
#ifndef IS_LINUX
  a.sin6_len = sizeof(sockaddr_in6);
#endif
  a.sin6_family = AF_INET6;
  inet_pton(AF_INET6, "::1", &a.sin6_addr);
  if (bind(fd, (&a)(sockaddr *), sizeof(sockaddr_in6)) == -1 || listen(fd, 4) == -1) {
    close(fd);
    return -1;
  }
  return fd;
}

Bool Resolves(U8 *name, I32 family)
{
  addrinfo hints, *res = NULL;
  MemSet(&hints, 0, sizeof(addrinfo));
  hints.ai_family = family;
  hints.ai_socktype = SOCK_STREAM;
  if (getaddrinfo(name, "80", &hints, &res) != 0) return FALSE;
  freeaddrinfo(res);
  return TRUE;
}

Bool Reach(U8 *name, I32 srv)
{// NetConnect(name) gets to srv
  I32 c = NetConnect(name, Port(srv));
  if (c == -1) return FALSE;
  I32 a = NetAccept(srv);
  Bool ok = a != -1;
  if (a != -1) close(a);
  close(c);
  return ok;
}

I32 LowestFreeFd()
{
  I32 fd = open("/dev/null", O_RDONLY);
  if (fd != -1) close(fd);
  return fd;
}

I32 Main()
{
  "Test - NetConnect tries every address: ";

  I32 s4 = NetCreateServer(0, "127.0.0.1", 4);
  Check(s4 != -1, "IPv4 server");
  Check(Reach("127.0.0.1", s4), "connect to 127.0.0.1");
  Check(Reach("localhost", s4), "localhost reaches an IPv4-only server");

  I32 s6 = Listen6();
  if (s6 != -1 && Reach("::1", s6) &&
      Resolves("localhost", AF_INET) && Resolves("localhost", AF_INET6)) {
    /* whichever family localhost lists first, both servers are reached */
    Check(Reach("localhost", s6), "localhost reaches an IPv6-only server");
    Check(Reach("localhost", s4), "localhost reaches an IPv4-only server again");
  }

  /* nobody listening on any of localhost's addresses: -1, nothing leaked */
  I64 port = Port(s4);
  close(s4);
  if (s6 != -1) close(s6);
  I32 fd_before = LowestFreeFd();
  Check(NetConnect("localhost", port) == -1, "closed port gives -1");
  Check(LowestFreeFd() == fd_before, "no socket leaked");

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
