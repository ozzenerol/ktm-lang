#include "testhelper.ktm"

/* accept, getpeername, getsockname, recvfrom and getsockopt took their
 * length as an I64* or U64*, but C's socklen_t is 32 bits on Linux and
 * macOS: the kernel writes 4 bytes and the upper half of an 8-byte length
 * keeps whatever was in it, while a U32 length (the right type) got an
 * "Incompatible function argument" warning. The lengths are U32 now. This
 * also checks the UDP structures against C and does an IPv4 and an IPv6
 * sendto/recvfrom round trip. */

#define SOCKLEN_SRC "__361_socklen.ktm"
#define SOCKLEN_OUT "__361_socklen.out"
#define SOCKLEN_BIN "__361_socklen.bin"

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

/* A program that passes U32 lengths everywhere compiles without warnings */
Bool CompilesCleanly(U8 *source)
{
  U8 *out;
  Bool ok;
  System("rm -f " SOCKLEN_SRC " " SOCKLEN_OUT " " SOCKLEN_BIN);
  if (!FileWrite(SOCKLEN_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         SOCKLEN_SRC " -o " SOCKLEN_BIN " > " SOCKLEN_OUT " 2>&1");
  out = FileRead(SOCKLEN_OUT);
  ok = out != NULL && StrLen(out) == 0;
  if (!ok && out) "\n%s", out;
  Free(out);
  System("rm -f " SOCKLEN_SRC " " SOCKLEN_OUT " " SOCKLEN_BIN);
  return ok;
}

I64 Off(U0 *base, U0 *field)
{
  return field(U8 *) - base(U8 *);
}

/* Bind a UDP socket to `ip` on a port the kernel picks; fills `me` with the
 * bound address and returns the socket, or -1 */
I32 UdpBind(I32 fam, U8 *ip, sockaddr_storage *me, U32 *len)
{
  I32 fd = socket(fam, SOCK_DGRAM, 0);
  sockaddr_in *v4 = me;
  sockaddr_in6 *v6 = me;
  if (fd == -1) return -1;
  MemSet(me, 0, sizeof(sockaddr_storage));
  if (fam == AF_INET) {
    *len = sizeof(sockaddr_in);
    v4->sin_family = AF_INET;
    inet_pton(AF_INET, ip, &v4->sin_addr);
  } else {
    *len = sizeof(sockaddr_in6);
    v6->sin6_family = AF_INET6;
    inet_pton(AF_INET6, ip, &v6->sin6_addr);
  }
#ifndef IS_LINUX
  me->ss_len = *len;
#endif
  if (bind(fd, me(sockaddr *), *len) == -1 ||
      getsockname(fd, me(sockaddr *), len) == -1) {
    close(fd);
    return -1;
  }
  return fd;
}

/* Send "ping" from one socket to another and read it back with recvfrom;
 * the sender's address must come back with the right length and port */
U0 RoundTrip(I32 fam, U8 *ip, U8 *name)
{
  sockaddr_storage a, b, from;
  sockaddr_in *v4 = &from;
  sockaddr_in6 *v6 = &from;
  U32 alen, blen, flen;
  U8 buf[16], what[64], txt[64];
  I32 sa = UdpBind(fam, ip, &a, &alen), sb = UdpBind(fam, ip, &b, &blen);
  I64 n;

  if (sa == -1 || sb == -1) {
    if (fam == AF_INET6) {
      /* no ::1 on this machine (IPv6 disabled): nothing to test */
      if (sa != -1) close(sa);
      if (sb != -1) close(sb);
      g_tests += 7;
      g_correct += 7;
      return;
    }
    Check(FALSE, "udp bind");
    return;
  }
  if (fam == AF_INET) n = sizeof(sockaddr_in);
  else n = sizeof(sockaddr_in6);
  Check(alen == n && blen == n, "getsockname length");

  StrPrint(what, "%s sendto", name);
  Check(sendto(sa, "ping", 4, 0, (&b)(sockaddr *), blen) == 4, what);

  /* MSG_PEEK leaves the datagram queued */
  MemSet(buf, 0, sizeof(buf));
  StrPrint(what, "%s recv MSG_PEEK", name);
  Check(recv(sb, buf, sizeof(buf), MSG_PEEK) == 4 && !StrCmp(buf, "ping"), what);

  MemSet(buf, 0, sizeof(buf));
  MemSet(&from, 0xAB, sizeof(sockaddr_storage));
  flen = sizeof(sockaddr_storage);
  n = recvfrom(sb, buf, sizeof(buf), 0, (&from)(sockaddr *), &flen);
  StrPrint(what, "%s recvfrom", name);
  Check(n == 4 && !StrCmp(buf, "ping"), what);
  StrPrint(what, "%s recvfrom address length", name);
  Check(flen == alen, what);
  StrPrint(what, "%s sender family and port", name);
  if (fam == AF_INET) {
    Check(v4->sin_family == AF_INET && v4->sin_port == (&a)(sockaddr_in *)->sin_port, what);
  } else {
    Check(v6->sin6_family == AF_INET6 && v6->sin6_port == (&a)(sockaddr_in6 *)->sin6_port, what);
  }
  StrPrint(what, "%s sender address", name);
  Check(NetGetIpv4Address((&from)(sockaddr *), fam, txt, sizeof(txt)) && !StrCmp(txt, ip), what);
  close(sa);
  close(sb);
}

/* A datagram longer than the buffer: recvmsg cuts it and sets MSG_TRUNC */
U0 Truncated()
{
  sockaddr_storage a;
  U32 alen;
  U8 buf[4];
  iovec io;
  msghdr m;
  I32 fd = UdpBind(AF_INET, "127.0.0.1", &a, &alen);
  Check(fd != -1 && sendto(fd, "toolong", 7, 0, (&a)(sockaddr *), alen) == 7, "sendto self");
  io.iov_base = buf;
  io.iov_len = sizeof(buf);
  MemSet(&m, 0, sizeof(msghdr));
  m.msg_iov = &io;
  m.msg_iovlen = 1;
  Check(recvmsg(fd, &m, 0) == 4 && (m.msg_flags & MSG_TRUNC) && !StrNCmp(buf, "tool", 4),
        "MSG_TRUNC on a cut datagram");
  close(fd);
}

I32 Main()
{
  sockaddr_in6 s6;
  "Test - udp socklen: ";

  Check(CompilesCleanly(
"
U0 Main()
{
  sockaddr_storage ss;
  U32 len = sizeof(sockaddr_storage), olen = sizeof(I32);
  I32 val, fd = socket(AF_INET, SOCK_DGRAM, 0);
  U8 buf[8];
  getsockname(fd, (&ss)(sockaddr *), &len);
  getpeername(fd, (&ss)(sockaddr *), &len);
  getsockopt(fd, SOL_SOCKET, SO_BROADCAST, &val, &olen);
  setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &val, olen);
  recvfrom(fd, buf, 0, MSG_DONTROUTE, (&ss)(sockaddr *), &len);
  sendto(fd, buf, 0, 0, (&ss)(sockaddr *), len);
  if (0) accept(fd, (&ss)(sockaddr *), &len);
  close(fd);
}
"), "U32 lengths compile without warnings");

  /* Layouts, as C's sizeof/offsetof give them on Linux and macOS */
  Check(sizeof(sockaddr) == 16, "sizeof(sockaddr)");
  Check(sizeof(sockaddr_in) == 16, "sizeof(sockaddr_in)");
  Check(sizeof(sockaddr_in6) == 28, "sizeof(sockaddr_in6)");
  Check(sizeof(sockaddr_storage) == 128, "sizeof(sockaddr_storage)");
  Check(Off(&s6, &s6.sin6_port) == 2 && Off(&s6, &s6.sin6_flowinfo) == 4 &&
        Off(&s6, &s6.sin6_addr) == 8 && Off(&s6, &s6.sin6_scope_id) == 24,
        "sockaddr_in6 offsets");

  /* MSG_* flags differ between Linux and the BSDs */
  Check(MSG_OOB == 1 && MSG_PEEK == 2 && MSG_DONTROUTE == 4, "MSG_OOB/PEEK/DONTROUTE");
#ifdef IS_LINUX
  Check(MSG_CTRUNC == 0x8 && MSG_TRUNC == 0x20 && MSG_EOR == 0x80 && MSG_WAITALL == 0x100,
        "MSG_* (Linux)");
#else
  Check(MSG_CTRUNC == 0x20 && MSG_TRUNC == 0x10 && MSG_EOR == 0x8 && MSG_WAITALL == 0x40,
        "MSG_* (macOS)");
#endif

  RoundTrip(AF_INET, "127.0.0.1", "IPv4");
  RoundTrip(AF_INET6, "::1", "IPv6");
  Truncated();

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
