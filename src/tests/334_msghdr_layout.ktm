#include "testhelper.ktm"

/* msghdr had msg_iovlen as I32 and msg_controllen as I64 everywhere. On
 * Linux both are size_t, so the upper half of msg_iovlen was whatever was
 * in memory and sendmsg failed; on macOS msg_controllen is a socklen_t, so
 * msg_flags sat at the wrong offset. msg_namelen is a socklen_t (U32) on
 * both: read back after recvmsg it carried stale upper bytes. */

extern "c" I32 socketpair(I32 d, I32 t, I32 p, I32 *sv);

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

I64 Off(msghdr *m, U0 *field)
{
  return field(U8 *) - m(U8 *);
}

U0 Stale(msghdr *m)
{// What an uninitialised stack variable may hold
  MemSet(m, 0xAB, sizeof(msghdr));
}

I32 Main()
{
  msghdr m;
  iovec io[2];
  I32 sv[2];
  U8 buf[16];
  I64 n;
  "Test - msghdr layout: ";

#ifdef IS_LINUX
  Check(sizeof(msghdr) == 56, "sizeof(msghdr)");
  Check(Off(&m, &m.msg_controllen) == 40, "msg_controllen offset");
  Check(Off(&m, &m.msg_flags) == 48, "msg_flags offset");
#else
  Check(sizeof(msghdr) == 48, "sizeof(msghdr)");
  Check(Off(&m, &m.msg_controllen) == 40, "msg_controllen offset");
  Check(Off(&m, &m.msg_flags) == 44, "msg_flags offset");
#endif
  Check(Off(&m, &m.msg_iov) == 16, "msg_iov offset");
  Check(Off(&m, &m.msg_iovlen) == 24, "msg_iovlen offset");
  Check(Off(&m, &m.msg_control) == 32, "msg_control offset");

  /* sendmsg/recvmsg with two buffers, over a stale struct */
  Check(socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0, "socketpair");
  io[0].iov_base = "hel";
  io[0].iov_len = 3;
  io[1].iov_base = "lo";
  io[1].iov_len = 2;
  Stale(&m);
  m.msg_name = NULL; m.msg_namelen = 0;
  m.msg_iov = io; m.msg_iovlen = 2;
  m.msg_control = NULL; m.msg_controllen = 0; m.msg_flags = 0;
  n = sendmsg(sv[0], &m, 0);
  Check(n == 5, "sendmsg sends both buffers");

  MemSet(buf, 0, sizeof(buf));
  io[0].iov_base = buf;
  io[0].iov_len = 2;
  io[1].iov_base = buf + 2;
  io[1].iov_len = 10;
  Stale(&m);
  m.msg_name = NULL; m.msg_namelen = 0;
  m.msg_iov = io; m.msg_iovlen = 2;
  m.msg_control = NULL; m.msg_controllen = 0; m.msg_flags = 0;
  n = recvmsg(sv[1], &m, 0);
  Check(n == 5 && !StrCmp(buf, "hello"), "recvmsg fills both buffers");
  Check(m.msg_flags == 0, "msg_flags after recvmsg");
  close(sv[0]);
  close(sv[1]);

  /* recvmsg writes the sender's address length into msg_namelen */
  I32 u = socket(AF_INET, SOCK_DGRAM, 0);
  sockaddr_in me, from;
  U32 len = sizeof(sockaddr_in);
  MemSet(&me, 0, sizeof(sockaddr_in));
#ifndef IS_LINUX
  me.sin_len = sizeof(sockaddr_in);
#endif
  me.sin_family = AF_INET;
  inet_pton(AF_INET, "127.0.0.1", &me.sin_addr);
  Check(u != -1 && bind(u, (&me)(sockaddr *), sizeof(sockaddr_in)) == 0, "bind udp");
  getsockname(u, (&me)(sockaddr *), &len);
  Check(sendto(u, "ping", 4, 0, (&me)(sockaddr *), sizeof(sockaddr_in)) == 4, "sendto");
  MemSet(buf, 0, sizeof(buf));
  io[0].iov_base = buf;
  io[0].iov_len = sizeof(buf);
  Stale(&m);
  m.msg_name = &from; m.msg_namelen = sizeof(sockaddr_in);
  m.msg_iov = io; m.msg_iovlen = 1;
  m.msg_control = NULL; m.msg_controllen = 0; m.msg_flags = 0;
  n = recvmsg(u, &m, 0);
  Check(n == 4 && !StrCmp(buf, "ping"), "recvmsg udp");
  Check(m.msg_namelen == sizeof(sockaddr_in), "msg_namelen after recvmsg");
  Check(from.sin_port == me.sin_port, "sender address");
  close(u);

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
