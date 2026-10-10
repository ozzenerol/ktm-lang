#include "testhelper.ktm"

/* NetGetIpv4AddressFromAcceptedSocket read the peer address into a
 * 16-byte sockaddr, which cuts a sockaddr_in6 short, and NetGetInAddress
 * then read sin6_addr past the end of it: an IPv6 peer came out as
 * garbage. Any family other than AF_INET was treated as AF_INET6. Only
 * loopback, kernel-chosen ports and a mkdtemp directory are used; the
 * IPv6 checks are skipped when ::1 can't be bound. */

extern "c" U8 *mkdtemp(U8 *tmpl);
extern "c" I32 getpid();

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

I32 g_srv, g_cli, g_acc;

Bool Pair(I32 family)
{// A listening loopback socket of `family`, a client and the accepted end
  sockaddr_storage ss;
  U32 len = sizeof(sockaddr_storage);
  MemSet(&ss, 0, sizeof(sockaddr_storage));
  g_srv = g_cli = g_acc = -1;
  if (family == AF_INET6) {
    sockaddr_in6 *a6 = (&ss)(sockaddr_in6 *);
#ifndef IS_LINUX
    a6->sin6_len = sizeof(sockaddr_in6);
#endif
    a6->sin6_family = AF_INET6;
    inet_pton(AF_INET6, "::1", &a6->sin6_addr);
    len = sizeof(sockaddr_in6);
  } else {
    sockaddr_in *a4 = (&ss)(sockaddr_in *);
#ifndef IS_LINUX
    a4->sin_len = sizeof(sockaddr_in);
#endif
    a4->sin_family = AF_INET;
    inet_pton(AF_INET, "127.0.0.1", &a4->sin_addr);
    len = sizeof(sockaddr_in);
  }
  if ((g_srv = socket(family, SOCK_STREAM, 0)) == -1) return FALSE;
  if (bind(g_srv, (&ss)(sockaddr *), len) == -1) return FALSE;
  if (listen(g_srv, 4) == -1) return FALSE;
  if (getsockname(g_srv, (&ss)(sockaddr *), &len) == -1) return FALSE;
  if ((g_cli = socket(family, SOCK_STREAM, 0)) == -1) return FALSE;
  if (connect(g_cli, (&ss)(sockaddr *), len) == -1) return FALSE;
  g_acc = NetAccept(g_srv);
  return g_acc != -1;
}

U0 ClosePair()
{
  if (g_acc != -1) close(g_acc);
  if (g_cli != -1) close(g_cli);
  if (g_srv != -1) close(g_srv);
}

I32 Main()
{
  U8 buf[64];
  "Test - peer address of an accepted socket: ";

  Check(Pair(AF_INET), "IPv4 pair");
  MemSet(buf, 0, sizeof(buf));
  Check(NetGetIpv4AddressFromAcceptedSocket(g_acc, buf, sizeof(buf)) &&
        !StrCmp(buf, "127.0.0.1"), "IPv4 peer");
  ClosePair();

  if (Pair(AF_INET6)) {
    MemSet(buf, 0, sizeof(buf));
    Check(NetGetIpv4AddressFromAcceptedSocket(g_acc, buf, sizeof(buf)) &&
          !StrCmp(buf, "::1"), "IPv6 peer");
    MemSet(buf, 0, sizeof(buf));
    Check(!NetGetIpv4AddressFromAcceptedSocket(g_acc, buf, 3), "buffer too small");
  }
  ClosePair();

  /* a unix domain peer has no IP address */
  U8 dir[64], path[128];
  StrCpy(dir, "./ktm335-XXXXXX");
  Check(mkdtemp(dir) != NULL, "mkdtemp");
  StrPrint(path, "%s/s.sock", dir);
  I32 us = NetCreateUnixServerSocket(path, 4);
  I32 uc = NetCreateUnixClientSocket(path);
  I32 ua = NetAccept(us);
  Check(us != -1 && uc != -1 && ua != -1, "unix pair");
  StrCpy(buf, "untouched");
  Check(!NetGetIpv4AddressFromAcceptedSocket(ua, buf, sizeof(buf)), "unix peer is FALSE");
  Check(!NetGetIpv4AddressFromAcceptedSocket(-1, buf, sizeof(buf)), "bad socket is FALSE");
  close(ua); close(uc); close(us);
  unlink(path);
  rmdir(dir);

  /* only AF_INET and AF_INET6 addresses are read */
  sockaddr_storage ss;
  MemSet(&ss, 0x7f, sizeof(sockaddr_storage));
  ss.ss_family = AF_UNIX;
  Check(!NetGetIpv4Address((&ss)(sockaddr *), AF_INET6, buf, sizeof(buf)), "AF_UNIX address");
  Check(!NetGetIpv4Address((&ss)(sockaddr *), AF_INET, buf, sizeof(buf)), "family mismatch");
  ss.ss_family = AF_INET;
  inet_pton(AF_INET, "10.1.2.3", &(&ss)(sockaddr_in *)->sin_addr);
  Check(NetGetIpv4Address((&ss)(sockaddr *), AF_INET, buf, sizeof(buf)) &&
        !StrCmp(buf, "10.1.2.3"), "AF_INET address");

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
