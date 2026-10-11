module libc;

pub extern "c" void *calloc(u64 count, u64 size);

pub extern "c" void *realloc(void *ptr, u64 size);

pub extern "c" void *memset(void *__dst, i32 __c, u64 __n);

pub extern "c" void *memcpy(void *__dst, void *__src, u64 __n);
