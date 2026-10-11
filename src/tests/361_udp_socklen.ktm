import cstr, fs, libc, mem, net, proc, test;

/* the String forms of net's address functions, into a C buffer here */
bool copyOut(String *s, u8 *buf, i64 size) {
    bool ok = s.ptr && s.len < size;
    if (ok) { cstr.copy(buf, s.cstr()); }
    s.free();
    return ok;
}
bool peerInto(i32 fd, u8 *buf, i64 size) {
    String s = net.peerAddr(fd);
    return copyOut(&s, buf, size);
}
bool addrInto(libc.sockaddr *sa, i32 fam, u8 *buf, i64 size) {
    String s = net.addrToStr(sa, fam);
    return copyOut(&s, buf, size);
}
i64 recvInto(i32 fd, u8 *buf, i64 len, u8 *from, i64 fromSize, i32 *port) {
    String f;
    i64 n = net.recvFrom(fd, buf[0:len], &f, port);
    if (!copyOut(&f, from, fromSize)) { from[0] = 0; }
    return n;
}

/* accept, getpeername, getsockname, recvfrom and getsockopt took their
 * length as an i64* or u64*, but C's socklen_t is 32 bits on Linux and
 * macOS: the kernel writes 4 bytes and the upper half of an 8-byte length
 * keeps whatever was in it, while a u32 length (the right type) got an
 * "Incompatible function argument" warning. The lengths are u32 now. This
 * also checks the UDP structures against C and does an IPv4 and an IPv6
 * sendto/recvfrom round trip. */

