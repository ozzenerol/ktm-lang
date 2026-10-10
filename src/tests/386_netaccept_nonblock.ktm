#include "testhelper.ktm"

/* On macOS BSD's accept() copies O_NONBLOCK from the listening socket, so
 * NetAccept(srv) on a non-blocking listener returned a non-blocking socket
 * (recv then gave -1 EAGAIN instead of waiting); Linux's accept doesn't.
 * NetAccept now sets the accepted socket's mode from non_blocking on every
 * OS. Checks all four listener x non_blocking combinations, that
 * FD_CLOEXEC is kept and that the socket carries data. */

Bool IsNonBlocking(I32 fd)
{
  return (fcntl(fd, F_GETFL, 0) & O_NONBLOCK) != 0;
}

/* Accept one connection with the listener (non-)blocking and NetAccept's
 * non_blocking, and check the result */
Bool AcceptMode(Bool listener_nb, Bool non_blocking)
{
  I32 srv, cli, acc;
  pollfd p;
  U8 buf[8];
  Bool ok = TRUE;

  srv = NetCreateServer(0, "127.0.0.1", 4, AF_INET, listener_nb);
  if (srv == -1) return FALSE;
  if (IsNonBlocking(srv) != listener_nb) ok = FALSE;
  cli = NetConnect("127.0.0.1", NetGetSocketPort(srv));
  if (cli == -1) {
    close(srv);
    return FALSE;
  }
  p.fd = srv;
  p.events = POLLIN;
  p.revents = 0;
  poll(&p, 1, 5000);

  acc = NetAccept(srv, non_blocking);
  if (acc == -1) {
    ok = FALSE;
  } else {
    if (IsNonBlocking(acc) != non_blocking) {
      "listener %d, non_blocking %d: accepted socket non-blocking %d\n",
        listener_nb, non_blocking, IsNonBlocking(acc);
      ok = FALSE;
    }
    if (!(fcntl(acc, F_GETFD, 0) & FD_CLOEXEC)) ok = FALSE;
    /* data goes through; a blocking socket waits for it */
    NetSend(cli, "ping", 4);
    if (non_blocking) {
      p.fd = acc;
      p.events = POLLIN;
      p.revents = 0;
      poll(&p, 1, 5000);
    }
    if (recv(acc, buf, 4, 0) != 4 || memcmp(buf, "ping", 4)) ok = FALSE;
    close(acc);
  }
  close(cli);
  close(srv);
  return ok;
}

I32 Main()
{
  "Test - NetAccept sets the accepted socket's mode: ";
  I64 correct = 0, tests = 0;

  tests++; if (AcceptMode(FALSE, FALSE)) correct++;
  else "Failed - blocking listener, blocking accept\n";
  tests++; if (AcceptMode(FALSE, TRUE)) correct++;
  else "Failed - blocking listener, non-blocking accept\n";
  tests++; if (AcceptMode(TRUE, FALSE)) correct++;
  else "Failed - non-blocking listener, blocking accept\n";
  tests++; if (AcceptMode(TRUE, TRUE)) correct++;
  else "Failed - non-blocking listener, non-blocking accept\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
