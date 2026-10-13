module net;
import str, cstr, date, libc, mem, os;

/* Byte order: the network is big endian, every target ktm supports is
    * little endian, so these swap the bytes. */
pub u16 htons(u16 x) {
        return ((x & 0xFF) << 8) | ((x >> 8) & 0xFF);
}

pub u16 ntohs(u16 x) {
        return htons(x);
}

pub u32 htonl(u32 x) {
        return ((x & 0xFF) << 24) | ((x & 0xFF00) << 8) |
                                    ((x >> 8) & 0xFF00) | ((x >> 24) & 0xFF);
}

pub u32 ntohl(u32 x) {
        return htonl(x);
}

#ifdef IS_LINUX
const i64 __NET_SOCK_CLOEXEC = 0x80000;
extern "c" i32 accept4(i32 socket, libc.sockaddr *address, u32 *addressLen, i32 flags);
#endif

/* The library's sockets are close-on-exec, so programs started with
    * System or exec don't inherit them. macOS has no SOCK_CLOEXEC or
    * accept4: set FD_CLOEXEC right after (a fork in another thread in
    * between can still inherit it there). */
static i32 netSocket(i32 domain, i32 type, i32 protocol) {
#ifdef IS_LINUX
        return libc.socket(domain, type | __NET_SOCK_CLOEXEC, protocol);
#else
        i32 fd = libc.socket(domain, type, protocol);
        if (fd != -1) { libc.fcntl(fd, libc.F_SETFD, libc.FD_CLOEXEC); }
        return fd;
#endif
}

static i32 netAcceptCloexec(i32 socket, libc.sockaddr *address, u32 *addressLen) {
#ifdef IS_LINUX
        return accept4(socket, address, addressLen, __NET_SOCK_CLOEXEC);
#else
        i32 fd = libc.accept(socket, address, addressLen);
        if (fd != -1) { libc.fcntl(fd, libc.F_SETFD, libc.FD_CLOEXEC); }
        return fd;
#endif
}

pub bool setNonBlocking(i32 sockfd) {
        i32 flags = libc.fcntl(sockfd, libc.F_GETFL, 0);
        if (libc.fcntl(sockfd,libc.F_SETFL,flags|libc.O_NONBLOCK)) {
                return false;
        }
        return true;
}

/* Clear O_NONBLOCK again (reads, writes and accepts wait); true on
 * success */
pub bool setBlocking(i32 sockfd) {
        i32 flags = libc.fcntl(sockfd, libc.F_GETFL, 0);
        if (flags == -1 || libc.fcntl(sockfd, libc.F_SETFL, flags & ~libc.O_NONBLOCK) == -1) {
                return false;
        }
        return true;
}

pub bool setReuseAddr(i32 sockfd) {
        i32 yes = 1;

        if (libc.setsockopt(sockfd, libc.SOL_SOCKET, libc.SO_REUSEADDR, &yes, sizeof(i32)) == -1) {
                return false;
        }

        return true;
}

/* Set TCP no delay, turn off Nagle buffering. Makes the socket sent data as soon
    * as it is avalible. Returns true on success and false if setsockopt fails.
    *
    * https://www.extrahop.com/company/blog/2016/tcp-nodelay-nagle-quickack-best-practices/
    * */
pub bool setNoDelay(i32 sockfd) {
        i32 yes = 1;

        if (libc.setsockopt(sockfd, libc.IPPROTO_TCP, libc.TCP_NODELAY, &yes, sizeof(i32)) == -1) {
                return false;
        }

        return true;
}

static void *netGetInAddress(libc.sockaddr *sa)
{// The IPv4 or IPv6 address in `sa`, null for any other family
        if (sa.sa_family == libc.AF_INET) {
                return &(((libc.sockaddr_in*)sa).sin_addr);
        }
        if (sa.sa_family == libc.AF_INET6) {
                return &(((libc.sockaddr_in6 *)sa).sin6_addr);
        }
        return null;
}

/* Fills out buf with the IPV4 address */
static bool addrToStrC(libc.sockaddr *sAddr, i32 ai_family, u8 *outbuf, i32 size) {
        void *address;
        if (sAddr.sa_family != ai_family) {
                return false;
        }
        address = netGetInAddress(sAddr);
        if (address == null) {
                return false;
        }
        if (libc.inet_ntop(ai_family, address, outbuf, size) == null) {
                return false;
        }
        return true;
}

