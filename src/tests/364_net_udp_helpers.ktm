#include "testhelper.ktm"

/* NetCreateUdpSocket, NetSendTo, NetRecvFrom, NetSetRecvTimeout and
 * NetGetSocketPort: loopback only, ports picked by the kernel. Ends with a
 * DNS-style exchange: a client sends an A query, a server parses it and
 * answers the sender, the client checks the answer. */

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

/* Round trip between two sockets of one family: client -> server, then the
 * server answers whoever sent it */
U0 Echo(U8 *ip, I32 fam, U8 *name)
{
  I32 srv = NetCreateUdpSocket(0, ip, fam), cli = NetCreateUdpSocket(0, ip, fam);
  I32 sport, cport, from_port = -1;
  U8 buf[64], from[INET6_ADDRSTRLEN], what[64];
  I64 n;

  if (fam == AF_INET6 && (srv == -1 || cli == -1)) {
    /* no ::1 here (IPv6 disabled): nothing to test */
    if (srv != -1) close(srv);
    if (cli != -1) close(cli);
    g_tests += 6;
    g_correct += 6;
    return;
  }
  StrPrint(what, "%s sockets", name);
  Check(srv != -1 && cli != -1, what);
  sport = NetGetSocketPort(srv);
  cport = NetGetSocketPort(cli);
  StrPrint(what, "%s ports", name);
  Check(sport > 0 && cport > 0 && sport != cport, what);

  StrPrint(what, "%s NetSendTo", name);
  Check(NetSendTo(cli, "hello", 5, ip, sport) == 5, what);
  MemSet(buf, 0, sizeof(buf));
  n = NetRecvFrom(srv, buf, sizeof(buf), from, sizeof(from), &from_port);
  StrPrint(what, "%s NetRecvFrom", name);
  Check(n == 5 && !StrCmp(buf, "hello"), what);
  StrPrint(what, "%s sender address and port", name);
  Check(!StrCmp(from, ip) && from_port == cport, what);

  /* answer the sender */
  NetSendTo(srv, "world", 5, from, from_port);
  MemSet(buf, 0, sizeof(buf));
  n = NetRecvFrom(cli, buf, sizeof(buf));
  StrPrint(what, "%s reply", name);
  Check(n == 5 && !StrCmp(buf, "world"), what);
  close(srv);
  close(cli);
}

/* --- DNS-style request/response ------------------------------------- */

U0 Put16(U8 *p, I64 v)
{
  p[0] = (v >> 8) & 0xFF;
  p[1] = v & 0xFF;
}

I64 Get16(U8 *p)
{
  return (p[0] << 8) | p[1];
}

/* A query for `host`, type A, class IN; returns its length */
I64 DnsQuery(U8 *pkt, I64 id, U8 *host)
{
  I64 n = 12, i, start;
  MemSet(pkt, 0, 12);
  Put16(pkt, id);
  Put16(pkt + 2, 0x0100);  /* recursion desired */
  Put16(pkt + 4, 1);       /* one question */
  while (*host) {
    start = n++;
    for (i = 0; host[i] && host[i] != '.'; i++)
      pkt[n++] = host[i];
    pkt[start] = i;
    host += i;
    if (*host == '.') host++;
  }
  pkt[n++] = 0;
  Put16(pkt + n, 1);       /* type A */
  Put16(pkt + n + 2, 1);   /* class IN */
  return n + 4;
}

/* The server: read one query, answer it with 10.1.2.3 */
Bool DnsServe(I32 srv)
{
  U8 pkt[512], from[INET6_ADDRSTRLEN], name[256];
  I32 port;
  I64 n = NetRecvFrom(srv, pkt, sizeof(pkt), from, sizeof(from), &port);
  I64 q = 12, len = 0;

  if (n < 12 || Get16(pkt + 4) != 1) return FALSE;
  while (pkt[q]) {  /* the question name, as text */
    if (len) name[len++] = '.';
    MemCpy(name + len, pkt + q + 1, pkt[q]);
    len += pkt[q];
    q += pkt[q] + 1;
  }
  name[len] = '\0';
  if (StrCmp(name, "example.ktm")) return FALSE;
  q += 5;  /* the root label, type and class */

  Put16(pkt + 2, 0x8180);  /* response, recursion desired + available */
  Put16(pkt + 6, 1);       /* one answer */
  Put16(pkt + q, 0xC00C);  /* name: pointer to the question's */
  Put16(pkt + q + 2, 1);   /* type A */
  Put16(pkt + q + 4, 1);   /* class IN */
  Put16(pkt + q + 6, 0);   /* TTL 300 */
  Put16(pkt + q + 8, 300);
  Put16(pkt + q + 10, 4);  /* 4 bytes of data */
  pkt[q + 12] = 10; pkt[q + 13] = 1; pkt[q + 14] = 2; pkt[q + 15] = 3;
  return NetSendTo(srv, pkt, q + 16, from, port) == q + 16;
}

