module str;

/* The size StrPrint and MPrintQ start a new buffer at (it grows). */
const i64 STR_LEN = 256;

/* StrPrint's internal format flags; MPrintQ's `flags` takes PRTF_SLASH,
 * which doubles each '%'. */
const i64 PRTF_PAD_ZERO = 0x001;

const i64 PRTF_LEFT_JUSTIFY = 0x002;

const i64 PRTF_TRUNCATE = 0x004;

const i64 PRTF_SLASH = 0x020;

const i64 PRTF_DECIMAL = 0x100;

/* Compare at most len bytes like C's strncmp: < 0, 0 or > 0, bytes compared
 * as unsigned, stopping at a NUL. null sorts before every string (two NULLs
 * are equal), whatever len is. */
pub _extern _STRNCMP i64 cmpLen(u8 *s1, u8 *s2, i64 len);

/* StrNCmp ignoring ASCII case, like C's strncasecmp. */
pub _extern _STRNICMP i64 cmpLenNoCase(u8 *s1, u8 *s2, i64 len);

/* Copy src and its NUL into dst, which must be big enough. A null src
 * copies "" and a null dst does nothing. */
pub _extern _STRCPY void copy(u8 *dst, u8 *src);

/* Compare like C's strcmp: < 0, 0 or > 0, bytes compared as unsigned.
 * null sorts before every string; two NULLs are equal. */
pub _extern _STRCMP i64 cmp(u8 *s1, u8 *s2);

#ifdef IS_X86_64
/* ch in lower case: only 'A'-'Z' change (ASCII). */
pub _extern _TOLOWER u8 toLower(u8 ch);
/* ch in upper case: only 'a'-'z' change (ASCII). */
pub _extern _TOUPPER u8 toUpper(u8 ch);
/* true for ' ', '\t', '\n', '\v', '\f' and '\r'. */
pub _extern _ISSPACE bool isSpace(u8 ch);
/* A 64-bit hash of str's bytes: h = h * 31 + c from 0, then a final mix
 * (murmur3's), so similar keys spread over the low bits; "" hashes to 0.
 * The same value on every target. */
pub _extern _STRHASH u64 hash(u8 *str);
#endif

#ifdef IS_ARM_64
pub u8 ToLower(u8 ch);
pub u8 ToUpper(u8 ch);
pub bool IsSpace(u8 ch);
pub u64 StrHash(u8 *str);
#endif
