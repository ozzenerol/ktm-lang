#include "./math.ktm"
#include "./defs.ktmh"
#include "./date.ktm"
#include "./memory.ktm"
#include "./io.ktm"

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

public U8 *StrPrint(U8 *dst,U8 *fmt,...);
public U8 *StrPrintJoin(U8 *_dst, U8 *fmt,I64 argc,I64 *argv);
public U8 *StrNPrint(U8 *dst, I64 size, U8 *fmt, ...);
public U8 *MPrintDate(CDate cdt);
public U8 *MPrintTime(CDate cdt);
public U8 *MPrintTimestampNow();
public I64 StrOcc(U8 *src, I64 ch);
public Bool WildMatch(U8 *test_str, U8 *wild_str);

#define STR_LEN 256

#define PRTF_PAD_ZERO        0x001
#define PRTF_LEFT_JUSTIFY	 0x002
#define PRTF_TRUNCATE		 0x004
#define PRTF_SLASH		     0x020
#define PRTF_DECIMAL		 0x100
#define PRTF_PLUS            0x1000 /* `+`: a sign on positive numbers too */
#define PRTF_SPACE           0x2000 /* ` `: a space where `+` would go */
#define PRTF_ALT             0x4000 /* `#`: 0x/0X before hex, a leading 0 in octal */

#define CH_SHIFT_SPACE 0x1F
#define CH_SPACE	   0x20

#define TIMESTAMP_FMT "%d-%02d-%02d %02d:%02d:%02d"

public extern "c" U64 strlen(U8 *s);
public extern "c" I32 strncmp(U8 *s1, U8 *s2, U64 n);
public extern "c" I32 strncasecmp(U8 *s1, U8 *s2, U64 n);
public extern "c" I32 strcmp(U8 *s1, U8 *s2);
public extern "c" U8 *strcpy(U8 *dst, U8 *src);
public extern "c" I64 atol(U8 *str);

#ifdef IS_X86_64
asm {
// All three return like C: <0 if s1 < s2, 0 if equal, >0 if s1 > s2,
// comparing bytes as unsigned and stopping at the first NUL.
// RDI=s1, RSI=s2, RDX=N
// 'A'=65, 'Z'=90; letters are lowercased like strncasecmp
_STRNICMP::
    PUSHQ  RBP
    MOVQ   RBP, RSP
    CALL   _STRNULLCMP     // NULL sorts first: RAX = result, RCX = 1 if done
    TEST   RCX, RCX
    JNZ    @@2
    MOVQ   RCX, RDX
    XORQ   RAX, RAX
@@3:
    TEST   RCX, RCX
    JZ     @@2
    DECQ   RCX
    MOVZBQ RAX, [RDI]
    MOVZBQ RDX, [RSI]
    INCQ   RDI
    INCQ   RSI
    CMP    AL, 65
    JB     @@22
    CMP    AL, 90
    JA     @@22
    ADD    AL, 32
@@22:
    CMP    DL, 65
    JB     @@23
    CMP    DL, 90
    JA     @@23
    ADD    DL, 32
@@23:
    SUBQ   RAX, RDX
    JNZ    @@2
    TEST   RDX, RDX
    JNZ    @@3
@@2:
    LEAVE
    RET

// RDI=s1, RSI=s2, RDX=N
_STRNCMP::
    PUSHQ   RBP
    MOVQ    RBP, RSP
    CALL    _STRNULLCMP
    TEST    RCX, RCX
    JNZ     @@2
    MOVQ    RCX, RDX
    XORQ    RAX, RAX
@@3:
    TEST    RCX, RCX
    JZ      @@2
    DECQ    RCX
    MOVZBQ  RAX, [RDI]
    MOVZBQ  RDX, [RSI]
    INCQ    RDI
    INCQ    RSI
    SUBQ    RAX, RDX
    JNZ     @@2
    TEST    RDX, RDX
    JNZ     @@3
@@2:
    LEAVE
    RET

// RDI=s1, RSI=s2. NULL sorts before every string: sets RAX to
// -1/0/1 and RCX to 1 when either is NULL, else RCX = 0. Keeps RDX.
_STRNULLCMP::
    XORQ   RAX, RAX
    MOVQ   RCX, 1
    TEST   RDI, RDI
    JZ     @@1
    TEST   RSI, RSI
    JZ     @@2
    XORQ   RCX, RCX
    RET
@@1:
    TEST   RSI, RSI
    JZ     @@3
    MOVQ   RAX, -1
    RET
@@2:
    MOVQ   RAX, 1
@@3:
    RET

// RDI=s1, RSI=s2. LODSB reads s2 into AL, SCASB compares it with s1.
_STRCMP::
    PUSH  RBP
    MOV   RBP,RSP
    CALL  _STRNULLCMP
    TEST  RCX, RCX
    JNZ   @@3
@@2:
    LODSB
    SCASB
    JNE    @@1
    TEST   AL, AL
    JNZ    @@2
    XOR    RAX, RAX
    JMP    @@3
@@1:
    SUB     RDI, 1
    MOVZX   RDX, AL
    MOVZBQ  RAX, [RDI]
    SUB     RAX, RDX
@@3:
    LEAVE
    RET

// @TempleOS - only slight tweaks
_STRCPY:: 
    PUSH  RBP
    MOVQ  RBP,RSP
    TEST  RDI,RDI
    JZ    @@15
    TEST  RSI, RSI
    JNZ   @@05
    XOR   RAX,RAX
    JMP   @@10
@@05:
    LODSB
@@10:
    STOSB
    TEST  AL,AL
    JNZ	  @@05
@@15:
    LEAVE
    RET

// RDI= char to convert
_TOUPPER::
    PUSH  RBP
    MOVQ  RBP,RSP
    MOVQ  RAX,RDI
    CMP   AL, 'a'
    JB    @@1
    CMP   AL, 'z'
    JA    @@1
    SUB   AL, 32
@@1:
    LEAVE
    RET

// RDI= char to convert
_TOLOWER::
    PUSH  RBP
    MOVQ  RBP,RSP
    MOVQ  RAX,RDI
    CMP   AL, 'A'
    JB    @@1
    CMP   AL, 'Z'
    JA    @@1
    ADD   AL, 32
@@1:
    LEAVE
    RET

/* RDI= U8 character */
_ISSPACE::
    MOVQ    RAX, RDI
    CMP     AL, ' '
    JE      @@5
    CMP     AL, '\t'
    JE      @@5
    CMP     AL, '\n'
    JE      @@5
    CMP     AL, '\v'
    JE      @@5
    CMP     AL, '\f'
    JE      @@5
    CMP     AL, '\r'
    JE      @@5
    JMP     @@10
@@5:
    MOVQ    RAX, 1
    JMP     @@15
@@10:
    MOVQ    RAX, 0
@@15:
    RET

/* RDI: U8 * string
 * RET U64 hashed string: h = h*31 + c over the bytes (0 for ""), then
 * the murmur3 64-bit finaliser so similar keys spread over the bits a
 * hash table masks with */
_STRHASH::
    XOR    RAX, RAX
@@5:
    MOVZBL ECX, [RDI]
    TEST   ECX, ECX
    JE     @@10
    MOVQ   RDX, RAX
    SALQ   RDX, 5
    SUB    RDX, RAX
    ADD    RDX, RCX
    MOVQ   RAX, RDX
    ADD    RDI, 1
    JMP    @@5
@@10:
    MOVQ   RDX, RAX
    SHR    RDX, 33
    XOR    RAX, RDX
    MOVABS RDX, 0xFF51AFD7ED558CCD
    IMUL   RAX, RDX
    MOVQ   RDX, RAX
    SHR    RDX, 33
    XOR    RAX, RDX
    MOVABS RDX, 0xC4CEB9FE1A85EC53
    IMUL   RAX, RDX
    MOVQ   RDX, RAX
    SHR    RDX, 33
    XOR    RAX, RDX
    RET
}

