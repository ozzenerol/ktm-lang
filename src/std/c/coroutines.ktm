module c;

extern "c" U0 *mmap(U0 *addr, U64 len, I32 prot, I32 flags, I32 fd, I64 offset);

extern "c" I32 munmap(U0 *addr, U64 len);

extern "c" I32 mprotect(U0 *addr, U64 len, I32 prot);

extern "c" I32 getpagesize();
