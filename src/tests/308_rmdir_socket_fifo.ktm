#include "testhelper.ktm"

/* RmDir(path, RM_RECURSE) tested `ent->type & DT_DIR`, but d_type is a
 * number, not a bit mask: a socket (DT_SOCK 12) or block device (DT_BLK
 * 6) matched, was "recursed" into, failed, and stopped the removal. A
 * socket, a FIFO, a symlink and a file must all go. Everything happens
 * in a fresh mkdtemp directory. */

extern "c" U8 *mkdtemp(U8 *template);
extern "c" I32 mkfifo(U8 *path, U32 mode);
extern "c" I32 symlink(U8 *target, U8 *linkpath);
extern "c" I32 socket(I32 domain, I32 type, I32 protocol);
extern "c" I32 bind(I32 fd, U0 *addr, U32 len);

U8 *g_root;
U8 g_path[256];

U8 *P(U8 *name)
{
  StrPrint(g_path, "%s/%s", g_root, name);
  return g_path;
}

Bool Exists(U8 *name)
{
  cStat st;
  return lstat(P(name), &st) == 0;
}

Bool IsSocket(U8 *name)
{
  cStat st;
  return lstat(P(name), &st) == 0 && (st.st_mode & S_IFMT) == S_IFSOCK;
}

/* A UNIX socket bound at `path`; sockaddr_un is the family then the
 * path (macOS puts a length byte before a one-byte family) */
Bool MakeSocket(U8 *path)
{
  U8 addr[110];
  I32 fd = socket(1, 1, 0); /* AF_UNIX, SOCK_STREAM */
  if (fd < 0) return FALSE;
  MemSet(addr, 0, sizeof(addr));
#ifdef IS_MACOS
  addr[0] = 106;
  addr[1] = 1;
#else
  addr[0] = 1;
  addr[1] = 0;
#endif
  MemCpy(addr + 2, path, StrLen(path) + 1);
  Bool ok = bind(fd, addr, 106) == 0;
  close(fd);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 3;
  U8 root[64], *cmd;
  "Test - RmDir removes sockets and FIFOs: ";

  StrCpy(root, "./ktm-test-308-XXXXXX");
  if (!mkdtemp(root)) {
    PrintResult(0, tests);
    "====\n";
    return 0;
  }
  g_root = root;

  MkDir(P("t"));
  MkDir(P("t/sub"));
  FileWrite(P("t/f"), "k\n", 2);
  mkfifo(P("t/fifo"), 0x1A4);
  symlink("sub", P("t/link"));
  MakeSocket(P("t/s"));
  MakeSocket(P("t/sub/s2"));
  if (IsSocket("t/s") && IsSocket("t/sub/s2")) correct++;

  if (RmDir(P("t"), RM_RECURSE) == 0) correct++;
  if (!Exists("t")) correct++;

  cmd = StrPrint(NULL, "rm -rf '%s'", root);
  System(cmd);
  Free(cmd);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
