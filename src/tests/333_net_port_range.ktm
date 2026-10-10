#include "testhelper.ktm"

/* NetCreateServer and NetConnect printed the port into a 6-byte buffer, so
 * a port above 65535 was cut to its first five digits (212345 listened on
 * 21234) and 65536 or a negative port was not rejected. Ports outside
 * 0..65535 now give -1. Only 127.0.0.1 and kernel-chosen ports are used; the server checks
 * pick values whose cut-off form is that port, so even the bug never
 * listens anywhere else. */

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

I64 LocalPort(I32 sockfd)
{
  sockaddr_in sa;
  U32 len = sizeof(sockaddr_in);
  if (getsockname(sockfd, (&sa)(sockaddr *), &len) == -1) return -1;
  U16 p = sa.sin_port;
  return ((p & 0xff) << 8) | (p >> 8);
}

U0 CloseIfOpen(I32 fd)
{
  if (fd != -1) close(fd);
}

I32 Main()
{
  I32 srv, s, c;
  I64 port;
  "Test - Net port range: ";

  /* port 0 still means "any free port" */
  srv = NetCreateServer(0, "127.0.0.1", 4);
  Check(srv != -1, "NetCreateServer on port 0");
  port = LocalPort(srv);
  Check(port > 0 && port <= 65535, "kernel picked a port");

  /* a too long port must not connect to its first five digits */
  c = NetConnect("127.0.0.1", port * 10 + 7);
  Check(c == -1, "NetConnect to port * 10 + 7");
  CloseIfOpen(c);
  c = NetConnect("127.0.0.1", port + 65536);
  Check(c == -1, "NetConnect to port + 65536");
  CloseIfOpen(c);
  c = NetConnect("127.0.0.1", 65536);
  Check(c == -1, "NetConnect to port 65536");
  CloseIfOpen(c);
  c = NetConnect("127.0.0.1", -1);
  Check(c == -1, "NetConnect to port -1");
  CloseIfOpen(c);
  c = NetConnect("127.0.0.1", port);
  Check(c != -1, "NetConnect to the real port");
  CloseIfOpen(c);
  CloseIfOpen(srv);

  /* a server must not listen on the first five digits either: the port
   * is free again (just closed), so a cut port would bind */
  s = NetCreateServer(port * 10 + 7, "127.0.0.1", 4);
  Check(s == -1, "NetCreateServer on port * 10 + 7");
  CloseIfOpen(s);
  s = NetCreateServer(-5, "127.0.0.1", 4);
  Check(s == -1, "NetCreateServer on -5");
  CloseIfOpen(s);
  s = NetCreateServer(-2147483648, "127.0.0.1", 4);
  Check(s == -1, "NetCreateServer on I32 min");
  CloseIfOpen(s);

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