public _extern _TOLOWER U8 ToLower(U8 ch);
public _extern _TOUPPER U8 ToUpper(U8 ch);
public _extern _ISSPACE Bool IsSpace(U8 ch);
public _extern _STRHASH U64 StrHash(U8 *str);
#endif

#ifdef __aarch64__
asm {
// strncmp(x0=str1, x1=str2, x2=len); NULL sorts first
_STRNCMP::
    CBZ     X0, @@10
    CBZ     X1, @@11
    CBZ     X2, @@2
@@1:
    LDRB    W4, [X0], 1
    LDRB    W5, [X1], 1
    SUBS    W6, W4, W5
    B.NE    @@3
    CBZ     W4, @@2
    SUBS    X2, X2, 1
    B.NE    @@1
@@2:
    MOV     X0, 0
    RET
@@3:
    SXTW    X0, W6
    RET
@@10:
    MOV     X0, 0
    CBZ     X1, @@12
    SUB     X0, X0, 1
@@12:
    RET
@@11:
    MOV     X0, 1
    RET

// strnicmp(str1, str2, len) - case-insensitive (ASCII)
// X0 = str1, X1 = str2, X2 = len; NULL sorts first
_STRNICMP::
    CBZ     X0, @@10
    CBZ     X1, @@11
    CBZ     X2, @@2
@@1:
    LDRB    W4, [X0], 1
    LDRB    W5, [X1], 1
    // Lowercase W4 if it's in 'A'..'Z'
    SUB     W7, W4, 0x41
    CMP     W7, 25
    ADD     W8, W4, 0x20
    CSEL    W4, W8, W4, LS
    // Lowercase W5 the same way
    SUB     W7, W5, 0x41
    CMP     W7, 25
    ADD     W8, W5, 0x20
    CSEL    W5, W8, W5, LS

    SUBS    W6, W4, W5
    B.NE    @@3
    CBZ     W4, @@2
    SUBS    X2, X2, 1
    B.NE    @@1
@@2:
    MOV     X0, 0
    RET
@@3:
    SXTW    X0, W6
    RET
@@10:
    MOV     X0, 0
    CBZ     X1, @@12
    SUB     X0, X0, 1
@@12:
    RET
@@11:
    MOV     X0, 1
    RET

// NULL sorts first
_STRCMP::
    CBZ     X0, @@10
    CBZ     X1, @@11
@@1:
    LDRB    W4, [X0], 1
    LDRB    W5, [X1], 1
    SUBS    W6, W4, W5
    B.NE    @@2
    CBNZ    W4, @@1
    MOV     X0, 0
    RET
@@2:
    SXTW    X0, W6
    RET
@@10:
    MOV     X0, 0
    CBZ     X1, @@12
    SUB     X0, X0, 1
@@12:
    RET
@@11:
    MOV     X0, 1
    RET

// Like x86_64: a NULL dst does nothing, a NULL src copies ""
_STRCPY::
    CBZ     X0, @@3
    MOV     X2, X0
    CBNZ    X1, @@1
    MOV     W3, 0
    STRB    W3, [X0]
    MOV     X0, X2
    RET
@@1:
    LDRB    W3, [X1], 1
    STRB    W3, [X0], 1
    CBNZ    W3, @@1
    MOV     X0, X2
@@3:
    RET
}

public U8 ToLower(U8 ch)
{
  if (ch >= 'A' && ch <= 'Z') return ch + 32;
  return ch;
}

public U8 ToUpper(U8 ch)
{
  if (ch >= 'a' && ch <= 'z') return ch - 32;
  return ch;
}

public Bool IsSpace(U8 ch)
{
  return ch == ' ' || ch == '\t' || ch == '\n' ||
         ch == '\v' || ch == '\f' || ch == '\r';
}

public U64 StrHash(U8 *str)
{// h = h*31 + c over the bytes (0 for ""), then the murmur3 finaliser;
 // must match _STRHASH on x86_64
  U64 h = 0;
  for (I64 i = 0; str[i]; ++i) {
    h = (h << 5) - h + str[i];
  }
  h ^= h >> 33;
  h *= 0xFF51AFD7ED558CCD;
  h ^= h >> 33;
  h *= 0xC4CEB9FE1A85EC53;
  h ^= h >> 33;
  return h;
}
#endif

public I64 Atoi(U8 *str)
{// The integer at the start of str (like atol); 0 for NULL
  if (!str) return 0;
  return atol(str);
}

/* The length of `buf` before its '\0', 0 for NULL. libc's strlen, which
 * reads a word at a time: about 5x the old byte loop. */
public U64 StrLen(U8 *buf)
{
  if (!buf) return 0;
  return strlen(buf);
}
public _extern _STRNCMP I64 StrNCmp(U8 *s1, U8 *s2, I64 len);
public _extern _STRNICMP I64 StrNICmp(U8 *s1, U8 *s2, I64 len);
public _extern _STRCMP I64 StrCmp(U8 *s1, U8 *s2);
public _extern _STRCPY U0 StrCpy(U8 *dst, U8 *src);

public U8 *StrNCpy(U8 *buf, I64 len)
{// A new string with at most the first `len` bytes of buf, stopping at
 // its NUL (like C's strndup); always NUL terminated. A negative len is
 // 0 and a NULL buf gives "".
  I64 n = 0;
  if (len < 0) len = 0;
  if (buf) {
    while (n < len && buf[n]) n++;
  }
  U8 *str = MAlloc(n+1);
  if (n) MemCpy(str,buf,n);
  str[n] = '\0';
  return str;
}