#ifdef IS_LINUX
const i64 __NET_EINTR = 4;
const i64 __NET_EINVAL = 22;
const i64 __NET_ETIMEDOUT = 110;
const i64 __NET_EINPROGRESS = 115;
#else
const i64 __NET_EINTR = 4;
const i64 __NET_EINVAL = 22;
const i64 __NET_ETIMEDOUT = 60;
const i64 __NET_EINPROGRESS = 36;
#endif

const i64 __NET_POLLOUT = 0x4;

struct NetPollFd {
        i32 fd;
        i16 events;
        i16 revents;
};

/* Connect sockfd to `sa`, giving up when `deadline` (NowMilliseconds) is
    * reached: a non-blocking connect, a poll for writability, then SO_ERROR.
    * The socket is left non-blocking. 0 when connected, otherwise -1 with
    * errno set (ETIMEDOUT when the time ran out). */
static i32 netConnectBefore(i32 sockfd, libc.addrinfo *sa, i64 deadline) {
        NetPollFd pfd;
        i32 err = 0, n;
        u32 len = sizeof(i32);
        i64 left;

        if (!setNonBlocking(sockfd)) {
                return -1;
        }
        if (libc.connect(sockfd, sa.ai_addr, sa.ai_addrlen) == 0) {
                return 0;
        }
        if (os.errno() != __NET_EINPROGRESS) {
                return -1;
        }

        while (1) {
                left = deadline - date.nowMs();
                if (left <= 0) {
                        os.setErrno(__NET_ETIMEDOUT);
                        return -1;
                }
                pfd.fd = sockfd;
                pfd.events = __NET_POLLOUT;
                pfd.revents = 0;
                n = libc.poll((libc.pollfd *)&pfd, 1, left);
                if (n > 0) {
                        break;
                }
                if (n == -1 && os.errno() != __NET_EINTR) {
                        return -1;
                }
                /* 0 (time is up) or EINTR: the loop checks the deadline again */
        }
        if (libc.getsockopt(sockfd, libc.SOL_SOCKET, libc.SO_ERROR, &err, &len) == -1) {
                return -1;
        }
        if (err != 0) {
                os.setErrno(err);
                return -1;
        }
        return 0;
}

static i32 dialC(u8 *addr, i32 port, bool nonBlocking=false,
                i64 timeoutMs=0)
{// Returns the connected socket, or -1 on failure
        u8 strport[6];
        i32 sockfd, err = 0;
        libc.addrinfo hints, *servinfo, *ptr;
        i64 deadline = 0;

        if (port < 0 || port > 65535 || timeoutMs < 0) {
                os.setErrno(__NET_EINVAL);
                return -1;
        }
        libc.snprintf(strport, sizeof(strport), "%d", port);
        mem.set(&hints, 0, sizeof(libc.addrinfo));
        hints.ai_family = libc.AF_UNSPEC; /* IPv4 or IPv6, whatever addr resolves to */
        hints.ai_socktype = libc.SOCK_STREAM;
        sockfd = -1;

        if (libc.getaddrinfo(addr, strport, &hints, &servinfo) != 0) {
                return -1;
        }
        /* The name lookup above can't be cut short; the time counts from here */
        if (timeoutMs > 0) {
                deadline = date.nowMs() + timeoutMs;
        }

        /* connect on the first one we can */
        for (ptr = servinfo; ptr != null; ptr = ptr.ai_next) {
                if ((sockfd = netSocket(ptr.ai_family, ptr.ai_socktype,
                                                ptr.ai_protocol)) == -1) {
                        err = os.errno();
                        continue;
                }

                if (timeoutMs > 0) {
                        if (netConnectBefore(sockfd, ptr, deadline) == -1) {
                                err = os.errno();
                                libc.close(sockfd); /* try the next address with the time that's left */
                                sockfd = -1;
                                if (err == __NET_ETIMEDOUT) { break; }
                                continue;
                        }
                        if (!nonBlocking && !setBlocking(sockfd)) { goto error; }
                } else if (libc.connect(sockfd, ptr.ai_addr, ptr.ai_addrlen) == -1) {
                        err = os.errno();
                        libc.close(sockfd); /* try the next address */
                        sockfd = -1;
                        continue;
                }
                if (!setReuseAddr(sockfd)) { goto error; }
                if (nonBlocking) {
                        if (!setNonBlocking(sockfd)) { goto error; }
                }

                break;
        }

        if (sockfd == -1) {
                goto error;
        }

        libc.freeaddrinfo(servinfo);

        return sockfd;

error:
        if (sockfd != -1) {
                err = os.errno();
                libc.close(sockfd);
        }
        libc.freeaddrinfo(servinfo);
        if (err) { os.setErrno(err); }
        return -1;
}

