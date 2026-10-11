module libc;

pub extern "c" void *mmap(void *addr, u64 len, i32 prot, i32 flags, i32 fd, i64 offset);

pub extern "c" i32 munmap(void *addr, u64 len);

pub extern "c" i32 mprotect(void *addr, u64 len, i32 prot);

pub extern "c" i32 getpagesize();
