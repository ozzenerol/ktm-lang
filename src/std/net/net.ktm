module net;
import date, libc, mem, os, str;

/* Socket capabilities, a bit stripped down */
#ifdef IS_LINUX
  #define SOL_SOCKET      1   /* options for socket level */
  #define SO_DEBUG        1   /* turn on debugging info recording */
  #define SO_ACCEPTCONN   30  /* socket has had listen() */
  #define SO_REUSEADDR    2   /* allow local address reuse */
  #define SO_KEEPALIVE    9   /* keep connections alive */
  #define SO_DONTROUTE    5   /* just use interface addresses */
  #define SO_BROADCAST    6   /* permit sending of broadcast msgs */
  #define SO_LINGER       13  /* linger on close if data present (in seconds) */
  #define SO_OOBINLINE    10  /* leave received OOB data in line */
  #define SO_REUSEPORT    15  /* allow local address & port reuse */
  #define SO_TIMESTAMP    29  /* timestamp received dgram traffic */
  #define SO_SNDBUF       7               /* Send buffer size */
  #define SO_RCVBUF       8               /* Receive buffer size */
  #define SO_ERROR        4               /* Get and clear the pending error */
  #define SO_RCVTIMEO     20              /* Read timeout (a timeval) */
  #define SO_SNDTIMEO     21              /* Write timeout (a timeval) */
#else
/*
    * Option flags per-socket.
    */
  #define SOL_SOCKET      0xffff          /* options for socket level */
  #define SO_DEBUG        0x0001          /* turn on debugging info recording */
  #define SO_ACCEPTCONN   0x0002          /* socket has had listen() */
  #define SO_REUSEADDR    0x0004          /* allow local address reuse */
  #define SO_KEEPALIVE    0x0008          /* keep connections alive */
  #define SO_DONTROUTE    0x0010          /* just use interface addresses */
  #define SO_BROADCAST    0x0020          /* permit sending of broadcast msgs */
  #define SO_LINGER       0x1080          /* linger on close if data present (in seconds) */
  #define SO_OOBINLINE    0x0100          /* leave received OOB data in line */
  #define SO_REUSEPORT    0x0200          /* allow local address & port reuse */
  #define SO_TIMESTAMP    0x0400          /* timestamp received dgram traffic */
  #define SO_TIMESTAMP_MONOTONIC  0x0800  /* Monotonically increasing timestamp on rcvd dgram */
  #define SO_SNDBUF       0x1001          /* Send buffer size */
  #define SO_RCVBUF       0x1002          /* Receive buffer size */
  #define SO_ERROR        0x1007          /* Get and clear the pending error */
  #define SO_RCVTIMEO     0x1006          /* Read timeout (a timeval) */
  #define SO_SNDTIMEO     0x1005          /* Write timeout (a timeval) */
#endif

#ifdef IS_LINUX
  #define AF_INET6        10              /* IPv6 */
#else
  #define AF_INET6        30              /* IPv6 */
#endif

#ifdef IS_LINUX
  #define TCP_NODELAY             1       /* don't delay send to coalesce packets */
  #define TCP_MAXSEG              2       /* set maximum segment size */
        const i64 TCP_CORK = 3;       /* don't send out partial frames */
        const i64 TCP_KEEPIDLE = 4;       /* idle time used when SO_KEEPALIVE is enabled */
  #define TCP_KEEPALIVE           4       /* the macOS name of TCP_KEEPIDLE */
  #define TCP_KEEPINTVL           5       /* interval between keepalives */
  #define TCP_KEEPCNT             6       /* number of keepalives before close */
        const i64 TCP_INFO = 11;      /* information about this connection */
        const i64 TCP_QUICKACK = 12;      /* block/reenable quick acks */
        const i64 TCP_USER_TIMEOUT = 18;      /* how long for loss retry before timeout */
  #define TCP_FASTOPEN            23      /* Enable/Disable TCP Fastopen on this socket */
