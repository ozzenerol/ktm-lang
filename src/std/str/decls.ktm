module str;

/* Compare at most len bytes like C's strncmp: < 0, 0 or > 0, bytes compared
 * as unsigned, stopping at a NUL. NULL sorts before every string (two NULLs
 * are equal), whatever len is. */
public _extern _STRNCMP I64 StrNCmp(U8 *s1, U8 *s2, I64 len);

/* StrNCmp ignoring ASCII case, like C's strncasecmp. */
public _extern _STRNICMP I64 StrNICmp(U8 *s1, U8 *s2, I64 len);

/* Copy src and its NUL into dst, which must be big enough. A NULL src
 * copies "" and a NULL dst does nothing. */
public _extern _STRCPY U0 StrCpy(U8 *dst, U8 *src);

/* Compare like C's strcmp: < 0, 0 or > 0, bytes compared as unsigned.
 * NULL sorts before every string; two NULLs are equal. */
public _extern _STRCMP I64 StrCmp(U8 *s1, U8 *s2);

#ifdef IS_X86_64
/* ch in lower case: only 'A'-'Z' change (ASCII). */
public _extern _TOLOWER U8 ToLower(U8 ch);
/* ch in upper case: only 'a'-'z' change (ASCII). */
public _extern _TOUPPER U8 ToUpper(U8 ch);
/* TRUE for ' ', '\t', '\n', '\v', '\f' and '\r'. */
public _extern _ISSPACE Bool IsSpace(U8 ch);
/* A 64-bit hash of str's bytes: h = h * 31 + c from 0, then a final mix
 * (murmur3's), so similar keys spread over the low bits; "" hashes to 0.
 * The same value on every target. */
public _extern _STRHASH U64 StrHash(U8 *str);
#endif

#ifdef IS_ARM_64
public U8 ToLower(U8 ch);
public U8 ToUpper(U8 ch);
public Bool IsSpace(U8 ch);
public U64 StrHash(U8 *str);
#endif
