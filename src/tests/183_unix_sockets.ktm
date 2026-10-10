#include "testhelper.ktm"

/* The socket address classes had the BSD layout everywhere (a leading
 * length byte), so on Linux the family landed in the wrong byte: unix
 * domain sockets never bound and an accepted socket's IPv4 address
 * couldn't be read. The unix helpers also overflowed sun_path on long
 * paths. std.ktmh had the BSD addrinfo order, the macOS values of
 * SOL_SOCKET, SO_xxx, TCP_xxx and AI_NUMERICSERV, and no IPPROTO_TCP. */

extern "c" I32 getpid();

I64 g_correct = 0;
U0 Check(Bool ok, U8 *what)
{
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

I32 Main()
{
  I64 tests = 28, i, port;
  U32 len;
  I32 srv, cli, acc, one = 1, seven = 7, got = 0;
  U8 path[256], longpath[256], ip[64], msg[8];
  sockaddr_un un;
  sockaddr_in sin;
  sockaddr_in6 sin6;
  "Test - unix domain sockets and socket address layouts: ";

  /* sizes and offsets of the C structs */
  Check(sizeof(sockaddr) == 16, "sizeof(sockaddr)");
  Check(sizeof(sockaddr_in) == 16, "sizeof(sockaddr_in)");
  Check(sizeof(sockaddr_in6) == 28, "sizeof(sockaddr_in6)");
  Check(sizeof(sockaddr_storage) == 128, "sizeof(sockaddr_storage)");
  Check((&sin.sin_port)(U8 *) - (&sin)(U8 *) == 2, "sin_port offset");
  Check((&sin.sin_addr)(U8 *) - (&sin)(U8 *) == 4, "sin_addr offset");
  Check((&sin6.sin6_addr)(U8 *) - (&sin6)(U8 *) == 8, "sin6_addr offset");
#ifdef IS_LINUX
  Check(sizeof(sockaddr_un) == 110, "sizeof(sockaddr_un)");
  Check((&un.sun_path)(U8 *) - (&un)(U8 *) == 2, "sun_path offset");
#else
  Check(sizeof(sockaddr_un) == 106, "sizeof(sockaddr_un)");
  Check((&un.sun_path)(U8 *) - (&un)(U8 *) == 2, "sun_path offset");
#endif

  /* a real AF_UNIX server and client in one process */
  StrPrint(path, "./ktm183-%d.sock", getpid);
  srv = NetCreateUnixServerSocket(path, 4);
  Check(srv > 0, "unix server");
  cli = NetCreateUnixClientSocket(path);
  Check(cli > 0, "unix client");
  acc = accept(srv, NULL, NULL);
  Check(acc > 0, "accept");
  Check(write(cli, "ping", 4) == 4 && read(acc, msg, 4) == 4 && !StrNCmp(msg, "ping", 4), "client -> server");
  Check(write(acc, "pong", 4) == 4 && read(cli, msg, 4) == 4 && !StrNCmp(msg, "pong", 4), "server -> client");
  len = sizeof(sockaddr_un);
  MemSet(&un, 0, sizeof(sockaddr_un));
  Check(getsockname(srv, (&un)(sockaddr *), &len) == 0 && un.sun_family == AF_UNIX &&
        !StrCmp(un.sun_path, path), "getsockname of the server");
  close(acc); close(cli); close(srv);
  unlink(path);

  /* the longest path that fits works, one more byte is refused, not copied */
  len = sizeof(sockaddr_un) - 2 - 1;
  StrPrint(longpath, "./ktm183-%d-", getpid);
  for (i = StrLen(longpath); i < len; i++) longpath[i] = 'x';
  longpath[len] = '\0';
  srv = NetCreateUnixServerSocket(longpath, 4);
  Check(srv > 0, "longest path server");
  cli = NetCreateUnixClientSocket(longpath);
  Check(cli > 0, "longest path client");
  close(cli); close(srv);
  unlink(longpath);
  longpath[len] = 'x';
  longpath[len + 1] = '\0';
  Check(NetCreateUnixServerSocket(longpath, 4) == -1, "path one byte too long");
  for (i = len + 1; i < 200; i++) longpath[i] = 'y';
  longpath[200] = '\0';
  Check(NetCreateUnixServerSocket(longpath, 4) == -1 &&
        NetCreateUnixClientSocket(longpath) == -1, "200-byte path");
  Check(NetCreateUnixClientSocket("./ktm183-nobody-listens") == -1, "no server");

  /* IPv4: the family of an accepted socket's peer address */
  srv = NetCreateServer(0, "127.0.0.1", 4);
  len = sizeof(sockaddr_in);
  getsockname(srv, (&sin)(sockaddr *), &len);
  Check(sin.sin_family == AF_INET, "sin_family from getsockname");
  port = ((&sin.sin_port)(U8 *))[0] << 8 | ((&sin.sin_port)(U8 *))[1];
  cli = NetConnect("127.0.0.1", port);
  acc = accept(srv, NULL, NULL);
  Check(NetGetIpv4AddressFromAcceptedSocket(acc, ip, sizeof(ip)) && !StrCmp(ip, "127.0.0.1"),
        "peer address of an accepted socket");

  /* std.ktmh constants and addrinfo field order */
  Check(setsockopt(acc, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(I32)) == 0, "SOL_SOCKET/SO_KEEPALIVE");
  len = sizeof(I32);
  Check(getsockopt(acc, SOL_SOCKET, SO_KEEPALIVE, &got, &len) == 0 && got != 0, "getsockopt");
  got = 0;
  len = sizeof(I32);
  Check(setsockopt(acc, IPPROTO_TCP, TCP_KEEPINTVL, &seven, sizeof(I32)) == 0 &&
        getsockopt(acc, IPPROTO_TCP, TCP_KEEPINTVL, &got, &len) == 0 && got == 7,
        "IPPROTO_TCP/TCP_KEEPINTVL");
  got = 0;
  len = sizeof(I32);
  Check(setsockopt(acc, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(I32)) == 0 &&
        getsockopt(acc, IPPROTO_TCP, TCP_NODELAY, &got, &len) == 0 && got != 0,
        "TCP_NODELAY");
  close(acc); close(cli); close(srv);

  addrinfo hints, *res;
  MemSet(&hints, 0, sizeof(addrinfo));
  hints.ai_family = AF_INET;
  hints.ai_socktype = SOCK_STREAM;
  Check(getaddrinfo("127.0.0.1", "80", &hints, &res) == 0 && res->ai_addr->sa_family == AF_INET &&
        (res->ai_addr)(sockaddr_in *)->sin_addr.s_addr == inet_addr("127.0.0.1"), "addrinfo");
  freeaddrinfo(res);
  hints.ai_flags = AI_NUMERICHOST | AI_NUMERICSERV;
  res = NULL;
  Check(getaddrinfo("127.0.0.1", "80", &hints, &res) == 0, "AI_NUMERICSERV");
  if (res) freeaddrinfo(res);

  PrintResult(g_correct, tests);
  "====\n";
  return 0;
}