pub bool bindListen(i32 sockfd, libc.addrinfo *sa, i32 backlog) {
        if (libc.bind(sockfd, sa.ai_addr, sa.ai_addrlen) == -1) {
                libc.close(sockfd);
                return false;
        }

        if (libc.listen(sockfd, backlog) == -1) {
                libc.close(sockfd);
                return false;
        }
        return true;
}

static i32 listenC(i32 port, u8 *bindaddr, i32 backlog,
                i32 addrfam=libc.AF_UNSPEC, bool nonBlocking=false)
{// Returns the listening socket, or -1 on failure
        i32 sockfd = -1, rv;
        u8 strport[6];
        libc.addrinfo hints, *servinfo, *ptr;

        if (port < 0 || port > 65535) {
                return -1;
        }
        libc.snprintf(strport, sizeof(strport), "%d", port);
        mem.set(&hints, 0, sizeof(libc.addrinfo));

        if (addrfam != libc.AF_UNSPEC && addrfam != libc.AF_INET && addrfam != libc.AF_INET6) {
                return -1;
        }

        /* "*" (or null) is any address of addrfam, IPv4 for AF_UNSPEC as it
            * always was; "::*" is any IPv6 address. Anything else must be a
            * numeric address: a host name would wait on DNS */
        if (bindaddr != null && !cstr.cmp("::*", bindaddr)) {
                if (addrfam == libc.AF_INET) {
                        return -1;
                }
                addrfam = libc.AF_INET6;
                bindaddr = null;
        } else if (bindaddr != null && !cstr.cmp("*", bindaddr)) {
                bindaddr = null;
        }
        if (bindaddr == null && addrfam == libc.AF_UNSPEC) {
                addrfam = libc.AF_INET;
        }

        hints.ai_family = addrfam;
        hints.ai_socktype = libc.SOCK_STREAM;
        hints.ai_flags = libc.AI_PASSIVE | libc.AI_NUMERICHOST;

        if ((rv = libc.getaddrinfo(bindaddr, strport, &hints, &servinfo)) != 0) {
                return -1;
        }

        for (ptr = servinfo; ptr != null; ptr = ptr.ai_next) {
                if ((sockfd = netSocket(ptr.ai_family, ptr.ai_socktype,
                                                ptr.ai_protocol)) == -1) {
                        continue;
                                                }

                if (!setReuseAddr(sockfd)) {
                        libc.close(sockfd); /* try the next address */
                        sockfd = -1;
                        continue;
                }
                if (!bindListen(sockfd, ptr, backlog)) {
                        sockfd = -1; /* NetListen closed it; try the next address */
                        continue;
                }
                if (nonBlocking) {
                        if (!setNonBlocking(sockfd)) { goto error; }
                }
                break;
        }

        if (ptr == null) {
                goto error;
        }

        libc.freeaddrinfo(servinfo);
        return sockfd;

error:
        if (sockfd != -1) { libc.close(sockfd); }
        libc.freeaddrinfo(servinfo);
        return -1;
}

pub i32 accept(i32 socket, bool nonBlocking=false)
{// Returns the accepted socket, or -1 on failure
        i32 accepted;
        u32 socklen = sizeof(libc.sockaddr_storage);
        libc.sockaddr_storage in_addr;

        if ((accepted = netAcceptCloexec(socket,(libc.sockaddr *)(&in_addr),&socklen)) == -1) {
                return -1;
        }

        /* Set the mode either way: BSD accept (macOS) copies O_NONBLOCK from
            * the listening socket, Linux's doesn't. */
        if (nonBlocking) {
                if (!setNonBlocking(accepted)) {
                        libc.close(accepted);
                        return -1;
                }
        } else if (!setBlocking(accepted)) {
                libc.close(accepted);
                return -1;
        }
        return accepted;
}

