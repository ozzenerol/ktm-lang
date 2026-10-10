#include "testhelper.ktm"

/* NetCreateServer and NetConnect always asked getaddrinfo for AF_INET:
 * the addrfam argument was only looked at for "::*", so a server could
 * not listen on ::1 and NetConnect could not reach an IPv6 address. A
 * numeric bind address that isn't valid ("256.1.1.1") went to DNS.
 * Only loopback addresses and kernel-chosen ports are used; the IPv6
 * checks are skipped when ::1 can't be bound. */

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

I64 g_port;

I64 Family(I32 sockfd)
{// The family of the socket's local address; sets g_port
  sockaddr_storage ss;
  U32 len = sizeof(sockaddr_storage);
  MemSet(&ss, 0, sizeof(sockaddr_storage));
  if (getsockname(sockfd, (&ss)(sockaddr *), &len) == -1) return -1;
  U16 p = (&ss)(sockaddr_in *)->sin_port; /* same offset in sockaddr_in6 */
  g_port = ((p & 0xff) << 8) | (p >> 8);
  return ss.ss_family;
}

Bool Ipv6Loopback()
{
  sockaddr_in6 a;
  I32 fd = socket(AF_INET6, SOCK_STREAM, 0);
  if (fd == -1) return FALSE;
  MemSet(&a, 0, sizeof(sockaddr_in6));
  a.sin6_family = AF_INET6;
  inet_pton(AF_INET6, "::1", &a.sin6_addr);
  Bool ok = bind(fd, (&a)(sockaddr *), sizeof(sockaddr_in6)) == 0;
  close(fd);
  return ok;
}

Bool RoundTrip(I32 srv, U8 *addr)
{// NetConnect to the server's port at addr, send a byte through
  U8 b = 0;
  I32 c = NetConnect(addr, g_port);
  if (c == -1) return FALSE;
  I32 a = NetAccept(srv);
  Bool ok = a != -1 && write(c, "x", 1) == 1 && read(a, &b, 1) == 1 && b == 'x';
  if (a != -1) close(a);
  close(c);
  return ok;
}

U0 CloseIfOpen(I32 fd)
{
  if (fd != -1) close(fd);
}

I32 Main()
{
  I32 s;
  "Test - Net address families: ";

  s = NetCreateServer(0, "127.0.0.1", 4, AF_INET);
  Check(s != -1 && Family(s) == AF_INET, "127.0.0.1 with AF_INET");
  Check(RoundTrip(s, "127.0.0.1"), "connect to 127.0.0.1");
  CloseIfOpen(s);

  s = NetCreateServer(0, "127.0.0.1", 4);
  Check(s != -1 && Family(s) == AF_INET, "127.0.0.1 with AF_UNSPEC");
  CloseIfOpen(s);

  s = NetCreateServer(0, "127.0.0.1", 4, AF_INET6);
  Check(s == -1, "127.0.0.1 with AF_INET6 fails");
  CloseIfOpen(s);
  s = NetCreateServer(0, "::*", 4, AF_INET);
  Check(s == -1, "::* with AF_INET fails");
  CloseIfOpen(s);
  s = NetCreateServer(0, "127.0.0.1", 4, AF_UNIX);
  Check(s == -1, "AF_UNIX is not a TCP family");
  CloseIfOpen(s);
  s = NetCreateServer(0, "127.0.0.1", 4, 12345);
  Check(s == -1, "unknown family");
  CloseIfOpen(s);

  /* a bind address is numeric: no DNS lookup for these */
  s = NetCreateServer(0, "256.1.1.1", 4);
  Check(s == -1, "256.1.1.1");
  CloseIfOpen(s);
  s = NetCreateServer(0, "not-an-address!", 4);
  Check(s == -1, "a host name");
  CloseIfOpen(s);

  if (Ipv6Loopback()) {
    s = NetCreateServer(0, "::1", 4, AF_INET6);
    Check(s != -1 && Family(s) == AF_INET6, "::1 with AF_INET6");
    Check(RoundTrip(s, "::1"), "connect to ::1");
    CloseIfOpen(s);

    s = NetCreateServer(0, "::1", 4);
    Check(s != -1 && Family(s) == AF_INET6, "::1 with AF_UNSPEC");
    Check(RoundTrip(s, "::1"), "connect to ::1 again");
    CloseIfOpen(s);

    s = NetCreateServer(0, "::1", 4, AF_INET);
    Check(s == -1, "::1 with AF_INET fails");
    CloseIfOpen(s);
  }

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
