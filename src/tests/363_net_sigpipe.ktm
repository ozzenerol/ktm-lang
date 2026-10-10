#include "testhelper.ktm"

/* Writing to a socket whose peer had closed raised SIGPIPE, which ended
 * the program, and there was no way to avoid it: no Net* send helper,
 * no MSG_NOSIGNAL and no signal(). NetSend now passes MSG_NOSIGNAL, and
 * signal(SIGPIPE, SIG_IGN) is available for plain write(). Reaching the
 * end of this test at all is the main check. */

extern "c" I32 socketpair(I32 d, I32 t, I32 p, I32 *sv);

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

/* The repro from the known-issues page: close the accepted socket, then
 * write to the client end twice */
U0 TcpClosedPeer()
{
  I32 srv, cli, conn, i;
  U32 len = sizeof(sockaddr_in);
  I64 rv = 0;
  sockaddr_in sa;

  srv = NetCreateServer(0, "127.0.0.1", 4);
  Check(srv != -1, "NetCreateServer");
  Check(getsockname(srv, (sockaddr *)&sa, &len) == 0, "getsockname");
  cli = NetConnect("127.0.0.1", Ntohs(sa.sin_port));
  Check(cli != -1, "NetConnect");
  conn = NetAccept(srv);
  Check(conn != -1, "NetAccept");
  close(conn);

  /* The first write may still succeed (the peer answers it with a RST),
   * a later one fails */
  for (i = 0; i < 50 && rv != -1; i++) {
    rv = NetSend(cli, "x", 1);
    if (rv != -1) poll(NULL, 0, 10);   // 10 ms
  }
  Check(rv == -1, "NetSend to a closed peer returns -1");
  Check(Errno() == EPIPE || Errno() == ECONNRESET,
      "NetSend: EPIPE or ECONNRESET");
  close(cli);
  close(srv);
}

U0 UnixClosedPeer()
{
  I32 sv[2];
  Check(socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0, "socketpair");
  Check(NetSend(sv[0], "ok", 2) == 2, "NetSend to an open peer");
  close(sv[1]);
  Check(NetSend(sv[0], "x", 1) == -1 && Errno() == EPIPE,
      "NetSend to a closed peer: EPIPE");
  /* Caller flags are kept */
  Check(NetSend(sv[0], "x", 1, MSG_DONTWAIT) == -1 && Errno() == EPIPE,
      "NetSend with flags: EPIPE");
  close(sv[0]);
}

U0 IgnoreSigpipe()
{
  I32 sv[2];
  Check(signal(SIGPIPE, SIG_IGN) != SIG_ERR, "signal(SIGPIPE, SIG_IGN)");
  Check(socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0, "socketpair 2");
  close(sv[1]);
  Check(write(sv[0], "x", 1) == -1 && Errno() == EPIPE,
      "write with SIGPIPE ignored: EPIPE");
  close(sv[0]);
  Check(signal(SIGPIPE, SIG_DFL) == SIG_IGN,
      "signal returns the previous handler");
}

I32 Main()
{
  "Test - net sigpipe: ";
  TcpClosedPeer;
  UnixClosedPeer;
  IgnoreSigpipe;
  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