U8 *StrNew(U8 *buf)
{ // @TempleOS
  U8 *res;
  I64 size;
  if (buf) {
    size = StrLen(buf)+1;
    res = MAlloc(size);
    MemCpy(res,buf,size);
    res[size-1] = '\0';
  } else {
    res=MAlloc(1);
    *res=0;
  }
  return res;
}

public U8 *FileExtDot(U8 *src)
{//@TempleOS - Find dot char in name.
  I64 ch;
  if (!src) return NULL;
  while (ch=*src++)
    if (ch=='.' && *src!='/' && *src!='.')
      return src-1;
  return NULL;
}

public U8 *FileExtRem(U8 *src,U8 *dst=NULL)
{//@TempleOS - Remove filename extension from str.
  U8 *ptr;
  if (!src) {
    if (dst) *dst=0;
    return dst;
  }
  if (ptr=FileExtDot(src)) {
    if (dst)
      StrCpy(dst,ptr+1);
    *ptr=0;
  } else if (dst)
    *dst=0;
  return dst;
}

public Bool IsDotZ(U8 *filename)
{//@TempleOS - Does name end in .Z?
  if (!filename) return FALSE;
  I64 i=StrLen(filename);
  if (StrOcc(filename,'.')>1 && filename[i-1]=='Z' && filename[i-2]=='.')
    return TRUE;
  else
    return FALSE;
}

public Bool IsDotC(U8 *filename)
{//@TempleOS - Does name end in .C?
  if (!filename) return FALSE;
  I64 i=StrLen(filename);
  if (StrOcc(filename,'.')>1 && filename[i-1]=='C' && filename[i-2]=='.')
    return TRUE;
  else
    return FALSE;
}

public U0 SPutChar(U8 **_dst,U8 ch,U8 **_buf)
{ // @TempleOS
  I64 i;
  U8 *dst=*_dst,*buf;
  if (_buf) {
    buf=*_buf;
    i=dst-buf;
    if (i+1>=MSize(buf)) { /* room for ch and the '\0' after it */
      /* Double, with room for ch and the '\0' even when i is 0.
       * (TempleOS wrote i<<1+1, which ktm parses as i<<2.) */
      buf=MAlloc((i+2)*2);
      MemCpy(buf,*_buf,i);
      Free(*_buf);
      dst=buf+i;
      *_buf=buf;
    }
  }
  *dst++=ch;
  *dst='\0';
  *_dst=dst;
}

public U0 OutStr(U8 *instr,U8 **_buf,U8 **_dst,I64 len,I64 flags,
                 I64 prec=-1)
{// @TempleOS
 // instr -> in string
 // _buf  -> the underlying buffer 
 // _dst  -> where the string will go
 // len   -> the minimum width, padded with spaces
 // prec  -> if >= 0 at most this many chars of instr are written, as C's %.Ns
  I64 i,j,size,dst_len;
  U8 *buf,*dst;
  if (!instr)
    i=0;
  else
    i=StrLen(instr);
  dst = *_dst;
  /* No _buf: the caller's buffer is not ours to MSize/ReAlloc */
  if (_buf) {
    buf = *_buf;
    dst_len = dst-buf;
    size = MSize(buf);

    if (i >= (size - dst_len)) { /* double, as CatPrint does */
      buf = ReAlloc(buf,((size*2)+i)*sizeof(U8));
      *_buf = buf;
      *_dst = buf + dst_len;
      dst = *_dst;
    }
  }
  if (flags&PRTF_TRUNCATE && i>len)
    i=len;
  if (prec>=0 && i>prec)
    i=prec;
  /* All writes go through _dst: SPutChar may move the buffer */
  if (!(flags&PRTF_LEFT_JUSTIFY))
    for (j=i;j<len;j++)
      SPutChar(_dst,CH_SPACE,_buf);
  for (j=0;j<i;j++)
    SPutChar(_dst,*instr++,_buf);
  if (flags&PRTF_LEFT_JUSTIFY)
    for (j=i;j<len;j++)
      SPutChar(_dst,CH_SPACE,_buf);
}

public I64 StrOcc(U8 *src, I64 ch)
{// @TempleOS - Count occurrences of a char.
  I64 i=0;
  if (!src) return 0;
  while (*src)
    if (*src++==ch)
      i++;
  return i;
}

public U8 *StrFirstOcc(U8 *src,U8 *marker)
{//@TempleOS - Point to 1st occurrence of marker set in str.
  I64 ch;
  if (!src || !marker) return NULL;
  while ((ch=*src++) && !StrOcc(marker,ch));
  if (ch)
    return src-1;
  else
    return NULL;
}

U8 *StrFirstRem(U8 *src,U8 *marker,U8 *dst=NULL)
{//@TempleOS - Remove first str segment and place in dst buf or NULL.
  I64 ch;
  U8 *ptr=src,*res=dst;
  if (!src || !marker) {
    if (dst) *dst=0;
    return res;
  }
  if (dst) {
    while ((ch=*ptr++) && !StrOcc(marker,ch))
      *dst++=ch;
    *dst=0;
  } else
    while ((ch=*ptr++) && !StrOcc(marker,ch));
  if (ch)
    StrCpy(src,ptr);
  else
    *src=0;
  return res;
}

public U8 *StrLastOcc(U8 *src,U8 *marker)
{// @TempleOS - Point to last occurrence of market set in str.
  I64 ch;
  U8 *res=NULL;
  if (!src || !marker) return NULL;
  while (ch=*src++)
    if (StrOcc(marker,ch))
      res=src-1;
  return res;
}

public U8 *StrLastRem(U8 *src,U8 *marker,U8 *dst=NULL)
{// @TempleOS - Remove last str segment and place in dst buf or NULL.
  U8 *ptr;
  if (!src || !marker) {
    if (dst) *dst=0;
    return dst;
  }
  if ((ptr=StrLastOcc(src,marker)) != NULL) {
    if (dst)
      StrCpy(dst,ptr+1);
    *ptr=0;
  } else {
    if (dst)
      StrCpy(dst,src);
    *src=0;
  }
  return dst;
}

public U8 *StrTrimStart(U8 *str, I64 str_len=-1, I64 *_new_len=NULL)
{ // Mutates the string, removes whitespace at the start
  // of the string, MSize calls are still safe. Only the first str_len
  // bytes are looked at (the whole string if str_len < 0); when something
  // is removed the result is NUL terminated at its new length. NULL
  // returns NULL.
  I64 new_len,len,j;

  if (!str) {
    if (_new_len) *_new_len = 0;
    return NULL;
  }
  if (str_len < 0) {
    len = StrLen(str);
  } else {
    len = str_len;
  }
  j = 0;

  while (j < len && IsSpace(str[j])) {
    ++j;
  }

  new_len = len-j;
  if (_new_len) {
    *_new_len = new_len;
  }
  if (j == 0) {
    return str;
  }
  memmove(str,str+j,new_len);
  str[new_len] = '\0';
  return str;
}