#ifdef IS_LINUX
const i64 __NET_MSG_NOSIGNAL = 0x4000;
#else
const i64 __NET_MSG_NOSIGNAL = 0x80000;
#endif

static i64 sendC(i32 sockfd, void *buf, i64 len, i32 flags=0)
{// send() that never raises SIGPIPE: a closed peer gives -1 (EPIPE)
        return libc.send(sockfd, buf, len, flags | __NET_MSG_NOSIGNAL);
}

/**
    * Socket needs to be accepted for this to work, a better function
    * could probably be made as this only accepts IPV4 which doesn't feel
    * right.
    */
static bool peerAddrC(i32 sockfd, u8 *ipbuf, i32 outlen) {
        libc.sockaddr_storage sa; /* a sockaddr is too small for an IPv6 peer */
        u32 len;
        i32 retval;

        len = sizeof(libc.sockaddr_storage);
        if ((retval = libc.getpeername(sockfd, (libc.sockaddr *)(&sa), &len)) == -1) {
                return false;
        }

        return addrToStrC((libc.sockaddr *)(&sa), sa.ss_family, ipbuf, outlen);
}

/* Fills `addr` for the socket file `name`; false if the path doesn't fit */
static bool netUnixAddress(libc.sockaddr_un *addr, u8 *name) {
        i64 len = cstr.len(name);
        if (len >= sizeof(addr.sun_path)) {
                return false;
        }
        mem.set(addr, 0, sizeof(libc.sockaddr_un));
#ifndef IS_LINUX
        addr->sun_len = sizeof(libc.sockaddr_un);
#endif
        addr.sun_family = libc.AF_UNIX;
        mem.copy(addr.sun_path, name, len + 1);
        return true;
}

#ifdef IS_LINUX
const i64 __NET_ECONNREFUSED = 111;
#else
const i64 __NET_ECONNREFUSED = 61;
#endif

/* true if `name` is a socket file left behind by a server that is gone:
    * connecting to it is refused. false for anything else at that path,
    * including a socket a server still listens on (which sees a connection
    * that closes at once). */
static bool netUnixSocketIsStale(libc.sockaddr_un *addr, u8 *name) {
        libc.cStat st;
        i32 fd, rv;

        if (libc.lstat(name, &st) != 0 || (st.st_mode & libc.S_IFMT) != libc.S_IFSOCK) {
                return false;
        }
        if ((fd = netSocket(libc.AF_UNIX, libc.SOCK_STREAM, 0)) == -1) {
                return false;
        }
        rv = libc.connect(fd, (libc.sockaddr *)addr, sizeof(libc.sockaddr_un));
        bool stale = rv == -1 && os.errno() == __NET_ECONNREFUSED;
        libc.close(fd);
        return stale;
}

static i32 unixListenC(u8 *name, i32 backlog)
{// Listens on the socket file `name`; returns the socket or -1 on failure.
    // A stale socket file at `name` is replaced; any other file, or a socket
    // a server is listening on, makes it fail.
        i32 sockfd = -1;
        libc.sockaddr_un srv;

        if (!netUnixAddress(&srv, name)) {
                return -1;
        }

        if ((sockfd = netSocket(libc.AF_UNIX, libc.SOCK_STREAM, 0)) == -1) {
                goto error;
        }

        if (netUnixSocketIsStale(&srv, name)) {
                libc.unlink(name);
        }
        if ((libc.bind(sockfd, (libc.sockaddr *)(&srv), sizeof(libc.sockaddr_un))) == -1) {
                goto error;
        }

        if (libc.listen(sockfd, backlog) == -1) {
                goto error;
        }

        return sockfd;

error:
        if (sockfd != -1) {
                libc.close(sockfd);
        }
        return -1;
}

