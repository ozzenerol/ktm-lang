module c;

#define F_DUPFD 0  

#define F_GETFD 1  

#define F_SETFD 2  

#define F_GETFL 3  

#define F_SETFL 4  

#define F_FLUSH_DATA  40

#define F_CHKCLEAN    41

#define F_PREALLOCATE 42

#define F_SETSIZE     43

#define F_RDADVISE    44

#define F_RDAHEAD     45

#define FD_CLOEXEC 1

#define O_RDONLY  0x0000

#define O_WRONLY  0x0001

#define O_RDWR    0x0002

#define O_ACCMODE 0x0003

#define SEEK_SET 0

#define SEEK_CUR 1

#define SEEK_END 2

/*XXX: Should create a script that converts a select amount of c-class to 
 * ktm bindings so that they can be called and variance between operating systems
 * can be accounted for, i.e: the return value of `stat()` and flags */ 
extern "c" I32 open(U8 *__name, I32 flags, ...);

extern "c" I32 creat(U8 *__name, U16 __flags);

extern "c" I32 fcntl(I32 __fd, I32 __flags, ...);

extern "c" I32 close(I32 __fd);

extern "c" I64 read(I32 __fd, U0 *__buf, U64 __nbyte);

extern "c" I64 write(I32 __fd, U0 *__buf, U64 __nbyte);

extern "c" I64 lseek(I32 __fd, I64 __offset, I32 __whence);

extern "c" I32 chdir(U8 *path);

extern "c" I8 *getcwd(U8 *buf, U64 size);

extern "c" I8 *getenv(U8 *buf);

extern "c" I32 isatty(I32 fd);

class iovec
{
  U0 *iov_base;
  U64 iov_len;
};
