#include "testhelper.ktm"

/* NetSetSocketNoDelay returned FALSE when setsockopt succeeded and TRUE
 * when it failed. It must return TRUE on success (and really set
 * TCP_NODELAY) and FALSE on failure. */

I64 GetIntOpt(I32 fd, I32 level, I32 opt)
{
  I32 val = -1;
  U32 len = sizeof(I32);
  if (getsockopt(fd, level, opt, &val, &len) == -1) return -1;
  return val;
}

I32 Main()
{
  "Test - NetSetSocketNoDelay result: ";
  I64 correct = 0, tests = 9;

  I32 fd = socket(AF_INET, SOCK_STREAM, 0);
  if (fd >= 0) correct++;

  /* Off by default, on after a successful call that returns TRUE. */
  if (GetIntOpt(fd, IPPROTO_TCP, TCP_NODELAY) == 0) correct++;
  if (NetSetSocketNoDelay(fd) == TRUE) correct++;
  if (GetIntOpt(fd, IPPROTO_TCP, TCP_NODELAY) != 0) correct++;
  /* Setting it again still succeeds. */
  if (NetSetSocketNoDelay(fd) == TRUE) correct++;

  /* Sibling setters keep their (already correct) results. */
  if (NetSetSockReuseAddr(fd) == TRUE &&
      GetIntOpt(fd, SOL_SOCKET, SO_REUSEADDR) != 0) correct++;
  if (NetSetSocketNonBlocking(fd) == TRUE &&
      (fcntl(fd, F_GETFL, 0) & O_NONBLOCK)) correct++;
  close(fd);

  /* Failure: an invalid and a closed descriptor give FALSE. */
  if (NetSetSocketNoDelay(-1) == FALSE) correct++;
  if (NetSetSocketNoDelay(fd) == FALSE &&
      NetSetSockReuseAddr(fd) == FALSE &&
      NetSetSocketNonBlocking(fd) == FALSE) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
