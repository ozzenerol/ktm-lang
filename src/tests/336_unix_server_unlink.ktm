#include "testhelper.ktm"

/* NetCreateUnixServerSocket unlink()ed whatever was at the path before
 * binding: a regular file there was deleted, and a second server on the
 * path of a live one silently took it over, leaving the first one
 * listening on a socket nobody can reach. Now only a stale socket file
 * (connecting is refused) is replaced. Everything is in a mkdtemp dir.
 * Before the fix accepting on the first server blocked forever, so a
 * watchdog alarm turns a hang into a failure. */

extern "c" U8 *mkdtemp(U8 *tmpl);
extern "c" I32 symlink(U8 *target, U8 *linkpath);
extern "c" U32 alarm(U32 seconds);

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

Bool HasData(U8 *path)
{
  U8 b[8];
  I32 fd = open(path, O_RDONLY);
  if (fd == -1) return FALSE;
  MemSet(b, 0, sizeof(b));
  I64 n = read(fd, b, 5);
  close(fd);
  return n == 5 && !StrNCmp(b, "data\n", 5);
}

Bool IsMode(U8 *path, I64 kind)
{
  cStat st;
  return lstat(path, &st) == 0 && (st.st_mode & S_IFMT) == kind;
}

Bool Talk(I32 srv, U8 *path)
{// A client on path reaches srv
  U8 b = 0;
  I32 c = NetCreateUnixClientSocket(path);
  if (c == -1) return FALSE;
  I32 a = NetAccept(srv);
  Bool ok = a != -1 && write(c, "x", 1) == 1 && read(a, &b, 1) == 1 && b == 'x';
  if (a != -1) close(a);
  close(c);
  return ok;
}

I32 Main()
{
  U8 dir[64], file[128], sock[128], sub[128], link[128];
  I32 a, b, c, fd;
  "Test - unix server socket keeps other files: ";
  alarm(30);

  StrCpy(dir, "./ktm336-XXXXXX");
  Check(mkdtemp(dir) != NULL, "mkdtemp");
  StrPrint(file, "%s/file", dir);
  StrPrint(sock, "%s/s.sock", dir);
  StrPrint(sub, "%s/sub", dir);
  StrPrint(link, "%s/link", dir);

  /* a regular file is left alone */
  fd = open(file, O_CREAT | O_WRONLY | O_TRUNC, 0x1A4);
  write(fd, "data\n", 5);
  close(fd);
  a = NetCreateUnixServerSocket(file, 4);
  Check(a == -1, "server on a regular file fails");
  if (a != -1) close(a);
  Check(IsMode(file, S_IFREG) && HasData(file), "regular file kept");

  /* so are a directory and a symlink */
  mkdir(sub, 0x1ED);
  a = NetCreateUnixServerSocket(sub, 4);
  Check(a == -1 && IsMode(sub, S_IFDIR), "directory kept");
  if (a != -1) close(a);
  symlink(file, link);
  a = NetCreateUnixServerSocket(link, 4);
  Check(a == -1 && IsMode(link, S_IFLNK) && HasData(file), "symlink kept");
  if (a != -1) close(a);

  /* a live server keeps its path */
  a = NetCreateUnixServerSocket(sock, 4);
  Check(a != -1, "first server");
  b = NetCreateUnixServerSocket(sock, 4);
  Check(b == -1, "second server on a live path fails");
  if (b != -1) close(b);
  /* finding out the path is live took one connection, closed at once */
  U8 eof;
  c = NetAccept(a);
  Check(c != -1 && read(c, &eof, 1) == 0, "probe connection");
  if (c != -1) close(c);
  Check(a != -1 && Talk(a, sock), "first server still reachable");
  if (a != -1) close(a);

  /* the socket file it left behind is stale and is replaced */
  Check(IsMode(sock, S_IFSOCK), "stale socket file left");
  c = NetCreateUnixServerSocket(sock, 4);
  Check(c != -1, "server replaces a stale socket");
  Check(c != -1 && Talk(c, sock), "new server reachable");
  if (c != -1) close(c);

  unlink(sock);
  unlink(link);
  unlink(file);
  rmdir(sub);
  Check(rmdir(dir) == 0, "temp dir cleaned");

  alarm(0);
  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