static i32 unixDialC(u8 *name)
{// Connects to the socket file `name`; returns the socket or -1 on failure
        i32 sockfd;
        libc.sockaddr_un srv;

        if (!netUnixAddress(&srv, name)) {
                return -1;
        }

        if ((sockfd = netSocket(libc.AF_UNIX, libc.SOCK_STREAM, 0)) == -1) {
                return -1;
        }

        if (libc.connect(sockfd, (libc.sockaddr *)(&srv), sizeof(libc.sockaddr_un)) == -1) {
                libc.close(sockfd);
                return -1;
        }
        return sockfd;
}

pub i32 localPort(i32 sockfd)
{// The local port of sockfd, or -1
        libc.sockaddr_storage ss;
        u32 len = sizeof(libc.sockaddr_storage);

        if (libc.getsockname(sockfd, (libc.sockaddr *)(&ss), &len) == -1) {
                return -1;
        }
        if (ss.ss_family == libc.AF_INET) {
                return ntohs(((libc.sockaddr_in *)(&ss)).sin_port);
        }
        if (ss.ss_family == libc.AF_INET6) {
                return ntohs(((libc.sockaddr_in6 *)(&ss)).sin6_port);
        }
        return -1;
}

static i32 udpSocketC(i32 port, u8 *bindaddr, i32 addrfam=libc.AF_UNSPEC,
                bool nonBlocking=false)
{// Returns the bound UDP socket, or -1 on failure
        i32 sockfd = -1;
        u8 strport[6];
        libc.addrinfo hints, *servinfo, *ptr;

        if (port < 0 || port > 65535) {
                return -1;
        }
        if (addrfam != libc.AF_UNSPEC && addrfam != libc.AF_INET && addrfam != libc.AF_INET6) {
                return -1;
        }
        libc.snprintf(strport, sizeof(strport), "%d", port);

        /* The same bind addresses as NetCreateServer */
        if (bindaddr != null && !cstr.cmp("::*", bindaddr)) {
                if (addrfam == libc.AF_INET) {
                        return -1;
                }
                addrfam = libc.AF_INET6;
                bindaddr = null;
        } else if (bindaddr != null && !cstr.cmp("*", bindaddr)) {
                bindaddr = null;
        }
        if (bindaddr == null && addrfam == libc.AF_UNSPEC) {
                addrfam = libc.AF_INET;
        }

        mem.set(&hints, 0, sizeof(libc.addrinfo));
        hints.ai_family = addrfam;
        hints.ai_socktype = libc.SOCK_DGRAM;
        hints.ai_flags = libc.AI_PASSIVE | libc.AI_NUMERICHOST;

        if (libc.getaddrinfo(bindaddr, strport, &hints, &servinfo) != 0) {
                return -1;
        }

        for (ptr = servinfo; ptr != null; ptr = ptr.ai_next) {
                if ((sockfd = netSocket(ptr.ai_family, ptr.ai_socktype,
                                                ptr.ai_protocol)) == -1) {
                        continue;
                                                }
                if (libc.bind(sockfd, ptr.ai_addr, ptr.ai_addrlen) == -1) {
                        libc.close(sockfd); /* try the next address */
                        sockfd = -1;
                        continue;
                }
                if (nonBlocking && !setNonBlocking(sockfd)) {
                        libc.close(sockfd);
                        sockfd = -1;
                }
                break;
        }

        libc.freeaddrinfo(servinfo);
        return sockfd;
}

static i64 sendToC(i32 sockfd, void *buf, i64 len, u8 *addr, i32 port)
{// Returns the bytes sent, or -1 on failure
        libc.sockaddr_storage me;
        u32 melen = sizeof(libc.sockaddr_storage);
        u8 strport[6];
        libc.addrinfo hints, *servinfo;
        i64 sent;

        if (port < 0 || port > 65535 || addr == null) {
                return -1;
        }
        /* Resolve addr in the socket's own family */
        if (libc.getsockname(sockfd, (libc.sockaddr *)(&me), &melen) == -1) {
                return -1;
        }
        libc.snprintf(strport, sizeof(strport), "%d", port);
        mem.set(&hints, 0, sizeof(libc.addrinfo));
        hints.ai_family = me.ss_family;
        hints.ai_socktype = libc.SOCK_DGRAM;

        if (libc.getaddrinfo(addr, strport, &hints, &servinfo) != 0) {
                return -1;
        }
        /* like NetSend: a socket that can't deliver gives -1, never SIGPIPE */
        sent = libc.sendto(sockfd, buf, len, __NET_MSG_NOSIGNAL, servinfo.ai_addr,
                        servinfo.ai_addrlen);
        libc.freeaddrinfo(servinfo);
        return sent;
}