public U8 *StrTrimEnd(U8 *str, I64 str_len=-1, I64 *_new_len=NULL)
{ // Mutates the string, removes whitespace at the end
  // of the string, MSize calls are still safe. Only the first str_len
  // bytes are looked at (the whole string if str_len < 0). NULL returns
  // NULL.
  I64 new_len,len,i;

  if (!str) {
    if (_new_len) *_new_len = 0;
    return NULL;
  }
  if (str_len < 0) {
    len = StrLen(str);
  } else {
    len = str_len;
  }
  i = len-1;

  while (i >= 0 && IsSpace(str[i])) {
    --i;
  }
  i++;

  new_len = i;
  if (_new_len) {
    *_new_len = new_len;
  }
  if (new_len == len) {
    return str;
  }
  str[new_len] = '\0';
  return str;
}

public I64 StrCleanEnd(U8 *str, U8 *chars)
{ // Mutates the string, removes any of the characters in `chars` from
  // the end of the string and returns its new length. NULL returns 0.
  I64 len,j;

  if (!str) {
    return 0;
  }
  len = StrLen(str);

  if (chars) {
    while (len > 0) {
      j = 0;
      while (chars[j] && chars[j] != str[len-1]) {
        ++j;
      }
      if (!chars[j]) {
        break;
      }
      str[--len] = '\0';
    }
  }
  return len;
}

public U0 StrReverse(U8 *buf, I64 length)
{ // Reverse a string inplace
  I64 start = 0, end = length - 1;
  while (start < end) {
    U8 temp = buf[start];
    buf[start] = buf[end];
    buf[end] = temp;
    start++;
    end--;
  }
}

public I64 I64ToStr(U8 *buf, I64 num, Bool *_is_neg=NULL, I64 _len=5000)
{ // puts num in the buf, returns the length of the number
  I64 i = 0;
  U64 mag = num; /* unsigned: -I64_MIN doesn't fit in an I64 */

  if (num == 0) {
    buf[i++] = '0';
    buf[i] = '\0';
    return 1;
  }

  if (_is_neg) {
    *_is_neg = FALSE;
  }
  // Check for negative numbers
  if (num < 0) {
    if (_is_neg) {
      *_is_neg = TRUE;
    }
    mag = -mag; // absolute
  }

  // Process individual digits
  while (mag != 0 && _len != 0) {
    buf[i++] = mag % 10 + '0';
    mag = mag / 10;
    _len--;
  }

  buf[i] = '\0';
  //StrReverse(dst, i);
  return i;
}

public U64 U64ToStr(U8 *dst, U64 num, I64 len=5000)
{
  I64 i = 0;
  if (num == 0) {
    dst[i++] = '0';
    dst[i] = '\0';
    return 1;
  }

  while (num != 0 && len != 0) {
    dst[i++] = num%10 + '0';
    num = num / 10;
    len--;
  }

  dst[i] = '\0';
  //StrReverse(dst, i);
  return i;
}

I64 StrIntToHex(U8 *dst, I64 num)
{
  U8 *hex_str="0123456789ABCDEF";
  I64 i = (sizeof(U64) * 2) + 1, len = 2; /* "0x" + 16 digits */
  dst[i+1] = '\0';
  while (i >= 2) {
    len++;
    dst[i] = hex_str[num & 0xF];
    num >>= 4;
    i--;
  }
  dst[i] = 'x';
  dst[i-1] = '0';
  return len;
}

U8 *MPrintQ(U8 *ptr,I64 flags = 0)
{ // @TempleOS
  U8 **_buf,*buf,**_dst,*dst,buf2[8],*ptr2;
  I64 ch;
  buf=MAlloc(STR_LEN);
  _buf=&buf;
  dst=buf;
  _dst=&dst;
  if (ptr)
    while (ch=*ptr++) {
        switch (ch) {
          case '%':
            SPutChar(_dst,ch,_buf);
            if (flags&PRTF_SLASH)
              SPutChar(_dst,ch,_buf);
            break;
          case '\n':
            SPutChar(_dst,'\\',_buf);
            SPutChar(_dst,'n',_buf);
            break;
          case '\r':
            SPutChar(_dst,'\\',_buf);
            SPutChar(_dst,'r',_buf);
            break;
          case '\t':
            SPutChar(_dst,'\\',_buf);
            SPutChar(_dst,'t',_buf);
            break;
          case '\v':
            SPutChar(_dst,'\\',_buf);
            SPutChar(_dst,'v',_buf);
            break;
          case '\f':
            SPutChar(_dst,'\\',_buf);
            SPutChar(_dst,'f',_buf);
            break;
          case '"':
          case '\\':
            SPutChar(_dst,'\\',_buf);
            SPutChar(_dst,ch,_buf);
            break;
          default:
            if (ch>=CH_SHIFT_SPACE && ch!=0x7F)
              SPutChar(_dst,ch,_buf);
            else { /* `\x01`, which reads back as the same byte */
              StrPrint(buf2,"\\x%02X",ch);
              ptr2=buf2;
              while (*ptr2)
                SPutChar(_dst,*ptr2++,_buf);
            }
        }
    }
  SPutChar(_dst,0,_buf);
  return buf;
}

/* Digit buffer for an exact F64 decimal expansion: up to 309 integer digits
 * sit before F64_DIG_POINT, up to 1074 fraction digits from it. */
#define F64_DIG_POINT 320
#define F64_DIG_LEN   1408

static U0 F64DigRound(U8 *dig, I64 *_lo, I64 hi, I64 cut)
{ // Round the digits to dig[..cut) to nearest, ties to even, like C's printf
  I64 i;
  Bool up = FALSE;
  if (cut >= hi) return;
  if (dig[cut] > 5) {
    up = TRUE;
  } else if (dig[cut] == 5) {
    i = cut + 1;
    while (i < hi && !dig[i]) i++;
    up = i < hi || dig[cut-1] & 1;
  }
  for (i = cut; i < hi; ++i) {
    dig[i] = 0;
  }
  if (up) {
    i = cut - 1;
    while (dig[i] == 9) {
      dig[i--] = 0;
    }
    dig[i]++;
    if (i < *_lo) *_lo = i;
  }
}

