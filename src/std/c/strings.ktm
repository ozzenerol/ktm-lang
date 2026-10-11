module c;

extern "c" F64 strtod(U8 *nptr, U8 **endptr);

extern "c" I64 strtoll(U8 *nptr, U8 **endptr, I64 base=10);

extern "c" U64 strtoul(U8 *nptr, U8 **endptr, I64 base=10);

extern "c" I64 snprintf(I8 *nptr, U64 len, I8 *fmt,...);

extern "c" I64 printf(I8 *fmt,...);

extern "c" I64 strnlen(U8 *s, I64 size);

extern "c" U8 *strcat(U8 *__s1, U8 *__s2);

extern "c" U8 *strchr(U8 *__s, I32 __c);

extern "c" I32 strcmp(U8 *__s1, U8 *__s2);

extern "c" I32 strcoll(U8 *__s1, U8 *__s2);

extern "c" U8 *strcpy(U8 *__dst, U8 *__src);

extern "c" U64 strcspn(U8 *__s, U8 *__u8set);

extern "c" U8 *strerror(I32 __errnum);

extern "c" U8 *strncat(U8 *__s1, U8 *__s2, U64 __n);

extern "c" I32 strncmp(U8 *__s1, U8 *__s2, U64 __n);

extern "c" U8 *strncpy(U8 *__dst, U8 *__src, U64 __n);

extern "c" U8 *strpbrk(U8 *__s, U8 *__u8set);

extern "c" U8 *strrchr(U8 *__s, I32 __c);

extern "c" U64 strspn(U8 *__s, U8 *__u8set);

extern "c" U8 *strstr(U8 *__big, U8 *__little);

extern "c" U8 *strtok(U8 *__str, U8 *__sep);

extern "c" U64 strxfrm(U8 *__s1, U8 *__s2, U64 __n);

public extern "c" U64 strlen(U8 *s);

public extern "c" I32 strncasecmp(U8 *s1, U8 *s2, U64 n);

public extern "c" I64 atol(U8 *str);
