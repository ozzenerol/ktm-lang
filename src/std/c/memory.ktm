module c;

public extern "c" U0 *calloc(U64 count, U64 size);

public extern "c" U0 *realloc(U0 *ptr, U64 size);

public extern "c" U0 *memset(U0 *__dst, I32 __c, U64 __n);

public extern "c" U0 *memcpy(U0 *__dst, U0 *__src, U64 __n);
