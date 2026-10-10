#include "testhelper.ktm"

/* NetConnect's timeout_ms, NetSetSendTimeout and NetSetRecvTimeout on TCP.
 * Loopback only. A connect that never completes is made with a listening
 * socket whose accept queue is full: Linux and macOS then drop the SYN
 * instead of refusing it, so the connect stays pending. */

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s (Errno %d)\n", what, Errno();
}

Bool IsNonBlocking(I32 fd)
{
  return (fcntl(fd, F_GETFL, 0) & O_NONBLOCK) != 0;
}

/* A port nothing listens on */
I32 ClosedPort()
{
  I32 fd = NetCreateServer(0, "127.0.0.1", 1);
  I32 port = NetGetSocketPort(fd);
  close(fd);
  return port;
}

/* Fill the accept queue of `srv` with connects that are never accepted,
 * until one stays pending; returns how many sockets were used (in fds) */
I64 FillBacklog(I32 srv, I32 *fds, I64 max)
{
  sockaddr_in a;
  U32 len = sizeof(sockaddr_in);
  pollfd p;
  I64 i;
  getsockname(srv, (&a)(sockaddr *), &len);
  for (i = 0; i < max; i++) {
    fds[i] = socket(AF_INET, SOCK_STREAM, 0);
    fcntl(fds[i], F_SETFL, fcntl(fds[i], F_GETFL, 0) | O_NONBLOCK);
    connect(fds[i], (&a)(sockaddr *), len);
    p.fd = fds[i];
    p.events = POLLOUT;
    p.revents = 0;
    if (poll(&p, 1, 200) == 0) return i + 1;  /* this one is stuck */
  }
  return max;
}

I32 Main()
{
  I32 srv, cli, acc, port, fds[32];
  I64 t, n, i, nfill, total;
  U8 buf[65536];
  "Test - tcp timeouts: ";

  /* a connect with time to spare works, and the socket's mode is the one
   * asked for */
  srv = NetCreateServer(0, "127.0.0.1", 8);
  port = NetGetSocketPort(srv);
  cli = NetConnect("127.0.0.1", port, FALSE, 2000);
  Check(cli != -1 && !IsNonBlocking(cli), "connect with a timeout, blocking");
  acc = NetAccept(srv);
  Check(acc != -1 && NetSend(cli, "hi", 2) == 2 && recv(acc, buf, 2, 0) == 2,
        "data flows");
  close(acc);
  close(cli);
  cli = NetConnect("127.0.0.1", port, TRUE, 2000);
  Check(cli != -1 && IsNonBlocking(cli), "connect with a timeout, non-blocking");
  close(NetAccept(srv));
  close(cli);

  /* every address the name resolves to is tried: "localhost" may be ::1
   * first, which refuses, then 127.0.0.1 */
  cli = NetConnect("localhost", port, FALSE, 2000);
  Check(cli != -1, "localhost with a timeout");
  close(NetAccept(srv));
  close(cli);
  close(srv);

  /* a refused connect fails at once with ECONNREFUSED */
  t = NowMilliseconds;
  cli = NetConnect("127.0.0.1", ClosedPort(), FALSE, 2000);
  Check(cli == -1 && Errno() == ECONNREFUSED && NowMilliseconds - t < 1500,
        "refused");
  cli = NetConnect("127.0.0.1", ClosedPort());
  Check(cli == -1 && Errno() == ECONNREFUSED, "refused without a timeout");
  Check(NetConnect("127.0.0.1", 80, FALSE, -1) == -1 && Errno() == EINVAL,
        "negative timeout");

  /* a connect that never completes gives up with ETIMEDOUT */
  srv = NetCreateServer(0, "127.0.0.1", 1);
  port = NetGetSocketPort(srv);
  nfill = FillBacklog(srv, fds, 32);
  t = NowMilliseconds;
  cli = NetConnect("127.0.0.1", port, FALSE, 300);
  t = NowMilliseconds - t;
  Check(cli == -1 && Errno() == ETIMEDOUT, "connect times out");
  Check(t >= 250 && t < 3000, "after about the timeout");
  if (cli != -1) close(cli);
  for (i = 0; i < nfill; i++) close(fds[i]);
  close(srv);

  /* recv on a TCP socket times out with EAGAIN */
  srv = NetCreateServer(0, "127.0.0.1", 8);
  cli = NetConnect("127.0.0.1", NetGetSocketPort(srv));
  acc = NetAccept(srv);
  Check(NetSetRecvTimeout(cli, 150), "NetSetRecvTimeout on TCP");
  t = NowMilliseconds;
  n = recv(cli, buf, 16, 0);
  t = NowMilliseconds - t;
  Check(n == -1 && Errno() == EAGAIN && t >= 100 && t < 3000, "recv times out");

  /* send times out with EAGAIN once the peer stops reading */
  Check(NetSetSendTimeout(cli, 150), "NetSetSendTimeout");
  Check(!NetSetSendTimeout(cli, -1) && NetSetSendTimeout(cli, 150),
        "negative send timeout");
  t = NowMilliseconds;
  total = 0;
  n = 0;
  while (n != -1 && NowMilliseconds - t < 10000) {
    n = NetSend(cli, buf, sizeof(buf));
    if (n > 0) total += n;
  }
  Check(n == -1 && Errno() == EAGAIN && total > 0, "send times out");
  Check(NowMilliseconds - t < 10000, "send gave up in time");
  close(acc);
  close(cli);
  close(srv);

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
