module net;

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
  #define TCP_CORK                3       /* don't send out partial frames */
  #define TCP_KEEPIDLE            4       /* idle time used when SO_KEEPALIVE is enabled */
  #define TCP_KEEPALIVE           4       /* the macOS name of TCP_KEEPIDLE */
  #define TCP_KEEPINTVL           5       /* interval between keepalives */
  #define TCP_KEEPCNT             6       /* number of keepalives before close */
  #define TCP_INFO                11      /* information about this connection */
  #define TCP_QUICKACK            12      /* block/reenable quick acks */
  #define TCP_USER_TIMEOUT        18      /* how long for loss retry before timeout */
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
public U16 Htons(U16 x)
{
  return ((x & 0xFF) << 8) | ((x >> 8) & 0xFF);
}

public U16 Ntohs(U16 x)
{
  return Htons(x);
}

public U32 Htonl(U32 x)
{
  return ((x & 0xFF) << 24) | ((x & 0xFF00) << 8) |
         ((x >> 8) & 0xFF00) | ((x >> 24) & 0xFF);
}

public U32 Ntohl(U32 x)
{
  return Htonl(x);
}

#ifdef IS_LINUX
#define __NET_SOCK_CLOEXEC 0x80000
extern "c" I32 accept4(I32 socket, sockaddr *address, U32 *address_len, I32 flags);
#endif

/* The library's sockets are close-on-exec, so programs started with
 * System or exec don't inherit them. macOS has no SOCK_CLOEXEC or
 * accept4: set FD_CLOEXEC right after (a fork in another thread in
 * between can still inherit it there). */
static I32 NetSocket(I32 domain, I32 type, I32 protocol)
{
#ifdef IS_LINUX
  return socket(domain, type | __NET_SOCK_CLOEXEC, protocol);
#else
  I32 fd = socket(domain, type, protocol);
  if (fd != -1) fcntl(fd, F_SETFD, FD_CLOEXEC);
  return fd;
#endif
}

static I32 NetAcceptCloexec(I32 socket, sockaddr *address, U32 *address_len)
{
#ifdef IS_LINUX
  return accept4(socket, address, address_len, __NET_SOCK_CLOEXEC);
#else
  I32 fd = accept(socket, address, address_len);
  if (fd != -1) fcntl(fd, F_SETFD, FD_CLOEXEC);
  return fd;
#endif
}

public Bool NetSetSocketNonBlocking(I32 sockfd)
{
  I32 flags = fcntl(sockfd, F_GETFL, 0);
  if (fcntl(sockfd,F_SETFL,flags|O_NONBLOCK)) {
    return FALSE;
  }
  return TRUE;
}

/* Clear O_NONBLOCK again; TRUE on success */
static Bool NetSetSocketBlocking(I32 sockfd)
{
  I32 flags = fcntl(sockfd, F_GETFL, 0);
  if (flags == -1 || fcntl(sockfd, F_SETFL, flags & ~O_NONBLOCK) == -1)
    return FALSE;
  return TRUE;
}

public Bool NetSetSockReuseAddr(I32 sockfd)
{
  I32 yes = 1;

  if (setsockopt(sockfd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(I32)) == -1)
    return FALSE;

  return TRUE;
}

/* Set TCP no delay, turn off Nagle buffering. Makes the socket sent data as soon
 * as it is avalible. Returns TRUE on success and FALSE if setsockopt fails.
 *
 * https://www.extrahop.com/company/blog/2016/tcp-nodelay-nagle-quickack-best-practices/
 * */
public Bool NetSetSocketNoDelay(I32 sockfd)
{
  I32 yes = 1;

  if (setsockopt(sockfd, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(I32)) == -1)
    return FALSE;

  return TRUE;
}

static U0 *NetGetInAddress(sockaddr *sa)
{// The IPv4 or IPv6 address in `sa`, NULL for any other family
  if (sa->sa_family == AF_INET)
    return &(sa(sockaddr_in*)->sin_addr);
  if (sa->sa_family == AF_INET6)
    return &(sa(sockaddr_in6 *)->sin6_addr);
  return NULL;
}