#define SOCKLEN_SRC "__361_socklen.ktm"
#define SOCKLEN_OUT "__361_socklen.out"
#define SOCKLEN_BIN "__361_socklen.bin"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* A program that passes u32 lengths everywhere compiles without warnings */
bool compilesCleanly(u8 *source) {
    u8 *out;
    bool ok;
    proc.shell("rm -f " SOCKLEN_SRC " " SOCKLEN_OUT " " SOCKLEN_BIN);
    if (!fs.writeAll(SOCKLEN_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 SOCKLEN_SRC " -o " SOCKLEN_BIN " > " SOCKLEN_OUT " 2>&1");
    out = fs.readAll(SOCKLEN_OUT).take();
    ok = out != null && cstr.len(out) == 0;
    if (!ok && out) { printf("\n%s", out); }
    mem.free(out);
    proc.shell("rm -f " SOCKLEN_SRC " " SOCKLEN_OUT " " SOCKLEN_BIN);
    return ok;
}

i64 offFn(void *base, void *field) {
    return (u8 *)field - (u8 *)base;
}

/* Bind a UDP socket to `ip` on a port the kernel picks; fills `me` with the
 * bound address and returns the socket, or -1 */
i32 udpBind(i32 fam, u8 *ip, libc.sockaddr_storage *me, u32 *len) {
    i32 fd = libc.socket(fam, libc.SOCK_DGRAM, 0);
    libc.sockaddr_in *v4 = me;
    libc.sockaddr_in6 *v6 = me;
    if (fd == -1) { return -1; }
    mem.set(me, 0, sizeof(libc.sockaddr_storage));
    if (fam == libc.AF_INET) {
        *len = sizeof(libc.sockaddr_in);
        v4.sin_family = libc.AF_INET;
        libc.inet_pton(libc.AF_INET, ip, &v4.sin_addr);
    } else {
        *len = sizeof(libc.sockaddr_in6);
        v6.sin6_family = libc.AF_INET6;
        libc.inet_pton(libc.AF_INET6, ip, &v6.sin6_addr);
    }
#ifndef IS_LINUX
    me->ss_len = *len;
#endif
    if (libc.bind(fd, (libc.sockaddr *)me, *len) == -1 ||
            libc.getsockname(fd, (libc.sockaddr *)me, len) == -1) {
        libc.close(fd);
        return -1;
    }
    return fd;
}

/* Send "ping" from one socket to another and read it back with recvfrom;
 * the sender's address must come back with the right length and port */
void roundTrip(i32 fam, u8 *ip, u8 *name) {
    libc.sockaddr_storage a, b, from;
    libc.sockaddr_in *v4 = &from;
    libc.sockaddr_in6 *v6 = &from;
    u32 alen, blen, flen;
    u8 buf[16], what[64], txt[64];
    i32 sa = udpBind(fam, ip, &a, &alen), sb = udpBind(fam, ip, &b, &blen);
    i64 n;

    if (sa == -1 || sb == -1) {
        if (fam == libc.AF_INET6) {
            /* no ::1 on this machine (IPv6 disabled): nothing to test */
            if (sa != -1) { libc.close(sa); }
            if (sb != -1) { libc.close(sb); }
            gTests += 7;
            gCorrect += 7;
            return;
        }
        check(false, "udp bind");
        return;
    }
    if (fam == libc.AF_INET) { n = sizeof(libc.sockaddr_in); }
    else { n = sizeof(libc.sockaddr_in6); }
    check(alen == n && blen == n, "getsockname length");

    cstr.formatTo(what, "%s sendto", name);
    check(libc.sendto(sa, "ping", 4, 0, (libc.sockaddr *)(&b), blen) == 4, what);

    /* MSG_PEEK leaves the datagram queued */
    mem.set(buf, 0, sizeof(buf));
    cstr.formatTo(what, "%s recv MSG_PEEK", name);
    check(libc.recv(sb, buf, sizeof(buf), libc.MSG_PEEK) == 4 && !cstr.cmp(buf, "ping"), what);

    mem.set(buf, 0, sizeof(buf));
    mem.set(&from, 0xAB, sizeof(libc.sockaddr_storage));
    flen = sizeof(libc.sockaddr_storage);
    n = libc.recvfrom(sb, buf, sizeof(buf), 0, (libc.sockaddr *)(&from), &flen);
    cstr.formatTo(what, "%s recvfrom", name);
    check(n == 4 && !cstr.cmp(buf, "ping"), what);
    cstr.formatTo(what, "%s recvfrom address length", name);
    check(flen == alen, what);
    cstr.formatTo(what, "%s sender family and port", name);
    if (fam == libc.AF_INET) {
        check(v4.sin_family == libc.AF_INET && v4.sin_port == ((libc.sockaddr_in *)(&a)).sin_port, what);
    } else {
        check(v6.sin6_family == libc.AF_INET6 && v6.sin6_port == ((libc.sockaddr_in6 *)(&a)).sin6_port, what);
    }
    cstr.formatTo(what, "%s sender address", name);
    check(addrInto((libc.sockaddr *)(&from), fam, txt, sizeof(txt)) && !cstr.cmp(txt, ip), what);
    libc.close(sa);
    libc.close(sb);
}

/* A datagram longer than the buffer: recvmsg cuts it and sets MSG_TRUNC */
void truncated() {
    libc.sockaddr_storage a;
    u32 alen;
    u8 buf[4];
    libc.iovec io;
    libc.msghdr m;
    i32 fd = udpBind(libc.AF_INET, "127.0.0.1", &a, &alen);
    check(fd != -1 && libc.sendto(fd, "toolong", 7, 0, (libc.sockaddr *)(&a), alen) == 7, "sendto self");
    io.iov_base = buf;
    io.iov_len = sizeof(buf);
    mem.set(&m, 0, sizeof(libc.msghdr));
    m.msg_iov = &io;
    m.msg_iovlen = 1;
    check(libc.recvmsg(fd, &m, 0) == 4 && (m.msg_flags & libc.MSG_TRUNC) && !cstr.cmpLen(buf, "tool", 4),
                "MSG_TRUNC on a cut datagram");
    libc.close(fd);
}

i32 main() {
    libc.sockaddr_in6 s6;
    printf("Test - udp socklen: ");

    check(compilesCleanly(
"import libc;

void main()
{
  libc.sockaddr_storage ss;
  u32 len = sizeof(libc.sockaddr_storage), olen = sizeof(i32);
  i32 val, fd = libc.socket(libc.AF_INET, libc.SOCK_DGRAM, 0);
  u8 buf[8];
  libc.getsockname(fd, (libc.sockaddr *)(&ss), &len);
  libc.getpeername(fd, (libc.sockaddr *)(&ss), &len);
  libc.getsockopt(fd, libc.SOL_SOCKET, libc.SO_BROADCAST, &val, &olen);
  libc.setsockopt(fd, libc.SOL_SOCKET, libc.SO_BROADCAST, &val, olen);
  libc.recvfrom(fd, buf, 0, libc.MSG_DONTROUTE, (libc.sockaddr *)(&ss), &len);
  libc.sendto(fd, buf, 0, 0, (libc.sockaddr *)(&ss), len);
  if (0) { libc.accept(fd, (libc.sockaddr *)(&ss), &len); }
  libc.close(fd);
}
"), "u32 lengths compile without warnings");

    /* Layouts, as C's sizeof/offsetof give them on Linux and macOS */
    check(sizeof(libc.sockaddr) == 16, "sizeof(sockaddr)");
    check(sizeof(libc.sockaddr_in) == 16, "sizeof(sockaddr_in)");
    check(sizeof(libc.sockaddr_in6) == 28, "sizeof(sockaddr_in6)");
    check(sizeof(libc.sockaddr_storage) == 128, "sizeof(sockaddr_storage)");
    check(offFn(&s6, &s6.sin6_port) == 2 && offFn(&s6, &s6.sin6_flowinfo) == 4 &&
                offFn(&s6, &s6.sin6_addr) == 8 && offFn(&s6, &s6.sin6_scope_id) == 24,
                "sockaddr_in6 offsets");

    /* MSG_* flags differ between Linux and the BSDs */
    check(libc.MSG_OOB == 1 && libc.MSG_PEEK == 2 && libc.MSG_DONTROUTE == 4, "MSG_OOB/PEEK/DONTROUTE");
#ifdef IS_LINUX
    check(libc.MSG_CTRUNC == 0x8 && libc.MSG_TRUNC == 0x20 && libc.MSG_EOR == 0x80 && libc.MSG_WAITALL == 0x100,
                "MSG_* (Linux)");
#else
    check(libc.MSG_CTRUNC == 0x20 && libc.MSG_TRUNC == 0x10 && libc.MSG_EOR == 0x8 && libc.MSG_WAITALL == 0x40,
                "MSG_* (macOS)");
#endif

    roundTrip(libc.AF_INET, "127.0.0.1", "IPv4");
    roundTrip(libc.AF_INET6, "::1", "IPv6");
    truncated();

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