static i64 recvFromC(i32 sockfd, void *buf, i64 len, u8 *fromAddr=null,
                i64 fromSize=0, i32 *fromPort=null)
{// Returns the bytes received, or -1
        libc.sockaddr_storage from;
        u32 fromlen = sizeof(libc.sockaddr_storage);
        i64 got;

        if (len < 0) {
                return -1;
        }
        mem.set(&from, 0, sizeof(libc.sockaddr_storage));
        got = libc.recvfrom(sockfd, buf, len, 0, (libc.sockaddr *)(&from), &fromlen);
        if (got == -1) {
                return -1;
        }

        if (fromAddr != null && fromSize > 0) {
                if (!addrToStrC((libc.sockaddr *)(&from), from.ss_family, fromAddr, fromSize)) {
                        fromAddr[0] = '\0';
                }
        }
        if (fromPort != null) {
                if (from.ss_family == libc.AF_INET) {
                        *fromPort = ntohs(((libc.sockaddr_in *)(&from)).sin_port);
                } else if (from.ss_family == libc.AF_INET6) {
                        *fromPort = ntohs(((libc.sockaddr_in6 *)(&from)).sin6_port);
                } else {
                        *fromPort = -1;
                }
        }
        return got;
}

/* SO_RCVTIMEO or SO_SNDTIMEO */
static bool netSetTimeout(i32 sockfd, i32 opt, i64 ms) {
        libc.timeval tv;

        if (ms < 0) {
                return false;
        }
        /* tv_usec is a long on Linux, an int on macOS: zero the whole struct */
        mem.set(&tv, 0, sizeof(libc.timeval));
        tv.tv_sec = ms / 1000;
        tv.tv_usec = (ms % 1000) * 1000;
        return libc.setsockopt(sockfd, libc.SOL_SOCKET, opt, &tv, sizeof(libc.timeval)) == 0;
}

pub bool setRecvTimeout(i32 sockfd, i64 ms)
{// true if SO_RCVTIMEO was set
        return netSetTimeout(sockfd, libc.SO_RCVTIMEO, ms);
}

pub bool setSendTimeout(i32 sockfd, i64 ms)
{// true if SO_SNDTIMEO was set
        return netSetTimeout(sockfd, libc.SO_SNDTIMEO, ms);
}

/* ---- The API: text as u8[] (a literal, a slice, a String) ---- */

/* `s` as a C string in buf (size bytes), null for an empty s (any address) */
static u8 *netCStr(u8[] s, u8 *buf, i64 size)
{
        if (!s.len || s.len >= size) { return null; }
        mem.copy(buf, s.ptr, s.len);
        buf[s.len] = 0;
        return buf;
}

/* The address of sAddr (of family ai_family) as text, a String; one without
 * a ptr if it isn't one */
pub String addrToStr(libc.sockaddr *sAddr, i32 ai_family)
{
        u8 buf[64];
        String out;
        if (addrToStrC(sAddr, ai_family, buf, sizeof(buf))) { out = str.from(cstr.view(buf)); }
        return out;
}

/* The connected socket for addr (a host name or an address) and port, or -1 */
pub i32 dial(u8[] addr, i32 port, bool nonBlocking=false, i64 timeoutMs=0)
{
        u8 buf[256];
        u8 *c = netCStr(addr, buf, sizeof(buf));
        if (!c) { return -1; }
        return dialC(c, port, nonBlocking, timeoutMs);
}

/* A listening socket on port, on bindaddr (empty: every address), or -1 */
pub i32 listen(i32 port, u8[] bindaddr, i32 backlog,
                i32 addrfam=libc.AF_UNSPEC, bool nonBlocking=false)
{
        u8 buf[256];
        return listenC(port, netCStr(bindaddr, buf, sizeof(buf)), backlog, addrfam, nonBlocking);
}