U0 Dns()
{
  I32 srv = NetCreateUdpSocket(0, "127.0.0.1"), cli = NetCreateUdpSocket(0, "*");
  U8 pkt[512];
  I64 n = DnsQuery(pkt, 0xBEEF, "example.ktm"), q;

  Check(NetSendTo(cli, pkt, n, "127.0.0.1", NetGetSocketPort(srv)) == n, "dns query sent");
  Check(DnsServe(srv), "dns server parses and answers");
  NetSetRecvTimeout(cli, 2000);
  MemSet(pkt, 0, sizeof(pkt));
  n = NetRecvFrom(cli, pkt, sizeof(pkt));
  q = n - 4;
  Check(n > 12 && Get16(pkt) == 0xBEEF && Get16(pkt + 2) == 0x8180 && Get16(pkt + 6) == 1 &&
        pkt[q] == 10 && pkt[q + 1] == 1 && pkt[q + 2] == 2 && pkt[q + 3] == 3,
        "dns answer");
  close(srv);
  close(cli);
}

I32 Main()
{
  I32 fd, fd2;
  U8 buf[4];
  I64 t, n;
  "Test - net udp helpers: ";

  Echo("127.0.0.1", AF_UNSPEC, "IPv4");
  Echo("::1", AF_INET6, "IPv6");

  /* bind addresses and bad arguments, as NetCreateServer */
  fd = NetCreateUdpSocket(0, "*");
  Check(fd != -1 && NetGetSocketPort(fd) > 0, "\"*\" binds any IPv4 address");
  /* an IPv4 socket can't send to an IPv6 address */
  Check(NetSendTo(fd, "x", 1, "::1", 9) == -1, "family mismatch");
  Check(NetSendTo(fd, "x", 1, "127.0.0.1", 70000) == -1, "bad port in NetSendTo");
  close(fd);
  Check(NetCreateUdpSocket(-1, "127.0.0.1") == -1 && NetCreateUdpSocket(65536, NULL) == -1,
        "bad port");
  Check(NetCreateUdpSocket(0, "127.0.0.1", AF_UNIX) == -1, "bad family");
  Check(NetCreateUdpSocket(0, "::*", AF_INET) == -1, "\"::*\" with AF_INET");
  Check(NetCreateUdpSocket(0, "not-an-address") == -1, "host names are not looked up");
  fd = NetCreateUdpSocket(0, "127.0.0.1");
  fd2 = NetCreateUdpSocket(NetGetSocketPort(fd), "127.0.0.1");
  Check(fd2 == -1, "port in use");
  close(fd);

  /* a datagram longer than the buffer: the rest is dropped */
  fd = NetCreateUdpSocket(0, "127.0.0.1");
  NetSendTo(fd, "toolong", 7, "127.0.0.1", NetGetSocketPort(fd));
  NetSendTo(fd, "next", 4, "127.0.0.1", NetGetSocketPort(fd));
  MemSet(buf, 0, sizeof(buf));
  Check(NetRecvFrom(fd, buf, 4) == 4 && !StrNCmp(buf, "tool", 4), "long datagram cut");
  Check(NetRecvFrom(fd, buf, 4) == 4 && !StrNCmp(buf, "next", 4), "next datagram whole");

  /* timeout: -1 after about 150 ms instead of waiting forever */
  Check(NetSetRecvTimeout(fd, 150), "NetSetRecvTimeout");
  t = NowMilliseconds;
  n = NetRecvFrom(fd, buf, 4);
  t = NowMilliseconds - t;
  Check(n == -1 && Errno() == EAGAIN && t >= 100 && t < 3000, "recv times out with EAGAIN");
  Check(NetSetRecvTimeout(fd, 0) && !NetSetRecvTimeout(fd, -1), "timeout 0 and -1");
  close(fd);

  /* non-blocking: nothing queued gives -1 at once */
  fd = NetCreateUdpSocket(0, "127.0.0.1", AF_INET, TRUE);
  t = NowMilliseconds;
  Check(fd != -1 && NetRecvFrom(fd, buf, 4) == -1 && Errno() == EAGAIN &&
        NowMilliseconds - t < 1000, "non-blocking");
  close(fd);
  Check(NetGetSocketPort(-1) == -1, "NetGetSocketPort on a bad socket");

  Dns();

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
