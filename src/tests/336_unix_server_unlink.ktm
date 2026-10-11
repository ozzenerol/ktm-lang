#include "testhelper.ktm"

import libc, mem, net, str;

/* NetCreateUnixServerSocket unlink()ed whatever was at the path before
 * binding: a regular file there was deleted, and a second server on the
 * path of a live one silently took it over, leaving the first one
 * listening on a socket nobody can reach. Now only a stale socket file
 * (connecting is refused) is replaced. Everything is in a mkdtemp dir.
 * Before the fix accepting on the first server blocked forever, so a
 * watchdog alarm turns a hang into a failure. */

extern "c" u8 *mkdtemp(u8 *tmpl);
extern "c" i32 symlink(u8 *target, u8 *linkpath);
extern "c" u32 alarm(u32 seconds);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool hasData(u8 *path) {
    u8 b[8];
    i32 fd = libc.open(path, libc.O_RDONLY);
    if (fd == -1) { return false; }
    mem.set(b, 0, sizeof(b));
    i64 n = libc.read(fd, b, 5);
    libc.close(fd);
    return n == 5 && !str.cmpLen(b, "data\n", 5);
}

bool isMode(u8 *path, i64 kind) {
    libc.cStat st;
    return libc.lstat(path, &st) == 0 && (st.st_mode & libc.S_IFMT) == kind;
}

bool talk(i32 srv, u8 *path)
{// A client on path reaches srv
    u8 b = 0;
    i32 c = net.unixDial(path);
    if (c == -1) { return false; }
    i32 a = net.accept(srv);
    bool ok = a != -1 && libc.write(c, "x", 1) == 1 && libc.read(a, &b, 1) == 1 && b == 'x';
    if (a != -1) { libc.close(a); }
    libc.close(c);
    return ok;
}

i32 main() {
    u8 dir[64], file[128], sock[128], sub[128], link[128];
    i32 a, b, c, fd;
    printf("Test - unix server socket keeps other files: ");
    alarm(30);

    str.copy(dir, "./ktm336-XXXXXX");
    check(mkdtemp(dir) != null, "mkdtemp");
    str.formatTo(file, "%s/file", dir);
    str.formatTo(sock, "%s/s.sock", dir);
    str.formatTo(sub, "%s/sub", dir);
    str.formatTo(link, "%s/link", dir);

    /* a regular file is left alone */
    fd = libc.open(file, libc.O_CREAT | libc.O_WRONLY | libc.O_TRUNC, 0x1A4);
    libc.write(fd, "data\n", 5);
    libc.close(fd);
    a = net.unixListen(file, 4);
    check(a == -1, "server on a regular file fails");
    if (a != -1) { libc.close(a); }
    check(isMode(file, libc.S_IFREG) && hasData(file), "regular file kept");

    /* so are a directory and a symlink */
    libc.mkdir(sub, 0x1ED);
    a = net.unixListen(sub, 4);
    check(a == -1 && isMode(sub, libc.S_IFDIR), "directory kept");
    if (a != -1) { libc.close(a); }
    symlink(file, link);
    a = net.unixListen(link, 4);
    check(a == -1 && isMode(link, libc.S_IFLNK) && hasData(file), "symlink kept");
    if (a != -1) { libc.close(a); }

    /* a live server keeps its path */
    a = net.unixListen(sock, 4);
    check(a != -1, "first server");
    b = net.unixListen(sock, 4);
    check(b == -1, "second server on a live path fails");
    if (b != -1) { libc.close(b); }
    /* finding out the path is live took one connection, closed at once */
    u8 eof;
    c = net.accept(a);
    check(c != -1 && libc.read(c, &eof, 1) == 0, "probe connection");
    if (c != -1) { libc.close(c); }
    check(a != -1 && talk(a, sock), "first server still reachable");
    if (a != -1) { libc.close(a); }

    /* the socket file it left behind is stale and is replaced */
    check(isMode(sock, libc.S_IFSOCK), "stale socket file left");
    c = net.unixListen(sock, 4);
    check(c != -1, "server replaces a stale socket");
    check(c != -1 && talk(c, sock), "new server reachable");
    if (c != -1) { libc.close(c); }

    libc.unlink(sock);
    libc.unlink(link);
    libc.unlink(file);
    libc.rmdir(sub);
    check(libc.rmdir(dir) == 0, "temp dir cleaned");

    alarm(0);
    PrintResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
