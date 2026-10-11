module c;

/*
 * Address families.
 */
#define AF_UNSPEC       0               /* unspecified */

#define AF_UNIX         1               /* local to host (pipes) */

#define AF_INET         2               /* internetwork: UDP, TCP, etc. */

#define SOCK_STREAM     1               /* stream socket */

#define SOCK_DGRAM      2               /* datagram socket */

#define SOCK_RAW        3               /* raw-protocol interface */

#define SOCK_SEQPACKET  5               /* sequenced packet stream */

#define IPPROTO_IP              0               /* dummy for IP */

#define IPPROTO_ICMP            1               /* control message protocol */

#define IPPROTO_IPV4            4               /* IPv4 encapsulation */

#define IPPROTO_TCP             6               /* tcp */

#define IPPROTO_UDP             17              /* user datagram protocol */

#define IPPROTO_IPV6            41              /* IP6 header */

#define IPPROTO_ICMPV6          58              /* ICMP6 */

#define IPPROTO_RAW             255             /* raw IP packet */

#define INET6_ADDRSTRLEN        46

#define	AI_PASSIVE	0x00000001 /* get address to use bind() */

#define	AI_CANONNAME	0x00000002 /* fill ai_canonname */

#define	AI_NUMERICHOST	0x00000004 /* prevent host name resolution */

public class hostent
{
  U8	*h_name;	/* official name of host */
  U8	**h_aliases;	/* alias list */
  I32 h_addrtype;	/* host address type */
  I32 h_length;	/* length of address */
  U8	**h_addr_list;	/* list of addresses from name server */
};

/*
 * Assumption here is that a network number
 * fits in an unsigned long -- probably a poor one.
 */
public class netent
{
  U8  *n_name;      /* official name of net */
  U8  **n_aliases;  /* alias list */
  I32 n_addrtype;   /* net address type */
  U32 n_net;        /* network # */
};

public class servent
{
  U8 *s_name;	  /* official service name */
  U8 **s_aliases; /* alias list */
  I32 s_port;     /* port # */
  U8 *s_proto;    /* protocol to use */
};

public class protoent
{
  U8  *p_name;     /* official protocol name */
  U8  **p_aliases; /* alias list */
  I32 p_proto;     /* protocol # */
};

/* Linux has no length byte: the family is a U16 at the start. The BSDs and
 * macOS start with a U8 length and a U8 family. */
public class sockaddr
{
#ifdef IS_LINUX
  U16 sa_family;
#else
  U8 sa_len;
  U8 sa_family;
#endif
  U8 sa_data[14];
};

public class in_addr
{
  U32 s_addr;
};

public class sockaddr_in
{
#ifdef IS_LINUX
  U16 sin_family;
#else
  U8 sin_len;
  U8 sin_family;
#endif
  U16 sin_port;
  in_addr sin_addr;
  U8 sin_zero[8];
};

public class in6_addr
{
  union
  {
    U8   __u6_addr8[16];
    U16  __u6_addr16[8];
    U32  __u6_addr32[4];
  };                    /* 128-bit IP6 address */
};

public class sockaddr_in6
{
#ifdef IS_LINUX
  U16 sin6_family;    /* AF_INET6 (sa_family_t) */
#else
  U8  sin6_len;        /* length of this struct(sa_family_t) */
  U8  sin6_family;     /* AF_INET6 (sa_family_t) */
#endif
  U16 sin6_port;      /* Transport layer port # (in_port_t) */
  U32 sin6_flowinfo;  /* IP6 flow information */
  in6_addr sin6_addr; /* IP6 address */
  U32 sin6_scope_id;  /* scope zone index */
};

public class addrinfo;

public class sockaddr_storage
{
#ifdef IS_LINUX
  U16 ss_family;      /* address family */
#else
  U8  ss_len;         /* address length */
  U8  ss_family;      /* [XSI] address family */
#endif
  U8  __ss_pad1[_SS_PAD1SIZE];
  I64 __ss_align;     /* force structure storage alignment */
  U8  __ss_pad2[_SS_PAD2SIZE];
};

public class msghdr
{
  U0    *msg_name;      /* Optional address */
  U32   msg_namelen;    /* Size of the address (socklen_t) */
  iovec *msg_iov;       /* Scatter/gather array of buffers */
#ifdef IS_LINUX
  U64   msg_iovlen;     /* Number of elements in msg_iov (size_t) */
  U0    *msg_control;   /* Ancillary data */
  U64   msg_controllen; /* Length of ancillary data buffer (size_t) */
#else
  I32   msg_iovlen;     /* Number of elements in msg_iov (int) */
  U0    *msg_control;   /* Ancillary data */
  U32   msg_controllen; /* Length of ancillary data buffer (socklen_t) */
#endif
  I32   msg_flags;      /* Flags on received message */
};