static U8 *F64ToFmtStr(U64 bits, I64 prec, I64 conv)
{ // %f, %e or %g of the F64 with bit pattern `bits`, without the sign.
  // Works on the exact decimal value of the double, so it rounds like C.
  U8 *dig, *out, *o;
  I64 ex = (bits >> 52) & 0x7FF, lo, hi, cut, i, s, v, carry, fz, x;
  U64 mant = bits & 0xFFFFFFFFFFFFF;
  Bool strip = FALSE;

  if (ex == 0x7FF) {
    if (mant) return StrNew("nan");
    return StrNew("inf");
  }
  if (prec < 0) prec = 6;
  if (conv == 'g' && prec == 0) prec = 1;
  if (ex) {
    mant |= 0x10000000000000;
    ex -= 1075;
  } else {
    ex = -1074;
  }

  /* value = mant * 2^ex, as decimal digits in dig[lo..hi) */
  dig = CAlloc(F64_DIG_LEN);
  lo = hi = F64_DIG_POINT;
  while (mant) {
    dig[--lo] = mant % 10;
    mant /= 10;
  }
  while (ex > 0) { // multiply by 2^s
    s = ex;
    if (s > 28) s = 28;
    carry = 0;
    for (i = hi - 1; i >= lo; --i) {
      v = dig[i];
      v = (v << s) + carry;
      dig[i] = v % 10;
      carry = v / 10;
    }
    while (carry) {
      dig[--lo] = carry % 10;
      carry /= 10;
    }
    ex -= s;
  }
  while (ex < 0) { // divide by 2^s, every step adds s fraction digits
    s = -ex;
    if (s > 28) s = 28;
    carry = 0;
    for (i = lo; i < hi; ++i) {
      v = carry * 10 + dig[i];
      dig[i] = v >> s;
      carry = v & ((1 << s) - 1);
    }
    while (carry) {
      v = carry * 10;
      dig[hi++] = v >> s;
      carry = v & ((1 << s) - 1);
    }
    ex += s;
  }

  fz = lo;
  while (fz < hi && !dig[fz]) fz++;
  if (conv == 'f') {
    cut = F64_DIG_POINT + prec;
  } else if (conv == 'e') {
    cut = fz + prec + 1;
  } else {
    cut = fz + prec;
  }
  F64DigRound(dig, &lo, hi, cut);
  fz = lo;
  while (fz < hi && !dig[fz]) fz++;
  x = 0; // decimal exponent of the first significant digit
  if (fz < hi) x = F64_DIG_POINT - 1 - fz;

  if (conv == 'g') {
    strip = TRUE;
    if (x < prec && x >= -4) {
      conv = 'f';
      prec = prec - 1 - x;
    } else {
      conv = 'e';
      prec--;
    }
  }

  out = o = MAlloc(prec + F64_DIG_POINT + 16);
  if (conv == 'f') {
    i = lo;
    if (i > F64_DIG_POINT - 1) i = F64_DIG_POINT - 1;
    while (i < F64_DIG_POINT - 1 && !dig[i]) i++;
    while (i < F64_DIG_POINT) *o++ = dig[i++] + '0';
    cut = F64_DIG_POINT + prec;
  } else {
    *o++ = '0';
    if (fz < hi) o[-1] += dig[fz];
    i = fz + 1;
    cut = i + prec;
  }
  if (prec) {
    *o++ = '.';
    while (i < cut) {
      *o = '0';
      if (i < hi) *o += dig[i];
      o++;
      i++;
    }
    if (strip) {
      while (o[-1] == '0') o--;
      if (o[-1] == '.') o--;
    }
  }
  if (conv == 'e') {
    *o++ = 'e';
    if (x < 0) {
      *o++ = '-';
      x = -x;
    } else {
      *o++ = '+';
    }
    if (x >= 100) *o++ = x / 100 + '0';
    *o++ = x / 10 % 10 + '0';
    *o++ = x % 10 + '0';
  }
  *o = '\0';
  Free(dig);
  return out;
}

/* An integer conversion as C prints it: `conv` is d i u o x or X, `width`
 * the minimum width, `prec` the minimum number of digits (-1: none; 0
 * with a 0 value prints no digits). */
static U0 StrPrintInt(U8 **_ptr, U8 **grow, U64 val, I64 conv, I64 flags,
                      I64 width, I64 prec)
{
  U8 digits[72], prefix[4], *hex = "0123456789abcdef";
  I64 base = 10, ndig = 0, nzero = 0, npre = 0, total, i;
  Bool neg = FALSE;

  if (conv == 'd' || conv == 'i') {
    if (val(I64) < 0) {
      neg = TRUE;
      val = -val(I64); /* as a U64, also right for I64_MIN */
    }
  } else if (conv == 'o') {
    base = 8;
  } else if (conv == 'x' || conv == 'X') {
    base = 16;
    if (conv == 'X') hex = "0123456789ABCDEF";
  }

  if (val || prec != 0) {
    do {
      digits[ndig++] = hex[val % base];
      val /= base;
    } while (val);
  }

  if (neg)                    prefix[npre++] = '-';
  else if (conv == 'd' || conv == 'i') {
    if (flags & PRTF_PLUS)       prefix[npre++] = '+';
    else if (flags & PRTF_SPACE) prefix[npre++] = ' ';
  }
  if (flags & PRTF_ALT && base == 16 && ndig && !(ndig == 1 && digits[0] == '0')) {
    prefix[npre++] = '0';
    prefix[npre++] = conv;
  }

  if (prec > ndig) nzero = prec - ndig;
  /* `#o`: the first digit is a 0 */
  if (flags & PRTF_ALT && base == 8 && !nzero &&
      (!ndig || digits[ndig-1] != '0')) {
    nzero = 1;
  }
  /* `0` pads with zeros after the sign, unless `-` or a precision */
  if (flags & PRTF_PAD_ZERO && !(flags & PRTF_LEFT_JUSTIFY) && prec < 0 &&
      npre + nzero + ndig < width) {
    nzero = width - npre - ndig;
  }

  total = npre + nzero + ndig;
  if (!(flags & PRTF_LEFT_JUSTIFY))
    for (i = total; i < width; ++i) SPutChar(_ptr,CH_SPACE,grow);
  for (i = 0; i < npre; ++i)        SPutChar(_ptr,prefix[i],grow);
  for (i = 0; i < nzero; ++i)       SPutChar(_ptr,'0',grow);
  for (i = ndig - 1; i >= 0; --i)   SPutChar(_ptr,digits[i],grow);
  if (flags & PRTF_LEFT_JUSTIFY)
    for (i = total; i < width; ++i) SPutChar(_ptr,CH_SPACE,grow);
}

