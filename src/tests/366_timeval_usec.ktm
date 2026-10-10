#include "testhelper.ktm"

/* timeval.tv_usec was an I32 everywhere, but Linux's is a long: the upper
 * half of a stack timeval was whatever was in memory, so setsockopt
 * SO_RCVTIMEO (and select, utimes) got a garbage microsecond count and
 * failed. macOS has an int plus 4 bytes of padding. */

#ifdef IS_LINUX
#define TEST_SO_RCVTIMEO 20
#else
#define TEST_SO_RCVTIMEO 0x1006
#endif

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

U0 Stale(timeval *tv)
{// What an uninitialised stack variable may hold
  MemSet(tv, 0xAB, sizeof(timeval));
}

I32 Main()
{
  timeval tv, got;
  U32 len = sizeof(timeval);
  I32 fd;
  Bool set;
  U8 buf[4];
  "Test - timeval tv_usec: ";

  Check(sizeof(timeval) == 16, "sizeof(timeval)");
  Check((&tv.tv_usec)(U8 *) - (&tv)(U8 *) == 8, "tv_usec offset");
#ifdef IS_LINUX
  Check(sizeof(tv.tv_usec) == 8, "tv_usec is a long on Linux");
#else
  Check(sizeof(tv.tv_usec) == 4, "tv_usec is an int on macOS");
#endif

  /* gettimeofday fills the whole field */
  Stale(&tv);
  Check(gettimeofday(&tv, NULL) == 0, "gettimeofday");
  Check(tv.tv_usec >= 0 && tv.tv_usec < 1000000, "gettimeofday tv_usec range");

  /* A receive timeout from a stale timeval */
  fd = socket(AF_INET, SOCK_DGRAM, 0);
  Check(fd != -1, "socket");
  Stale(&tv);
  tv.tv_sec = 0;
  tv.tv_usec = 200000;
  set = setsockopt(fd, SOL_SOCKET, TEST_SO_RCVTIMEO, &tv, sizeof(timeval)) == 0;
  Check(set, "setsockopt SO_RCVTIMEO with a stale timeval");
  Stale(&got);
  Check(getsockopt(fd, SOL_SOCKET, TEST_SO_RCVTIMEO, &got, &len) == 0,
      "getsockopt SO_RCVTIMEO");
  Check(got.tv_sec == 0 && got.tv_usec >= 190000 && got.tv_usec <= 210000,
      "the timeout reads back as 0.2 s");
  /* Unbound UDP socket, nothing to read: recv gives up after the timeout */
  Check(set && recv(fd, buf, 4, 0) == -1, "recv times out"); // or blocks forever
  close(fd);

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