/* Fills out buf with the IPV4 address */
public Bool NetGetIpv4Address(sockaddr *sAddr, I32 ai_family, U8 *outbuf, I32 size)
{
  U0 *address;
  if (sAddr->sa_family != ai_family)
    return FALSE;
  address = NetGetInAddress(sAddr);
  if (address == NULL)
    return FALSE;
  if (inet_ntop(ai_family, address, outbuf, size) == NULL)
    return FALSE;
  return TRUE;
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

#define __NET_POLLOUT     0x4

class NetPollFd
{
  I32 fd;
  I16 events;
  I16 revents;
};

/* Connect sockfd to `sa`, giving up when `deadline` (NowMilliseconds) is
 * reached: a non-blocking connect, a poll for writability, then SO_ERROR.
 * The socket is left non-blocking. 0 when connected, otherwise -1 with
 * errno set (ETIMEDOUT when the time ran out). */
static I32 NetConnectBefore(I32 sockfd, addrinfo *sa, I64 deadline)
{
  NetPollFd pfd;
  I32 err = 0, n;
  U32 len = sizeof(I32);
  I64 left;

  if (!NetSetSocketNonBlocking(sockfd))
    return -1;
  if (connect(sockfd, sa->ai_addr, sa->ai_addrlen) == 0)
    return 0;
  if (Errno() != __NET_EINPROGRESS)
    return -1;

  while (1) {
    left = deadline - NowMilliseconds;
    if (left <= 0) {
      SetErrno(__NET_ETIMEDOUT);
      return -1;
    }
    pfd.fd = sockfd;
    pfd.events = __NET_POLLOUT;
    pfd.revents = 0;
    n = poll(&pfd, 1, left);
    if (n > 0)
      break;
    if (n == -1 && Errno() != __NET_EINTR)
      return -1;
    /* 0 (time is up) or EINTR: the loop checks the deadline again */
  }
  if (getsockopt(sockfd, SOL_SOCKET, SO_ERROR, &err, &len) == -1)
    return -1;
  if (err != 0) {
    SetErrno(err);
    return -1;
  }
  return 0;
}

public I32 NetConnect(U8 *addr, I32 port, Bool non_blocking=FALSE,
    I64 timeout_ms=0)
{// Returns the connected socket, or -1 on failure
  U8 strport[6];
  I32 sockfd, err = 0;
  addrinfo hints, *servinfo, *ptr;
  I64 deadline = 0;

  if (port < 0 || port > 65535 || timeout_ms < 0) {
    SetErrno(__NET_EINVAL);
    return -1;
  }
  snprintf(strport, sizeof(strport), "%d", port);
  MemSet(&hints, 0, sizeof(addrinfo));
  hints.ai_family = AF_UNSPEC; /* IPv4 or IPv6, whatever addr resolves to */
  hints.ai_socktype = SOCK_STREAM;
  sockfd = -1;

  if (getaddrinfo(addr, strport, &hints, &servinfo) != 0) {
    return -1;
  }
  /* The name lookup above can't be cut short; the time counts from here */
  if (timeout_ms > 0)
    deadline = NowMilliseconds + timeout_ms;

  /* connect on the first one we can */
  for (ptr = servinfo; ptr != NULL; ptr = ptr->ai_next) {
    if ((sockfd = NetSocket(ptr->ai_family, ptr->ai_socktype,
            ptr->ai_protocol)) == -1) {
      err = Errno();
      continue;
    }

    if (timeout_ms > 0) {
      if (NetConnectBefore(sockfd, ptr, deadline) == -1) {
        err = Errno();
        close(sockfd); /* try the next address with the time that's left */
        sockfd = -1;
        if (err == __NET_ETIMEDOUT) break;
        continue;
      }
      if (!non_blocking && !NetSetSocketBlocking(sockfd)) goto error;
    } else if (connect(sockfd, ptr->ai_addr, ptr->ai_addrlen) == -1) {
      err = Errno();
      close(sockfd); /* try the next address */
      sockfd = -1;
      continue;
    }
    if (!NetSetSockReuseAddr(sockfd)) goto error;
    if (non_blocking)
      if (!NetSetSocketNonBlocking(sockfd)) goto error;

    break;
  }

  if (sockfd == -1) {
    goto error;
  }

  freeaddrinfo(servinfo);

  return sockfd;

error:
  if (sockfd != -1) {
    err = Errno();
    close(sockfd);
  }
  freeaddrinfo(servinfo);
  if (err) SetErrno(err);
  return -1;
}

public Bool NetListen(I32 sockfd, addrinfo *sa, I32 backlog)
{
  if (bind(sockfd, sa->ai_addr, sa->ai_addrlen) == -1) {
    close(sockfd);
    return FALSE;
  }

  if (listen(sockfd, backlog) == -1) {
    close(sockfd);
    return FALSE;
  }
  return TRUE;
}

public I32 NetCreateServer(I32 port, U8 *bindaddr, I32 backlog,
    I32 addrfam=AF_UNSPEC, Bool non_blocking=FALSE)
{// Returns the listening socket, or -1 on failure
  I32 sockfd = -1, rv;
  U8 strport[6];
  addrinfo hints, *servinfo, *ptr;

  if (port < 0 || port > 65535) {
    return -1;
  }
  snprintf(strport, sizeof(strport), "%d", port);
  MemSet(&hints, 0, sizeof(addrinfo));

  if (addrfam != AF_UNSPEC && addrfam != AF_INET && addrfam != AF_INET6)
    return -1;

  /* "*" (or NULL) is any address of addrfam, IPv4 for AF_UNSPEC as it
   * always was; "::*" is any IPv6 address. Anything else must be a
   * numeric address: a host name would wait on DNS */
  if (bindaddr != NULL && !StrCmp("::*", bindaddr)) {
    if (addrfam == AF_INET)
      return -1;
    addrfam = AF_INET6;
    bindaddr = NULL;
  } else if (bindaddr != NULL && !StrCmp("*", bindaddr)) {
    bindaddr = NULL;
  }
  if (bindaddr == NULL && addrfam == AF_UNSPEC)
    addrfam = AF_INET;

  hints.ai_family = addrfam;
  hints.ai_socktype = SOCK_STREAM;
  hints.ai_flags = AI_PASSIVE | AI_NUMERICHOST;

  if ((rv = getaddrinfo(bindaddr, strport, &hints, &servinfo)) != 0)
    return -1;

  for (ptr = servinfo; ptr != NULL; ptr = ptr->ai_next) {
    if ((sockfd = NetSocket(ptr->ai_family, ptr->ai_socktype,
            ptr->ai_protocol)) == -1)
      continue;

    if (!NetSetSockReuseAddr(sockfd)) {
      close(sockfd); /* try the next address */
      sockfd = -1;
      continue;
    }
    if (!NetListen(sockfd, ptr, backlog)) {
      sockfd = -1; /* NetListen closed it; try the next address */
      continue;
    }
    if (non_blocking)
      if (!NetSetSocketNonBlocking(sockfd)) goto error;
    break;
  }

  if (ptr == NULL) {
    goto error;
  }

  freeaddrinfo(servinfo);
  return sockfd;

error:
  if (sockfd != -1) close(sockfd);
  freeaddrinfo(servinfo);
  return -1;
}

public I32 NetAccept(I32 socket, Bool non_blocking=FALSE)
{// Returns the accepted socket, or -1 on failure
  I32 accepted;
  U32 socklen = sizeof(sockaddr_storage);
  sockaddr_storage in_addr;

  if ((accepted = NetAcceptCloexec(socket,(&in_addr)(sockaddr *),&socklen)) == -1) {
    return -1;
  }

  /* Set the mode either way: BSD accept (macOS) copies O_NONBLOCK from
   * the listening socket, Linux's doesn't. */
  if (non_blocking) {
    if (!NetSetSocketNonBlocking(accepted)) {
      close(accepted);
      return -1;
    }
  } else if (!NetSetSocketBlocking(accepted)) {
    close(accepted);
    return -1;
  }
  return accepted;
}

#ifdef IS_LINUX
#define __NET_MSG_NOSIGNAL 0x4000
#else
#define __NET_MSG_NOSIGNAL 0x80000
#endif

public I64 NetSend(I32 sockfd, U0 *buf, I64 len, I32 flags=0)
{// send() that never raises SIGPIPE: a closed peer gives -1 (EPIPE)
  return send(sockfd, buf, len, flags | __NET_MSG_NOSIGNAL);
}

/**
 * Socket needs to be accepted for this to work, a better function
 * could probably be made as this only accepts IPV4 which doesn't feel
 * right.
 */
public Bool NetGetIpv4AddressFromAcceptedSocket(I32 sockfd, U8 *ipbuf, I32 outlen)
{
  sockaddr_storage sa; /* a sockaddr is too small for an IPv6 peer */
  U32 len;
  I32 retval;

  len = sizeof(sockaddr_storage);
  if ((retval = getpeername(sockfd, (&sa)(sockaddr *), &len)) == -1) {
    return FALSE;
  }

  return NetGetIpv4Address((&sa)(sockaddr *), sa.ss_family, ipbuf, outlen);
}

/* Fills `addr` for the socket file `name`; FALSE if the path doesn't fit */
static Bool NetUnixAddress(sockaddr_un *addr, U8 *name)
{
  I64 len = StrLen(name);
  if (len >= sizeof(addr->sun_path)) {
    return FALSE;
  }
  MemSet(addr, 0, sizeof(sockaddr_un));
#ifndef IS_LINUX
  addr->sun_len = sizeof(sockaddr_un);
#endif
  addr->sun_family = AF_UNIX;
  MemCpy(addr->sun_path, name, len + 1);
  return TRUE;
}

#ifdef IS_LINUX
#define __NET_ECONNREFUSED 111
#else
#define __NET_ECONNREFUSED 61
#endif

/* TRUE if `name` is a socket file left behind by a server that is gone:
 * connecting to it is refused. FALSE for anything else at that path,
 * including a socket a server still listens on (which sees a connection
 * that closes at once). */
static Bool NetUnixSocketIsStale(sockaddr_un *addr, U8 *name)
{
  cStat st;
  I32 fd, rv;

  if (lstat(name, &st) != 0 || (st.st_mode & S_IFMT) != S_IFSOCK)
    return FALSE;
  if ((fd = NetSocket(AF_UNIX, SOCK_STREAM, 0)) == -1)
    return FALSE;
  rv = connect(fd, addr(sockaddr *), sizeof(sockaddr_un));
  Bool stale = rv == -1 && Errno() == __NET_ECONNREFUSED;
  close(fd);
  return stale;
}

public I32 NetCreateUnixServerSocket(U8 *name, I32 backlog)
{// Listens on the socket file `name`; returns the socket or -1 on failure.
 // A stale socket file at `name` is replaced; any other file, or a socket
 // a server is listening on, makes it fail.
  I32 sockfd = -1;
  sockaddr_un srv;

  if (!NetUnixAddress(&srv, name))
    return -1;

  if ((sockfd = NetSocket(AF_UNIX, SOCK_STREAM, 0)) == -1)
    goto error;

  if (NetUnixSocketIsStale(&srv, name))
    unlink(name);
  if ((bind(sockfd, (&srv)(sockaddr *), sizeof(sockaddr_un))) == -1)
    goto error;

  if (listen(sockfd, backlog) == -1)
    goto error;

  return sockfd;

error:
  if (sockfd != -1) {
    close(sockfd);
  }
  return -1;
}

public I32 NetCreateUnixClientSocket(U8 *name)
{// Connects to the socket file `name`; returns the socket or -1 on failure
  I32 sockfd;
  sockaddr_un srv;

  if (!NetUnixAddress(&srv, name))
    return -1;

  if ((sockfd = NetSocket(AF_UNIX, SOCK_STREAM, 0)) == -1)
    return -1;

  if (connect(sockfd, (&srv)(sockaddr *), sizeof(sockaddr_un)) == -1) {
    close(sockfd);
    return -1;
  }
  return sockfd;
}

public I32 NetGetSocketPort(I32 sockfd)
{// The local port of sockfd, or -1
  sockaddr_storage ss;
  U32 len = sizeof(sockaddr_storage);

  if (getsockname(sockfd, (&ss)(sockaddr *), &len) == -1)
    return -1;
  if (ss.ss_family == AF_INET)
    return Ntohs((&ss)(sockaddr_in *)->sin_port);
  if (ss.ss_family == AF_INET6)
    return Ntohs((&ss)(sockaddr_in6 *)->sin6_port);
  return -1;
}

public I32 NetCreateUdpSocket(I32 port, U8 *bindaddr, I32 addrfam=AF_UNSPEC,
    Bool non_blocking=FALSE)
{// Returns the bound UDP socket, or -1 on failure
  I32 sockfd = -1;
  U8 strport[6];
  addrinfo hints, *servinfo, *ptr;

  if (port < 0 || port > 65535)
    return -1;
  if (addrfam != AF_UNSPEC && addrfam != AF_INET && addrfam != AF_INET6)
    return -1;
  snprintf(strport, sizeof(strport), "%d", port);

  /* The same bind addresses as NetCreateServer */
  if (bindaddr != NULL && !StrCmp("::*", bindaddr)) {
    if (addrfam == AF_INET)
      return -1;
    addrfam = AF_INET6;
    bindaddr = NULL;
  } else if (bindaddr != NULL && !StrCmp("*", bindaddr)) {
    bindaddr = NULL;
  }
  if (bindaddr == NULL && addrfam == AF_UNSPEC)
    addrfam = AF_INET;

  MemSet(&hints, 0, sizeof(addrinfo));
  hints.ai_family = addrfam;
  hints.ai_socktype = SOCK_DGRAM;
  hints.ai_flags = AI_PASSIVE | AI_NUMERICHOST;

  if (getaddrinfo(bindaddr, strport, &hints, &servinfo) != 0)
    return -1;

  for (ptr = servinfo; ptr != NULL; ptr = ptr->ai_next) {
    if ((sockfd = NetSocket(ptr->ai_family, ptr->ai_socktype,
            ptr->ai_protocol)) == -1)
      continue;
    if (bind(sockfd, ptr->ai_addr, ptr->ai_addrlen) == -1) {
      close(sockfd); /* try the next address */
      sockfd = -1;
      continue;
    }
    if (non_blocking && !NetSetSocketNonBlocking(sockfd)) {
      close(sockfd);
      sockfd = -1;
    }
    break;
  }

  freeaddrinfo(servinfo);
  return sockfd;
}

public I64 NetSendTo(I32 sockfd, U0 *buf, I64 len, U8 *addr, I32 port)
{// Returns the bytes sent, or -1 on failure
  sockaddr_storage me;
  U32 melen = sizeof(sockaddr_storage);
  U8 strport[6];
  addrinfo hints, *servinfo;
  I64 sent;

  if (port < 0 || port > 65535 || addr == NULL)
    return -1;
  /* Resolve addr in the socket's own family */
  if (getsockname(sockfd, (&me)(sockaddr *), &melen) == -1)
    return -1;
  snprintf(strport, sizeof(strport), "%d", port);
  MemSet(&hints, 0, sizeof(addrinfo));
  hints.ai_family = me.ss_family;
  hints.ai_socktype = SOCK_DGRAM;

  if (getaddrinfo(addr, strport, &hints, &servinfo) != 0)
    return -1;
  /* like NetSend: a socket that can't deliver gives -1, never SIGPIPE */
  sent = sendto(sockfd, buf, len, __NET_MSG_NOSIGNAL, servinfo->ai_addr,
      servinfo->ai_addrlen);
  freeaddrinfo(servinfo);
  return sent;
}

public I64 NetRecvFrom(I32 sockfd, U0 *buf, I64 len, U8 *from_addr=NULL,
    I64 from_size=0, I32 *from_port=NULL)
{// Returns the bytes received, or -1
  sockaddr_storage from;
  U32 fromlen = sizeof(sockaddr_storage);
  I64 got;

  if (len < 0)
    return -1;
  MemSet(&from, 0, sizeof(sockaddr_storage));
  got = recvfrom(sockfd, buf, len, 0, (&from)(sockaddr *), &fromlen);
  if (got == -1)
    return -1;

  if (from_addr != NULL && from_size > 0) {
    if (!NetGetIpv4Address((&from)(sockaddr *), from.ss_family, from_addr, from_size))
      from_addr[0] = '\0';
  }
  if (from_port != NULL) {
    if (from.ss_family == AF_INET)
      *from_port = Ntohs((&from)(sockaddr_in *)->sin_port);
    else if (from.ss_family == AF_INET6)
      *from_port = Ntohs((&from)(sockaddr_in6 *)->sin6_port);
    else
      *from_port = -1;
  }
  return got;
}

/* SO_RCVTIMEO or SO_SNDTIMEO */
static Bool NetSetTimeout(I32 sockfd, I32 opt, I64 ms)
{
  timeval tv;

  if (ms < 0)
    return FALSE;
  /* tv_usec is a long on Linux, an int on macOS: zero the whole struct */
  MemSet(&tv, 0, sizeof(timeval));
  tv.tv_sec = ms / 1000;
  tv.tv_usec = (ms % 1000) * 1000;
  return setsockopt(sockfd, SOL_SOCKET, opt, &tv, sizeof(timeval)) == 0;
}

public Bool NetSetRecvTimeout(I32 sockfd, I64 ms)
{// TRUE if SO_RCVTIMEO was set
  return NetSetTimeout(sockfd, SO_RCVTIMEO, ms);
}

public Bool NetSetSendTimeout(I32 sockfd, I64 ms)
{// TRUE if SO_SNDTIMEO was set
  return NetSetTimeout(sockfd, SO_SNDTIMEO, ms);
}