U8 *StrPrintJoin(U8 *_dst, U8 *fmt,I64 argc,I64 *argv)
{// @TempleOS - Not a 1:1 yet
  if (!fmt) return NULL;
  U64 uintarg, curlen = 0;
  F64 farg;
  I64 ch, sp = 0, len, flags, tlen,dec_len;
  U8 *ptr, *strarg, *tmp, *dst, *spec, buf[256];
  U8 **grow;
  CDate cdt;

  /* Growth (SPutChar's and OutStr's MSize/Free dance) is only legal on a
   * buffer WE MAlloc'd. A caller-provided dst may live on the stack or mid-array,
   * where MSize reads garbage and Free aborts - so, as in TempleOS, the
   * caller's buffer is trusted to be big enough and never grown. */
  if (_dst == NULL) {
    dst = MAlloc(STR_LEN);
    grow = &dst;
  } else {
    dst = _dst;
    grow = NULL;
  }

  ptr = dst;
  *ptr = '\0'; /* SPutChar terminates after each char: an empty result needs it too */
  while (ch = *fmt++) {
    if (ch == '%') {
      spec = fmt - 1; /* the '%', for an unsupported conversion */
      flags = 0;
      /* The flags, in any order, as in C */
      while (TRUE) {
        if (*fmt == '-')      flags |= PRTF_LEFT_JUSTIFY;
        else if (*fmt == '0') flags |= PRTF_PAD_ZERO;
        else if (*fmt == '+') flags |= PRTF_PLUS;
        else if (*fmt == ' ') flags |= PRTF_SPACE;
        else if (*fmt == '#') flags |= PRTF_ALT;
        else break;
        fmt++;
      }
      tlen = 0;
      while ('0'<=*fmt<='9') {
        tlen = tlen * 10 + *fmt++ -'0';
      }
      if (*fmt == '*') {
        fmt++;
        tlen = argv[sp++](U64);
        if (tlen < 0) { /* as in C, a negative '*' width left-justifies */
          flags|=PRTF_LEFT_JUSTIFY;
          tlen = -tlen;
        }
      }
      dec_len = -1;
      if (*fmt == '.') {
        fmt++;
        dec_len = 0;
        while ('0'<=*fmt<='9') {
          dec_len = dec_len * 10 + *fmt++ -'0';
        }
        if (*fmt == '*') {
          fmt++;
          dec_len=argv[sp++];
          if (dec_len < 0) { /* as in C, a negative precision is none */
            dec_len = -1;
          }
        }
        flags|=PRTF_DECIMAL;
      }
      /* C's length modifiers (l ll h hh z j t L q): every integer
       * argument is 64 bits, so they change nothing */
      while (*fmt == 'l' || *fmt == 'h' || *fmt == 'z' || *fmt == 'j' ||
             *fmt == 't' || *fmt == 'L' || *fmt == 'q') {
        fmt++;
      }

      switch (*fmt++) {
        case '%': {
          SPutChar(&ptr,'%',grow);
          break;
        }

        case 'Q': {
          strarg = argv[sp++](U8*);
          strarg = MPrintQ(strarg,flags);
          OutStr(strarg,grow,&ptr,tlen,flags);
          Free(strarg);
          break;
        }

        case 'T': {
          cdt = argv[sp++](CDate);
          strarg = MPrintTime(cdt);
          OutStr(strarg,grow,&ptr,tlen,flags);
          Free(strarg);
          break;
        }

        case 'D': {
          cdt = argv[sp++](CDate);
          strarg = MPrintDate(cdt);
          OutStr(strarg,grow,&ptr,tlen,flags);
          Free(strarg);
          break;
        }

        /* Presently there is only F64, %f/%e/%g as C prints them */
        case 'e':
        case 'g':
        case 'f': {
          uintarg = argv[sp++](U64); // the F64's bits
          strarg = F64ToFmtStr(uintarg,dec_len,*(fmt-1));
          len = StrLen(strarg);
          U8 sign = 0; /* '-', or '+' / ' ' with those flags */
          if (uintarg >> 63)           sign = '-';
          else if (flags & PRTF_PLUS)  sign = '+';
          else if (flags & PRTF_SPACE) sign = ' ';
          if (sign) {
            len++;
          }
          /* No zero padding for inf/nan, '-' wins over '0' */
          if (flags & PRTF_LEFT_JUSTIFY || !('0' <= *strarg <= '9')) {
            flags &= ~PRTF_PAD_ZERO;
          }
          if (!(flags & (PRTF_PAD_ZERO|PRTF_LEFT_JUSTIFY))) {
            for (I64 idx = len; idx < tlen; ++idx) {
              SPutChar(&ptr,CH_SPACE,grow);
            }
          }
          if (sign) {
            SPutChar(&ptr,sign,grow);
          }
          if (flags & PRTF_PAD_ZERO) {
            for (I64 idx = len; idx < tlen; ++idx) {
              SPutChar(&ptr,'0',grow);
            }
          }
          for (tmp = strarg; *tmp; ++tmp) {
            SPutChar(&ptr,*tmp,grow);
          }
          if (flags & PRTF_LEFT_JUSTIFY) {
            for (I64 idx = len; idx < tlen; ++idx) {
              SPutChar(&ptr,CH_SPACE,grow);
            }
          }
          Free(strarg);
          break;
        }

        case 's': {
          strarg = argv[sp++](U8 *);
          OutStr(strarg,grow,&ptr,tlen,flags&~PRTF_TRUNCATE,dec_len);
          break;
        }
        
        case 'F': { // a file
          tmp = argv[sp++](U8 *);
          strarg = FileRead(tmp,&len);
          OutStr(strarg,grow,&ptr,tlen,flags&~PRTF_TRUNCATE,dec_len);
          Free(strarg);
          break;
        }

        case 'c': {
          uintarg = argv[sp++](U64);
          for (I64 i = 0; i < 8; ++i) {
            buf[i] = uintarg >> ((i*8) & 0xFF);
          }
          buf[8] = '\0';
          OutStr(buf,grow,&ptr,tlen,flags);
          break;
        }

        case 'C': {
          uintarg = argv[sp++](U64);
          for (I64 i = 0; i < 8; ++i) {
            buf[i] = ToUpper(uintarg >> ((i*8) & 0xFF));
          }
          buf[8] = '\0';
          OutStr(buf,grow,&ptr,tlen,flags);
          break;
        }

        case 'd':
        case 'i':
        case 'u':
        case 'o':
        case 'x':
        case 'X': {
          uintarg = argv[sp++](U64);
          StrPrintInt(&ptr,grow,uintarg,*(fmt-1),flags,tlen,dec_len);
          break;
        }

        case 'p': {
          uintarg = argv[sp++](U64);
          len = StrIntToHex(buf,uintarg);
          OutStr(buf,grow,&ptr,tlen,flags);
          break;
        }

        default: {
          /* *(fmt-1) is not a conversion StrPrint knows. Kept in the
           * output as written, as C prints one, with a diagnostic on
           * stderr. As in C a conversion ends at a letter: what it may
           * still hold (`%ld`, `%+d`) is skipped and its argument taken,
           * so the conversions after it get the right ones. A '%' at the
           * very end stops at the NUL instead of reading past it. */
          U8 bad = *(fmt-1);
          if (!bad) {
            fmt--;
            len = snprintf(buf,sizeof(buf),
                           "Invalid format: '%%' with no conversion at the end\n");
          } else {
            U8 *end = NULL;
            for (tmp = fmt-1; *tmp && StrOcc(" +#'0123456789.hlLqjzt",*tmp);
                 ++tmp) {
              if ('a' <= *tmp <= 'z' || 'A' <= *tmp <= 'Z') end = tmp;
            }
            if ('a' <= *tmp <= 'z' || 'A' <= *tmp <= 'Z') end = tmp;
            if (end) {
              sp++;
              fmt = end+1;
            }
            len = snprintf(buf,sizeof(buf),"Invalid format char: '%c'\n",bad);
          }
          write(STDERR,buf,len);
          for (tmp = spec; tmp < fmt; ++tmp) {
            SPutChar(&ptr,*tmp,grow);
          }
          break;
        }
      }
    } else {
      SPutChar(&ptr,ch,grow);
    }
  }
  return dst;
}