/* data sent on sockfd (never raising SIGPIPE): the bytes sent, or -1 */
pub i64 send(i32 sockfd, u8[] data, i32 flags=0)
{
        return sendC(sockfd, data.ptr, data.len, flags);
}

/* The peer's address of an accepted socket, a String (no ptr on an error) */
pub String peerAddr(i32 sockfd)
{
        u8 buf[64];
        String out;
        if (peerAddrC(sockfd, buf, sizeof(buf))) { out = str.from(cstr.view(buf)); }
        return out;
}

pub i32 unixListen(u8[] name, i32 backlog)
{
        u8 buf[256];
        u8 *c = netCStr(name, buf, sizeof(buf));
        if (!c) { return -1; }
        return unixListenC(c, backlog);
}

pub i32 unixDial(u8[] name)
{
        u8 buf[256];
        u8 *c = netCStr(name, buf, sizeof(buf));
        if (!c) { return -1; }
        return unixDialC(c);
}

/* A bound UDP socket on port, on bindaddr (empty: every address), or -1 */
pub i32 udpSocket(i32 port, u8[] bindaddr, i32 addrfam=libc.AF_UNSPEC,
                bool nonBlocking=false)
{
        u8 buf[256];
        return udpSocketC(port, netCStr(bindaddr, buf, sizeof(buf)), addrfam, nonBlocking);
}

/* data to addr:port from a UDP socket: the bytes sent, or -1 */
pub i64 sendTo(i32 sockfd, u8[] data, u8[] addr, i32 port)
{
        u8 buf[256];
        u8 *c = netCStr(addr, buf, sizeof(buf));
        if (!c) { return -1; }
        return sendToC(sockfd, data.ptr, data.len, c, port);
}

/* A datagram into buf: the bytes received, or -1; the sender's address
 * into *fromAddr (a String, replaced) and its port into *fromPort when
 * given */
pub i64 recvFrom(i32 sockfd, u8[] buf, String *fromAddr=null, i32 *fromPort=null)
{
        u8 abuf[64];
        abuf[0] = 0;
        i64 got = recvFromC(sockfd, buf.ptr, buf.len, fromAddr ? abuf : null,
                            fromAddr ? sizeof(abuf) : 0, fromPort);
        if (fromAddr && got >= 0) {
                fromAddr.clear();
                fromAddr.append(cstr.view(abuf));
        }
        return got;
}

/* ---- Binary addresses: a peer kept as the kernel gives it, so a UDP
 * server can answer a datagram without turning the sender into text and
 * back. ---- */

/* An IPv4 or IPv6 address and port, as a sockaddr (ss, len bytes of it).
 * Fill one with net.makeAddr, recvFromAddr, localAddr or remoteAddr; a
 * declared Addr is all zero (no address). */
pub struct Addr {
        libc.sockaddr_storage ss;
        u32 len;
};

static bool netAddrFill(Addr *a, libc.sockaddr *sa, u32 len) {
        if (len > sizeof(libc.sockaddr_storage) ||
            (sa.sa_family != libc.AF_INET && sa.sa_family != libc.AF_INET6)) {
                a.len = 0;
                return false;
        }
        mem.set(&a.ss, 0, sizeof(libc.sockaddr_storage));
        mem.copy(&a.ss, sa, len);
        a.len = len;
        return true;
}

/* *out for ip, a numeric IPv4 ("127.0.0.1") or IPv6 ("::1",
 * "fe80::1%en0") address, and port (no name lookup: use dial or sendTo
 * for host names). false, with out.len 0, when ip isn't one or the port
 * is outside 0..65535. */
pub bool makeAddr(u8[] ip, i32 port, Addr *out) {
        u8 buf[256], strport[6];
        libc.addrinfo hints, *res;
        out.len = 0;
        u8 *c = netCStr(ip, buf, sizeof(buf));
        if (!c || port < 0 || port > 65535) { return false; }
        /* IPv4 only as a full dotted quad: getaddrinfo also takes the old
         * inet_aton forms ("1.2.3", "0x7f.1") */
        u8 v4[16];
        if (!cstr.firstOf(c, ":") && libc.inet_pton(libc.AF_INET, c, v4) != 1) { return false; }
        libc.snprintf(strport, sizeof(strport), "%d", port);
        mem.set(&hints, 0, sizeof(libc.addrinfo));
        hints.ai_family = libc.AF_UNSPEC;
        hints.ai_socktype = libc.SOCK_DGRAM;
        hints.ai_flags = libc.AI_NUMERICHOST | libc.AI_NUMERICSERV;
        if (libc.getaddrinfo(c, strport, &hints, &res) != 0) { return false; }
        bool ok = netAddrFill(out, res.ai_addr, res.ai_addrlen);
        libc.freeaddrinfo(res);
        return ok;
}