public class sockaddr_un
{
#ifdef IS_LINUX
  U16 sun_family;    /* AF_UNIX */
  U8 sun_path[108];  /* path name */
#else
  U8 sun_len;        /* sockaddr len including null */
  U8 sun_family;     /* [XSI] AF_UNIX */
  U8 sun_path[104];  /* [XSI] path name (gag) */
#endif
};

public extern "c" I32 setsockopt(I32 __sockfd, I32 __level, I32 __option_name,
    U0 *__option_value, U32 __option_len);

public extern "c" I32 getsockopt(I32 socket, I32 level, I32 option_name,
    U0 * option_value, U32 *option_len);

public extern "c" I32 shutdown(I32 socket, I32 how);

public extern "c" U32 inet_addr(U8 *cp);

public extern "c" I32 inet_aton(U8 *cp, in_addr *pin);

public extern "c" U32 inet_lnaof(in_addr in);

public extern "c" in_addr inet_makeaddr(U32 net, U32 lna);

public extern "c" U32 inet_netof(in_addr in);

public extern "c" U32 inet_network(U8 *cp);

public extern "c" U8 *inet_ntoa(in_addr in);

public extern "c" U8 *inet_ntop(I32 af, U0 *src, U8 *dst, I64 size);

public extern "c" I32 inet_pton(I32 af, U8 *src, U0 *dst);

public extern "c" U0 endhostent();

public extern "c" U0 endnetent();

public extern "c" U0 endprotoent();

public extern "c" U0 endservent();

public extern "c" U0 freeaddrinfo(addrinfo *info);

public extern "c" U8 *gai_strerror(I32 __err);

public extern "c" I32 getaddrinfo(U8 *hostname, U8 *servname, addrinfo *hints, addrinfo **res);

/* Deprecated by POSIX (IPv4 only, not thread-safe): use getaddrinfo */
public extern "c" hostent *gethostbyaddr(U0 *ptr, I64 len, I32 type);

/* Deprecated by POSIX (IPv4 only, not thread-safe): use getaddrinfo */
public extern "c" hostent *gethostbyname(U8 *name);

public extern "c" hostent *gethostent();

public extern "c" I32 getnameinfo(sockaddr *sa, I64 salen, U8 *host,
    I64 hostlen, U8 *serv, I64 servlen, I32 flags);

public extern "c" netent *getnetbyaddr(U32 net, I32 type);

public extern "c" netent *getnetbyname(U8 *name);

public extern "c" netent *getnetent();

public extern "c" protoent *getprotobyname(U8 *name);

public extern "c" protoent *getprotobynumber(I32 proto);

public extern "c" protoent *getprotoent();

public extern "c" servent *getservbyname(U8 *name, U8 *proto);

public extern "c" servent *getservbyport(I32 port, U8 *proto);

public extern "c" servent *getservent();

public extern "c" U0 sethostent(I32 stayopen);

public extern "c" U0 setnetent(I32 stayopen);

public extern "c" U0 setprotoent(I32 stayopen);

public extern "c" U0 setservent(I32 stayopen);

public extern "c" U8 *hstrerror(I32 err);

public extern "c" I32 socket(I32 domain, I32 type, I32 protocol);

public extern "c" I32 connect(I32 socket, sockaddr *address, U32 address_len);

public extern "c" I32 accept(I32 socket, sockaddr *address, U32 *address_len);

public extern "c" I32 bind(I32 socket, sockaddr *address, U32 address_len);

public extern "c" I32 getpeername(I32 socket, sockaddr *address, U32 *address_len);

public extern "c" I64 recvmsg(I32 socket, msghdr *message, I32 flags);

public extern "c" I64 recvfrom(I32 socket, U0 *buffer, U64 length, I32 flags, sockaddr *address, U32 *address_len);

public extern "c" I64 recv(I32 socket, U0 *buffer, U64 length, I32 flags);

public extern "c" I32 listen(I32 socket, I32 backlog);

public extern "c" I32 getsockname(I32 socket, sockaddr *address, U32 *address_len);

public extern "c" I64 send(I32 socket, U0 *buffer, U64 length, I32 flags);

public extern "c" I64 sendmsg(I32 socket, msghdr *message, I32 flags);

public extern "c" I64 sendto(I32 socket, U0 *buffer, U64 length, I32 flags, sockaddr *dest_addr, U32 dest_len);

extern "c" I32 poll(NetPollFd *fds, U64 nfds, I32 timeout);