public U8 *StrMergeJoin(I64 argc, U64 *argv)
{
  U8 *buf = MAlloc(256), *dst = buf;
  for (I64 i = 0; i < argc; ++i) {
    OutStr(argv[i](U8*),&buf,&dst,0,0);
  }
  return buf;
}

public U8 *StrMerge(...)
{
  return StrMergeJoin(argc,argv);
}

U8 *StrPrint(U8 *dst=NULL,U8 *fmt,...)
{// @TempleOS
  return StrPrintJoin(dst,fmt,argc,argv);
}

/* Format into `dst`, which has room for `size` bytes, like C's snprintf:
 * at most size-1 characters are written, always followed by a '\0' (size
 * <= 0 writes nothing), and longer output is cut short. Returns dst.
 * StrPrint(dst, ...) has no bound, so use this for a fixed buffer. */
U8 *StrNPrint(U8 *dst, I64 size, U8 *fmt, ...)
{
  if (!dst || size <= 0) return dst;
  U8 *full = StrPrintJoin(NULL,fmt,argc,argv);
  I64 len = 0;
  if (full) {
    len = StrLen(full);
    if (len > size - 1) len = size - 1;
    MemCpy(dst,full,len);
    Free(full);
  }
  dst[len] = '\0';
  return dst;
}

U8 *MStrPrint(U8 *fmt,...)
{// @TempleOS - MAlloc StrPrint.
  U8 *res,*buf=StrPrintJoin(NULL,fmt,argc,argv);
  res=StrNew(buf);
  Free(buf);
  return res;
}

U0 Print(U8 *fmt,...)
{// @TempleOS - StrPrint to stdout, through C's stdio so it stays in
 // order with printf and the print statements that use it
  U8 *buf=StrPrintJoin(NULL,fmt,argc,argv);
  printf("%s",buf);
  Free(buf);
}

U8 *MPrintTime(CDate cdt)
{// @TempleOS
  CDateStruct ds;
  Date2Struct(&ds,cdt);
  return MStrPrint("%02d:%02d:%02d",ds.hour,ds.min,ds.sec);
}

U8 *MPrintDate(CDate cdt)
{// @TempleOS
  CDateStruct ds;
  Date2Struct(&ds,cdt);
  return MStrPrint("%02d/%02d/%04d",ds.mon,ds.day_of_mon,ds.year);
}


U8 *MPrintTimestampNow()
{// Get a timestamp
  I64 seconds = NowMilliseconds() / 1000; 
  U8 *timestamp = MAlloc(sizeof(U8)*32);
  auto ptm = localtime(&seconds);
  auto len = snprintf(timestamp,sizeof(U8)*32,TIMESTAMP_FMT,
          ptm->tm_year+1900,ptm->tm_mon+1,ptm->tm_mday,
          ptm->tm_hour,ptm->tm_min,ptm->tm_sec);
  timestamp[len] = '\0';
  return timestamp;
}

/* Append formatted text to the heap string `_dst` (MAlloc, StrNew,
 * StrPrint(NULL, ...) or a previous CatPrint; NULL starts a new one),
 * growing it when it is full, and return it. The block may move and the
 * old pointer is then freed, so always write `s = CatPrint(s, ...)` and
 * never pass a string literal or a stack buffer. Each call measures the
 * whole of `_dst` to find its end, so n appends cost O(n^2) for a long
 * string: in a loop keep the length yourself with CatLenPrint, or
 * collect the pieces and StrPrint them once. */
U8 *CatPrint(U8 *_dst,U8 *fmt,...)
{// @TempleOS
  U8 *s2 = StrPrintJoin(NULL,fmt,argc,argv);
  if (!s2) return _dst;
  if (!_dst) return s2;
  I64 len = StrLen(_dst);
  I64 s2len = StrLen(s2);
  I64 size = MSize(_dst);
  if (len + s2len >= size) {
    _dst = ReAlloc(_dst,(size*2)+s2len+1);
  }
  MemCpy(_dst+len,s2,s2len+1);
  Free(s2);
  return _dst;
}

/* CatPrint for a string whose length the caller keeps in `*_len`, so no
 * StrLen per call. `_dst` may be NULL (with *_len 0) to start one. */
U8 *CatLenPrint(U8 *_dst, I64 *_len, U8 *fmt, ...)
{
  I64 len = *_len;
  U8 *s2 = StrPrintJoin(NULL,fmt,argc,argv);
  if (!s2) return _dst;
  I64 s2len = StrLen(s2);
  I64 size = MSize(_dst);
  if (len + s2len >= size) { /* +1 for the '\0' (size is 0 for NULL) */
    _dst = ReAlloc(_dst,(size*2)+s2len+1);
  }
  MemCpy(_dst+len,s2,s2len);
  len+=s2len;
  _dst[len] = '\0';
  *_len = len;
  Free(s2); /* the formatted text, now copied */
  return _dst;
}

static U8 *_str_date_days[7] = {
  "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"
};
static U8 *_str_date_months[12] = {
  "january", "february", "march", "april", "may", "june", "july",
  "august", "september", "october", "november", "december"
};

/* Read up to `max_digits` decimal digits (after any spaces) */
static Bool _StrDateNum(U8 **_s, I64 max_digits, I64 *_out)
{
  U8 *s = *_s;
  I64 v = 0, n = 0;
  while (*s == ' ' || *s == '\t') s++;
  while (n < max_digits && *s >= '0' && *s <= '9') {
    v = v * 10 + (*s - '0');
    s++;
    n++;
  }
  if (n == 0) return FALSE;
  *_s = s;
  *_out = v;
  return TRUE;
}