#else
  #define TCP_NODELAY             0x01    /* don't delay send to coalesce packets */
  #define TCP_MAXSEG              0x02    /* set maximum segment size */
  #define TCP_NOPUSH              0x04    /* don't push last block of write */
  #define TCP_NOOPT               0x08    /* don't use TCP options */
  #define TCP_KEEPALIVE           0x10    /* idle time used when SO_KEEPALIVE is enabled */
  #define TCP_CONNECTIONTIMEOUT   0x20    /* connection timeout */
  #define PERSIST_TIMEOUT         0x40    /* time after which a connection in
                                                                                                                                        *  persist timeout will terminate.
                                                                                                                                        *  see draft-ananth-tcpm-persist-02.txt
                                                                                                                                        */
  #define TCP_RXT_CONNDROPTIME    0x80    /* time after which tcp retransmissions will be
                                                                                                                                        * stopped and the connection will be dropped
                                                                                                                                        */
  #define TCP_RXT_FINDROP         0x100   /* when this option is set, drop a connection
                                                                                                                                        * after retransmitting the FIN 3 times. It will
                                                                                                                                        * prevent holding too many mbufs in socket
                                                                                                                                        * buffer queues.
                                                                                                                                        */
  #define TCP_KEEPINTVL           0x101   /* interval between keepalives */
  #define TCP_KEEPCNT             0x102   /* number of keepalives before close */
  #define TCP_SENDMOREACKS        0x103   /* always ack every other packet */
  #define TCP_ENABLE_ECN          0x104   /* Enable ECN on a connection */
  #define TCP_FASTOPEN            0x105   /* Enable/Disable TCP Fastopen on this socket */
  #define TCP_CONNECTION_INFO     0x106   /* State of TCP connection */
#endif

#ifdef IS_LINUX
  #define	AI_NUMERICSERV	0x00000400 /* prevent service name resolution */
#else
  #define	AI_NUMERICSERV	0x00001000 /* prevent service name resolution */
  #define	AI_UNUSABLE	0x10000000 /* return addresses even if unusable (i.e. opposite of AI_DEFAULT) */
#endif

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
        i32 fd = socket(domain, type, protocol);
        if (fd != -1) fcntl(fd, F_SETFD, FD_CLOEXEC);
        return fd;
#endif
}