/* libc.AF_INET or libc.AF_INET6, 0 for an empty Addr */
pub i32 Addr.family() {
        if (!self.len) { return 0; }
        return self.ss.ss_family;
}

/* The address as text ("192.0.2.7", "::1"), a String that is yours; one
 * without a ptr for an empty Addr */
pub String Addr.ip() {
        String out;
        if (!self.len) { return out; }
        return addrToStr((libc.sockaddr *)(&self.ss), self.ss.ss_family);
}

/* The port, -1 for an empty Addr */
pub i32 Addr.port() {
        if (self.len && self.ss.ss_family == libc.AF_INET) {
                return ntohs(((libc.sockaddr_in *)(&self.ss)).sin_port);
        }
        if (self.len && self.ss.ss_family == libc.AF_INET6) {
                return ntohs(((libc.sockaddr_in6 *)(&self.ss)).sin6_port);
        }
        return -1;
}

/* The same family and address, any port (the same host) */
pub bool Addr.sameIp(Addr *other) {
        if (!self.len || self.family() != other.family()) { return false; }
        if (self.ss.ss_family == libc.AF_INET) {
                return !libc.memcmp(&((libc.sockaddr_in *)(&self.ss)).sin_addr,
                                    &((libc.sockaddr_in *)(&other.ss)).sin_addr, 4);
        }
        return !libc.memcmp(&((libc.sockaddr_in6 *)(&self.ss)).sin6_addr,
                            &((libc.sockaddr_in6 *)(&other.ss)).sin6_addr, 16);
}

/* The same family, address and port */
pub bool Addr.equals(Addr *other) {
        return self.sameIp(other) && self.port() == other.port();
}

/* A datagram into buf, its sender into *from: the bytes received, or -1
 * (with errno set; *from is then empty) */
pub i64 recvFromAddr(i32 sockfd, u8[] buf, Addr *from) {
        from.len = sizeof(libc.sockaddr_storage);
        i64 got = libc.recvfrom(sockfd, buf.ptr, buf.len, 0,
                                (libc.sockaddr *)(&from.ss), &from.len);
        if (got < 0) { from.len = 0; }
        return got;
}

/* data as one datagram to *to (from recvFromAddr or makeAddr): the bytes
 * sent, or -1. Like send, never raises SIGPIPE. */
pub i64 sendToAddr(i32 sockfd, u8[] data, Addr *to) {
        if (!to.len) {
                os.setErrno(__NET_EINVAL);
                return -1;
        }
        return libc.sendto(sockfd, data.ptr, data.len, __NET_MSG_NOSIGNAL,
                           (libc.sockaddr *)(&to.ss), to.len);
}

/* The address sockfd is bound to (getsockname) into *out; false on an
 * error or a socket that isn't IPv4 or IPv6 */
pub bool localAddr(i32 sockfd, Addr *out) {
        libc.sockaddr_storage ss;
        u32 len = sizeof(libc.sockaddr_storage);
        out.len = 0;
        if (libc.getsockname(sockfd, (libc.sockaddr *)(&ss), &len) == -1) { return false; }
        return netAddrFill(out, (libc.sockaddr *)(&ss), len);
}

/* The address of sockfd's peer (getpeername) into *out; false on an
 * error, an unconnected socket or one that isn't IPv4 or IPv6 */
pub bool remoteAddr(i32 sockfd, Addr *out) {
        libc.sockaddr_storage ss;
        u32 len = sizeof(libc.sockaddr_storage);
        out.len = 0;
        if (libc.getpeername(sockfd, (libc.sockaddr *)(&ss), &len) == -1) { return false; }
        return netAddrFill(out, (libc.sockaddr *)(&ss), len);
}
