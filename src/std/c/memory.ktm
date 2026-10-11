module c;

public extern "c" U0 *memchr(U0 *__s, I32 __c, U64 __n);

public extern "c" I32 memcmp(U0 *__s1, U0 *__s2, U64 __n);

public extern "c" U0 *memmove(U0 *__dst, U0 *__src, U64 __len);

public extern "c" U0 free(U0 *ptr);

public extern "c" U0 *malloc(U64 size);

public extern "c" U0 *calloc(U64 count, U64 size);

public extern "c" U0 *realloc(U0 *ptr, U64 size);

public extern "c" U0 *memset(U0 *__dst, I32 __c, U64 __n);

public extern "c" U0 *memcpy(U0 *__dst, U0 *__src, U64 __n);