/* A day or month name: the full name or its first three letters, any
 * case. Returns the index or -1. */
static I64 _StrDateName(U8 **_s, U8 **names, I64 count)
{
  U8 *s = *_s;
  for (I64 i = 0; i < count; ++i) {
    I64 len = StrLen(names[i]);
    if (StrNICmp(s, names[i], len) == 0) {
      *_s = s + len;
      return i;
    }
  }
  for (I64 i = 0; i < count; ++i) {
    if (StrNICmp(s, names[i], 3) == 0) {
      *_s = s + 3;
      return i;
    }
  }
  return -1;
}

/* similar to strptime. Conversions: %Y year, %m month (1-12), %d day,
 * %H hour (0-23, or 1-12 with %p), %M minutes, %s or %S seconds, %a day
 * name, %b month name (full or 3 letters), %p AM/PM, %z zone offset, %%.
 * A space in the format matches any run of spaces. Fields the format
 * doesn't set stay -1, except the time fields, which default to 0, and
 * zone_diff, which is CDATE_NO_ZONE without a %z.
 * Returns FALSE for empty input, input that ends before the format, text
 * that doesn't match the format, an unknown conversion or a field out of
 * range; text after the end of the format is ignored. */
Bool Str2CDateStruct(U8 *strdate, U8 *format, CDateStruct *t)
{
  I64 year = -1, mon = -1, day = -1, hour = 0, min = 0, sec = 0;
  I64 dow = -1, zone = CDATE_NO_ZONE, v, pm = -1, sign;
  Bool has_hour = FALSE;

  /* initialise the date struct's field to -1, error state */
  MemSet(t, -1, sizeof(CDateStruct));
  t->sec10000 = 0;
  t->sec100 = 0;
  t->sec = 0;
  t->min = 0;
  t->hour = 0;
  t->zone_diff = CDATE_NO_ZONE;

  if (!strdate || !format || !*strdate) {
    return FALSE;
  }

  while (*format) {
    if (*format == '%') {
      format++;
      switch (*format) {
        case 'a': /* day name */
          if ((dow = _StrDateName(&strdate, _str_date_days, 7)) < 0) {
            return FALSE;
          }
          break;
        case 'b': /* month name */
          if ((v = _StrDateName(&strdate, _str_date_months, 12)) < 0) {
            return FALSE;
          }
          mon = v + 1;
          break;
        case 'd': if (!_StrDateNum(&strdate, 2, &day)) return FALSE; break;
        case 'm': if (!_StrDateNum(&strdate, 2, &mon)) return FALSE; break;
        case 'Y': if (!_StrDateNum(&strdate, 4, &year)) return FALSE; break;
        case 'H':
          if (!_StrDateNum(&strdate, 2, &hour)) return FALSE;
          has_hour = TRUE;
          break;
        case 'M': if (!_StrDateNum(&strdate, 2, &min)) return FALSE; break;
        case 's':
        case 'S': if (!_StrDateNum(&strdate, 2, &sec)) return FALSE; break;
        case 'p': /* am or pm */
          while (*strdate == ' ') strdate++;
          if (StrNICmp(strdate, "AM", 2) == 0) {
            pm = 0;
          } else if (StrNICmp(strdate, "PM", 2) == 0) {
            pm = 1;
          } else {
            return FALSE;
          }
          strdate += 2;
          break;
        case 'z': /* timezone difference, e.g. +0100 */
          while (*strdate == ' ') strdate++;
          sign = 1;
          if (*strdate == '+') {
            strdate++;
          } else if (*strdate == '-') {
            sign = -1;
            strdate++;
          }
          if (!_StrDateNum(&strdate, 4, &zone)) return FALSE;
          if (zone / 100 > 23 || zone % 100 > 59) return FALSE;
          zone *= sign;
          break;
        case '%':
          if (*strdate != '%') return FALSE;
          strdate++;
          break;
        default:
          return FALSE;
      }
      format++;
    } else if (*format == ' ') {
      while (*format == ' ') format++;
      while (*strdate == ' ' || *strdate == '\t') strdate++;
    } else {
      if (*strdate != *format) {
        return FALSE;
      }
      strdate++;
      format++;
    }
  }

  if (pm != -1) {
    if (!has_hour || hour < 1 || hour > 12) {
      return FALSE;
    }
    if (pm == 0 && hour == 12) {
      hour = 0;
    } else if (pm == 1 && hour != 12) {
      hour += 12;
    }
  }
  if (hour > 23 || min > 59 || sec > 59) {
    return FALSE;
  }
  if (mon != -1 && (mon < 1 || mon > 12)) {
    return FALSE;
  }
  if (day != -1) {
    I64 max_day = 31;
    if (mon == 4 || mon == 6 || mon == 9 || mon == 11) {
      max_day = 30;
    } else if (mon == 2) {
      max_day = 29;
      if (year != -1 &&
          !((year % 4 == 0 && year % 100 != 0) || year % 400 == 0)) {
        max_day = 28;
      }
    }
    if (day < 1 || day > max_day) {
      return FALSE;
    }
  }

  t->year = year;
  t->mon = mon;
  t->day_of_mon = day;
  t->day_of_week = dow;
  t->hour = hour;
  t->min = min;
  t->sec = sec;
  t->zone_diff = zone;
  return TRUE;
}

CDate StrToCDate(U8 *_str, U8 *fmt)
{// Parse with Str2CDateStruct into a CDate; 0 if that fails or the
 // format doesn't give the year, month and day
  CDateStruct ds;
  if (!Str2CDateStruct(_str,fmt,&ds)) {
    return 0;
  }
  if (ds.year(I64) == -1 || ds.mon(I64) == -1 || ds.day_of_mon(I64) == -1) {
    return 0;
  }
  return Struct2Date(&ds);
}

Bool WildMatch(U8 *test_str, U8 *wild_str)
{// Wildcard match of the whole of test_str: '*' matches any run of
 // characters (also none) and '?' any one character; everything else
 // matches itself. FALSE if either string is NULL.
  U8 *star_wild = NULL, *star_test = NULL;
  if (!test_str || !wild_str) return FALSE;
  while (*test_str) {
    if (*wild_str == '*') {
      /* remember where to retry if the rest doesn't match */
      star_wild = ++wild_str;
      star_test = test_str;
    } else if (*wild_str && (*wild_str == '?' || *wild_str == *test_str)) {
      wild_str++;
      test_str++;
    } else if (star_wild) {
      /* let the last '*' swallow one more character and retry */
      wild_str = star_wild;
      test_str = ++star_test;
    } else {
      return FALSE;
    }
  }
  while (*wild_str == '*') wild_str++;
  return *wild_str == '\0';
}