static i32 netAcceptCloexec(i32 socket, libc.sockaddr *address, u32 *addressLen) {
#ifdef IS_LINUX
        return accept4(socket, address, addressLen, __NET_SOCK_CLOEXEC);
#else
        i32 fd = accept(socket, address, address_len);
        if (fd != -1) fcntl(fd, F_SETFD, FD_CLOEXEC);
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

/* Clear O_NONBLOCK again; true on success */
static bool netSetSocketBlocking(i32 sockfd) {
        i32 flags = libc.fcntl(sockfd, libc.F_GETFL, 0);
        if (flags == -1 || libc.fcntl(sockfd, libc.F_SETFL, flags & ~libc.O_NONBLOCK) == -1) {
                return false;
        }
        return true;
}

pub bool setReuseAddr(i32 sockfd) {
        i32 yes = 1;

        if (libc.setsockopt(sockfd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(i32)) == -1) {
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

        if (libc.setsockopt(sockfd, libc.IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(i32)) == -1) {
                return false;
        }

        return true;
}

static void *netGetInAddress(libc.sockaddr *sa)
{// The IPv4 or IPv6 address in `sa`, null for any other family
        if (sa.sa_family == libc.AF_INET) {
                return &(((libc.sockaddr_in*)sa).sin_addr);
        }
        if (sa.sa_family == AF_INET6) {
                return &(((libc.sockaddr_in6 *)sa).sin6_addr);
        }
        return null;
}

/* Fills out buf with the IPV4 address */
pub bool addrToStr(libc.sockaddr *sAddr, i32 ai_family, u8 *outbuf, i32 size) {
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
#define __NET_EINTR       4
#define __NET_EINVAL      22
#define __NET_ETIMEDOUT   110
#define __NET_EINPROGRESS 115
#else
#define __NET_EINTR       4
#define __NET_EINVAL      22
#define __NET_ETIMEDOUT   60
#define __NET_EINPROGRESS 36
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
                n = libc.poll(&pfd, 1, left);
                if (n > 0) {
                        break;
                }
                if (n == -1 && os.errno() != __NET_EINTR) {
                        return -1;
                }
                /* 0 (time is up) or EINTR: the loop checks the deadline again */
        }
        if (libc.getsockopt(sockfd, SOL_SOCKET, SO_ERROR, &err, &len) == -1) {
                return -1;
        }
        if (err != 0) {
                os.setErrno(err);
                return -1;
        }
        return 0;
}

pub i32 dial(u8 *addr, i32 port, bool nonBlocking=false,
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
                        if (!nonBlocking && !netSetSocketBlocking(sockfd)) { goto error; }
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

pub i32 listen(i32 port, u8 *bindaddr, i32 backlog,
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

        if (addrfam != libc.AF_UNSPEC && addrfam != libc.AF_INET && addrfam != AF_INET6) {
                return -1;
        }

        /* "*" (or null) is any address of addrfam, IPv4 for AF_UNSPEC as it
            * always was; "::*" is any IPv6 address. Anything else must be a
            * numeric address: a host name would wait on DNS */
        if (bindaddr != null && !str.cmp("::*", bindaddr)) {
                if (addrfam == libc.AF_INET) {
                        return -1;
                }
                addrfam = AF_INET6;
                bindaddr = null;
        } else if (bindaddr != null && !str.cmp("*", bindaddr)) {
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
        } else if (!netSetSocketBlocking(accepted)) {
                libc.close(accepted);
                return -1;
        }
        return accepted;
}

#ifdef IS_LINUX
#define __NET_MSG_NOSIGNAL 0x4000
#else
#define __NET_MSG_NOSIGNAL 0x80000
#endif

pub i64 send(i32 sockfd, void *buf, i64 len, i32 flags=0)
{// send() that never raises SIGPIPE: a closed peer gives -1 (EPIPE)
        return libc.send(sockfd, buf, len, flags | __NET_MSG_NOSIGNAL);
}

/**
    * Socket needs to be accepted for this to work, a better function
    * could probably be made as this only accepts IPV4 which doesn't feel
    * right.
    */
pub bool peerAddr(i32 sockfd, u8 *ipbuf, i32 outlen) {
        libc.sockaddr_storage sa; /* a sockaddr is too small for an IPv6 peer */
        u32 len;
        i32 retval;

        len = sizeof(libc.sockaddr_storage);
        if ((retval = libc.getpeername(sockfd, (libc.sockaddr *)(&sa), &len)) == -1) {
                return false;
        }

        return addrToStr((libc.sockaddr *)(&sa), sa.ss_family, ipbuf, outlen);
}

/* Fills `addr` for the socket file `name`; false if the path doesn't fit */
static bool netUnixAddress(libc.sockaddr_un *addr, u8 *name) {
        i64 len = str.len(name);
        if (len >= sizeof(addr.sun_path)) {
                return false;
        }
        mem.set(addr, 0, sizeof(libc.sockaddr_un));
#ifndef IS_LINUX
        addr->sun_len = sizeof(sockaddr_un);
#endif
        addr.sun_family = libc.AF_UNIX;
        mem.copy(addr.sun_path, name, len + 1);
        return true;
}

#ifdef IS_LINUX
#define __NET_ECONNREFUSED 111
#else
#define __NET_ECONNREFUSED 61
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

pub i32 unixListen(u8 *name, i32 backlog)
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

pub i32 unixDial(u8 *name)
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
        if (ss.ss_family == AF_INET6) {
                return ntohs(((libc.sockaddr_in6 *)(&ss)).sin6_port);
        }
        return -1;
}

pub i32 udpSocket(i32 port, u8 *bindaddr, i32 addrfam=libc.AF_UNSPEC,
                bool nonBlocking=false)
{// Returns the bound UDP socket, or -1 on failure
        i32 sockfd = -1;
        u8 strport[6];
        libc.addrinfo hints, *servinfo, *ptr;

        if (port < 0 || port > 65535) {
                return -1;
        }
        if (addrfam != libc.AF_UNSPEC && addrfam != libc.AF_INET && addrfam != AF_INET6) {
                return -1;
        }
        libc.snprintf(strport, sizeof(strport), "%d", port);

        /* The same bind addresses as NetCreateServer */
        if (bindaddr != null && !str.cmp("::*", bindaddr)) {
                if (addrfam == libc.AF_INET) {
                        return -1;
                }
                addrfam = AF_INET6;
                bindaddr = null;
        } else if (bindaddr != null && !str.cmp("*", bindaddr)) {
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

pub i64 sendTo(i32 sockfd, void *buf, i64 len, u8 *addr, i32 port)
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

pub i64 recvFrom(i32 sockfd, void *buf, i64 len, u8 *fromAddr=null,
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
                if (!addrToStr((libc.sockaddr *)(&from), from.ss_family, fromAddr, fromSize)) {
                        fromAddr[0] = '\0';
                }
        }
        if (fromPort != null) {
                if (from.ss_family == libc.AF_INET) {
                        *fromPort = ntohs(((libc.sockaddr_in *)(&from)).sin_port);
                } else if (from.ss_family == AF_INET6) {
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
        return libc.setsockopt(sockfd, SOL_SOCKET, opt, &tv, sizeof(libc.timeval)) == 0;
}

pub bool setRecvTimeout(i32 sockfd, i64 ms)
{// true if SO_RCVTIMEO was set
        return netSetTimeout(sockfd, SO_RCVTIMEO, ms);
}

pub bool setSendTimeout(i32 sockfd, i64 ms)
{// true if SO_SNDTIMEO was set
        return netSetTimeout(sockfd, SO_SNDTIMEO, ms);
}
