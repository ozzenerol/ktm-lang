module str;
import libc, mem, os;

const i64 PRTF_PLUS = 0x1000; /* `+`: a sign on positive numbers too */

const i64 PRTF_SPACE = 0x2000; /* ` `: a space where `+` would go */

const i64 PRTF_ALT = 0x4000; /* `#`: 0x/0X before hex, a leading 0 in octal */

#ifdef IS_X86_64
asm {
// All three return like C: <0 if s1 < s2, 0 if equal, >0 if s1 > s2,
// comparing bytes as unsigned and stopping at the first NUL.
// RDI=s1, RSI=s2, RDX=N
// 'A'=65, 'Z'=90; letters are lowercased like strncasecmp
_STRNICMP::
                PUSHQ  RBP
                MOVQ   RBP, RSP
                CALL   _STRNULLCMP     // null sorts first: RAX = result, RCX = 1 if done
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

// RDI=s1, RSI=s2. null sorts before every string: sets RAX to
// -1/0/1 and RCX to 1 when either is null, else RCX = 0. Keeps RDX.
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

/* RDI= u8 character */
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

/* RDI: u8 * string
    * RET u64 hashed string: h = h*31 + c over the bytes (0 for ""), then
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

pub _extern _TOLOWER u8 toLower(u8 ch);
pub _extern _TOUPPER u8 toUpper(u8 ch);
pub _extern _ISSPACE bool isSpace(u8 ch);
pub _extern _STRHASH u64 hash(u8 *str);
#endif

#ifdef __aarch64__
asm {
// strncmp(x0=str1, x1=str2, x2=len); null sorts first
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
// X0 = str1, X1 = str2, X2 = len; null sorts first
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

// null sorts first
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

// Like x86_64: a null dst does nothing, a null src copies ""
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

pub u8 toLower(u8 ch) {
        if (ch >= 'A' && ch <= 'Z') { return ch + 32; }
        return ch;
}

pub u8 toUpper(u8 ch) {
        if (ch >= 'a' && ch <= 'z') { return ch - 32; }
        return ch;
}

pub bool isSpace(u8 ch) {
        return ch == ' ' || ch == '\t' || ch == '\n' ||
                                    ch == '\v' || ch == '\f' || ch == '\r';
}

pub u64 hash(u8 *str)
{// h = h*31 + c over the bytes (0 for ""), then the murmur3 finaliser;
    // must match _STRHASH on x86_64
        u64 h = 0;
        for (i64 i = 0; str[i]; ++i) {
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

pub i64 toInt(u8 *str)
{// The integer at the start of str (like atol); 0 for null
        if (!str) { return 0; }
        return libc.atol(str);
}

/* The length of `buf` before its '\0', 0 for null. libc's strlen, which
    * reads a word at a time: about 5x the old byte loop. */
pub u64 len(u8 *buf) {
        if (!buf) { return 0; }
        return libc.strlen(buf);
}

pub _extern _STRNCMP i64 cmpLen(u8 *s1, u8 *s2, i64 len);

pub _extern _STRNICMP i64 cmpLenNoCase(u8 *s1, u8 *s2, i64 len);

pub _extern _STRCMP i64 cmp(u8 *s1, u8 *s2);

pub _extern _STRCPY void copy(u8 *dst, u8 *src);

pub u8 *dupLen(u8 *buf, i64 len)
{// A new string with at most the first `len` bytes of buf, stopping at
    // its NUL (like C's strndup); always NUL terminated. A negative len is
    // 0 and a null buf gives "".
        i64 n = 0;
        if (len < 0) { len = 0; }
        if (buf) {
                while (n < len && buf[n]) { n++; }
        }
        u8 *str = mem.alloc(n+1);
        if (n) { mem.copy(str,buf,n); }
        str[n] = '\0';
        return str;
}

pub u8 *dup(u8 *buf) {
        u8 *res;
        i64 size;
        if (buf) {
                size = len(buf)+1;
                res = mem.alloc(size);
                mem.copy(res,buf,size);
                res[size-1] = '\0';
        } else {
                res=mem.alloc(1);
                *res=0;
        }
        return res;
}

void strPutChar(u8 **_dst,u8 ch,u8 **_buf) {
        i64 i;
        u8 *dst=*_dst,*buf;
        if (_buf) {
                buf=*_buf;
                i=dst-buf;
                if (i+1>=mem.size(buf)) { /* room for ch and the '\0' after it */
                        /* Double, with room for ch and the '\0' even when i is 0.
                            * (TempleOS wrote i<<1+1, which ktm parses as i<<2.) */
                        buf=mem.alloc((i+2)*2);
                        mem.copy(buf,*_buf,i);
                        mem.free(*_buf);
                        dst=buf+i;
                        *_buf=buf;
                }
        }
        *dst++=ch;
        *dst='\0';
        *_dst=dst;
}

void outStr(u8 *instr,u8 **_buf,u8 **_dst,i64 len,i64 flags,
                                                                    i64 prec=-1) {
    // instr -> in string
    // _buf  -> the underlying buffer 
    // _dst  -> where the string will go
    // len   -> the minimum width, padded with spaces
    // prec  -> if >= 0 at most this many chars of instr are written, as C's %.Ns
        i64 i,j,size,dstLen;
        u8 *buf,*dst;
        if (!instr) {
                i=0;
        } else {
                i=len(instr);
        }
        dst = *_dst;
        /* No _buf: the caller's buffer is not ours to MSize/ReAlloc */
        if (_buf) {
                buf = *_buf;
                dstLen = dst-buf;
                size = mem.size(buf);

                if (i >= (size - dstLen)) { /* double, as CatPrint does */
                        buf = mem.realloc(buf,((size*2)+i)*sizeof(u8));
                        *_buf = buf;
                        *_dst = buf + dstLen;
                        dst = *_dst;
                }
        }
        if (flags&PRTF_TRUNCATE && i>len) {
                i=len;
        }
        if (prec>=0 && i>prec) {
                i=prec;
        }
        /* All writes go through _dst: SPutChar may move the buffer */
        if (!(flags&PRTF_LEFT_JUSTIFY)) {
                for (j=i;j<len;j++) {
                        strPutChar(_dst,CH_SPACE,_buf);
                }
        }
        for (j=0;j<i;j++) {
                strPutChar(_dst,*instr++,_buf);
        }
        if (flags&PRTF_LEFT_JUSTIFY) {
                for (j=i;j<len;j++) {
                        strPutChar(_dst,CH_SPACE,_buf);
                }
        }
}

pub i64 count(u8 *src, i64 ch)
{// Count occurrences of a char.
        i64 i=0;
        if (!src) { return 0; }
        while (*src) {
                if (*src++==ch) {
                        i++;
                }
        }
        return i;
}

pub u8 *firstOf(u8 *src,u8 *marker)
{// Point to 1st occurrence of marker set in str.
        i64 ch;
        if (!src || !marker) { return null; }
        while ((ch=*src++) && !count(marker,ch)) {}
        if (ch) {
                return src-1;
        } else {
                return null;
        }
}

pub u8 *splitFirst(u8 *src,u8 *marker,u8 *dst=null)
{// Remove first str segment and place in dst buf or null.
        i64 ch;
        u8 *ptr=src,*res=dst;
        if (!src || !marker) {
                if (dst) { *dst=0; }
                return res;
        }
        if (dst) {
                while ((ch=*ptr++) && !count(marker,ch)) {
                        *dst++=ch;
                }
                *dst=0;
        } else {
                while ((ch=*ptr++) && !count(marker,ch)) {}
        }
        if (ch) {
                copy(src,ptr);
        } else {
                *src=0;
        }
        return res;
}

pub u8 *lastOf(u8 *src,u8 *marker)
{// Point to last occurrence of market set in str.
        i64 ch;
        u8 *res=null;
        if (!src || !marker) { return null; }
        while (ch=*src++) {
                if (count(marker,ch)) {
                        res=src-1;
                }
        }
        return res;
}

pub u8 *splitLast(u8 *src,u8 *marker,u8 *dst=null)
{// Remove last str segment and place in dst buf or null.
        u8 *ptr;
        if (!src || !marker) {
                if (dst) { *dst=0; }
                return dst;
        }
        if ((ptr=lastOf(src,marker)) != null) {
                if (dst) {
                        copy(dst,ptr+1);
                }
                *ptr=0;
        } else {
                if (dst) {
                        copy(dst,src);
                }
                *src=0;
        }
        return dst;
}

pub u8 *trimStart(u8 *str, i64 strLen=-1, i64 *_new_len=null)
{ // Mutates the string, removes whitespace at the start
        // of the string, MSize calls are still safe. Only the first str_len
        // bytes are looked at (the whole string if str_len < 0); when something
        // is removed the result is NUL terminated at its new length. null
        // returns null.
        i64 newLen,len,j;

        if (!str) {
                if (_new_len) { *_new_len = 0; }
                return null;
        }
        if (strLen < 0) {
                len = len(str);
        } else {
                len = strLen;
        }
        j = 0;

        while (j < len && isSpace(str[j])) {
                ++j;
        }

        newLen = len-j;
        if (_new_len) {
                *_new_len = newLen;
        }
        if (j == 0) {
                return str;
        }
        libc.memmove(str,str+j,newLen);
        str[newLen] = '\0';
        return str;
}

pub u8 *trimEnd(u8 *str, i64 strLen=-1, i64 *_new_len=null)
{ // Mutates the string, removes whitespace at the end
        // of the string, MSize calls are still safe. Only the first str_len
        // bytes are looked at (the whole string if str_len < 0). null returns
        // null.
        i64 newLen,len,i;

        if (!str) {
                if (_new_len) { *_new_len = 0; }
                return null;
        }
        if (strLen < 0) {
                len = len(str);
        } else {
                len = strLen;
        }
        i = len-1;

        while (i >= 0 && isSpace(str[i])) {
                --i;
        }
        i++;

        newLen = i;
        if (_new_len) {
                *_new_len = newLen;
        }
        if (newLen == len) {
                return str;
        }
        str[newLen] = '\0';
        return str;
}

pub i64 trimEndChars(u8 *str, u8 *chars)
{ // Mutates the string, removes any of the characters in `chars` from
        // the end of the string and returns its new length. null returns 0.
        i64 len,j;

        if (!str) {
                return 0;
        }
        len = len(str);

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

pub void reverse(u8 *buf, i64 length)
{ // Reverse a string inplace
        i64 start = 0, end = length - 1;
        while (start < end) {
                u8 temp = buf[start];
                buf[start] = buf[end];
                buf[end] = temp;
                start++;
                end--;
        }
}

i64 i64ToStr(u8 *buf, i64 num, bool *_is_neg=null, i64 _len=5000)
{ // puts num in the buf, returns the length of the number
        i64 i = 0;
        u64 mag = num; /* unsigned: -I64_MIN doesn't fit in an i64 */

        if (num == 0) {
                buf[i++] = '0';
                buf[i] = '\0';
                return 1;
        }

        if (_is_neg) {
                *_is_neg = false;
        }
        // Check for negative numbers
        if (num < 0) {
                if (_is_neg) {
                        *_is_neg = true;
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

u64 u64ToStr(u8 *dst, u64 num, i64 len=5000) {
        i64 i = 0;
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

pub i64 hex(u8 *dst, i64 num) {
        u8 *hexStr="0123456789ABCDEF";
        i64 i = (sizeof(u64) * 2) + 1, len = 2; /* "0x" + 16 digits */
        dst[i+1] = '\0';
        while (i >= 2) {
                len++;
                dst[i] = hexStr[num & 0xF];
                num >>= 4;
                i--;
        }
        dst[i] = 'x';
        dst[i-1] = '0';
        return len;
}

pub u8 *quote(u8 *ptr,i64 flags = 0) {
        u8 **_buf,*buf,**_dst,*dst,buf2[8],*ptr2;
        i64 ch;
        buf=mem.alloc(STR_LEN);
        _buf=&buf;
        dst=buf;
        _dst=&dst;
        if (ptr) {
                while (ch=*ptr++) {
                                switch (ch) {
                                        case '%':
                                                strPutChar(_dst,ch,_buf);
                                                if (flags&PRTF_SLASH) {
                                                        strPutChar(_dst,ch,_buf);
                                                }
                                        case '\n':
                                                strPutChar(_dst,'\\',_buf);
                                                strPutChar(_dst,'n',_buf);
                                        case '\r':
                                                strPutChar(_dst,'\\',_buf);
                                                strPutChar(_dst,'r',_buf);
                                        case '\t':
                                                strPutChar(_dst,'\\',_buf);
                                                strPutChar(_dst,'t',_buf);
                                        case '\v':
                                                strPutChar(_dst,'\\',_buf);
                                                strPutChar(_dst,'v',_buf);
                                        case '\f':
                                                strPutChar(_dst,'\\',_buf);
                                                strPutChar(_dst,'f',_buf);
                                        case '"':
                                        case '\\':
                                                strPutChar(_dst,'\\',_buf);
                                                strPutChar(_dst,ch,_buf);
                                        default:
                                                if (ch>=CH_SHIFT_SPACE && ch!=0x7F) {
                                                        strPutChar(_dst,ch,_buf);
                                                } else { /* `\x01`, which reads back as the same byte */
                                                        formatTo(buf2,"\\x%02X",ch);
                                                        ptr2=buf2;
                                                        while (*ptr2) {
                                                                strPutChar(_dst,*ptr2++,_buf);
                                                        }
                                                }
                                }
                }
        }
        strPutChar(_dst,0,_buf);
        return buf;
}

/* Digit buffer for an exact f64 decimal expansion: up to 309 integer digits
    * sit before F64_DIG_POINT, up to 1074 fraction digits from it. */
const i64 F64_DIG_POINT = 320;

const i64 F64_DIG_LEN = 1408;

static void f64DigRound(u8 *dig, i64 *_lo, i64 hi, i64 cut)
{ // Round the digits to dig[..cut) to nearest, ties to even, like C's printf
        i64 i;
        bool up = false;
        if (cut >= hi) { return; }
        if (dig[cut] > 5) {
                up = true;
        } else if (dig[cut] == 5) {
                i = cut + 1;
                while (i < hi && !dig[i]) { i++; }
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
                if (i < *_lo) { *_lo = i; }
        }
}

static u8 *f64ToFmtStr(u64 bits, i64 prec, i64 conv)
{ // %f, %e or %g of the f64 with bit pattern `bits`, without the sign.
        // Works on the exact decimal value of the double, so it rounds like C.
        u8 *dig, *out, *o;
        i64 ex = (bits >> 52) & 0x7FF, lo, hi, cut, i, s, v, carry, fz, x;
        u64 mant = bits & 0xFFFFFFFFFFFFF;
        bool strip = false;

        if (ex == 0x7FF) {
                if (mant) { return dup("nan"); }
                return dup("inf");
        }
        if (prec < 0) { prec = 6; }
        if (conv == 'g' && prec == 0) { prec = 1; }
        if (ex) {
                mant |= 0x10000000000000;
                ex -= 1075;
        } else {
                ex = -1074;
        }

        /* value = mant * 2^ex, as decimal digits in dig[lo..hi) */
        dig = mem.allocZero(F64_DIG_LEN);
        lo = hi = F64_DIG_POINT;
        while (mant) {
                dig[--lo] = mant % 10;
                mant /= 10;
        }
        while (ex > 0) { // multiply by 2^s
                s = ex;
                if (s > 28) { s = 28; }
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
                if (s > 28) { s = 28; }
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
        while (fz < hi && !dig[fz]) { fz++; }
        if (conv == 'f') {
                cut = F64_DIG_POINT + prec;
        } else if (conv == 'e') {
                cut = fz + prec + 1;
        } else {
                cut = fz + prec;
        }
        f64DigRound(dig, &lo, hi, cut);
        fz = lo;
        while (fz < hi && !dig[fz]) { fz++; }
        x = 0; // decimal exponent of the first significant digit
        if (fz < hi) { x = F64_DIG_POINT - 1 - fz; }

        if (conv == 'g') {
                strip = true;
                if (x < prec && x >= -4) {
                        conv = 'f';
                        prec = prec - 1 - x;
                } else {
                        conv = 'e';
                        prec--;
                }
        }

        out = o = mem.alloc(prec + F64_DIG_POINT + 16);
        if (conv == 'f') {
                i = lo;
                if (i > F64_DIG_POINT - 1) { i = F64_DIG_POINT - 1; }
                while (i < F64_DIG_POINT - 1 && !dig[i]) { i++; }
                while (i < F64_DIG_POINT) { *o++ = dig[i++] + '0'; }
                cut = F64_DIG_POINT + prec;
        } else {
                *o++ = '0';
                if (fz < hi) { o[-1] += dig[fz]; }
                i = fz + 1;
                cut = i + prec;
        }
        if (prec) {
                *o++ = '.';
                while (i < cut) {
                        *o = '0';
                        if (i < hi) { *o += dig[i]; }
                        o++;
                        i++;
                }
                if (strip) {
                        while (o[-1] == '0') { o--; }
                        if (o[-1] == '.') { o--; }
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
                if (x >= 100) { *o++ = x / 100 + '0'; }
                *o++ = x / 10 % 10 + '0';
                *o++ = x % 10 + '0';
        }
        *o = '\0';
        mem.free(dig);
        return out;
}

/* An integer conversion as C prints it: `conv` is d i u o x or X, `width`
    * the minimum width, `prec` the minimum number of digits (-1: none; 0
    * with a 0 value prints no digits). */
static void strPrintInt(u8 **_ptr, u8 **grow, u64 val, i64 conv, i64 flags,
                                                                                        i64 width, i64 prec) {
        u8 digits[72], prefix[4], *hex = "0123456789abcdef";
        i64 base = 10, ndig = 0, nzero = 0, npre = 0, total, i;
        bool neg = false;

        if (conv == 'd' || conv == 'i') {
                if ((i64)val < 0) {
                        neg = true;
                        val = -(i64)val; /* as a u64, also right for I64_MIN */
                }
        } else if (conv == 'o') {
                base = 8;
        } else if (conv == 'x' || conv == 'X') {
                base = 16;
                if (conv == 'X') { hex = "0123456789ABCDEF"; }
        }

        if (val || prec != 0) {
                do {
                        digits[ndig++] = hex[val % base];
                        val /= base;
                } while (val);
        }

        if (neg)                    { prefix[npre++] = '-'; }
        else if (conv == 'd' || conv == 'i') {
                if (flags & PRTF_PLUS)       { prefix[npre++] = '+'; }
                else if (flags & PRTF_SPACE) { prefix[npre++] = ' '; }
        }
        if (flags & PRTF_ALT && base == 16 && ndig && !(ndig == 1 && digits[0] == '0')) {
                prefix[npre++] = '0';
                prefix[npre++] = conv;
        }

        if (prec > ndig) { nzero = prec - ndig; }
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
        if (!(flags & PRTF_LEFT_JUSTIFY)) {
                for (i = total; i < width; ++i) { strPutChar(_ptr,CH_SPACE,grow); }
        }
        for (i = 0; i < npre; ++i)        { strPutChar(_ptr,prefix[i],grow); }
        for (i = 0; i < nzero; ++i)       { strPutChar(_ptr,'0',grow); }
        for (i = ndig - 1; i >= 0; --i)   { strPutChar(_ptr,digits[i],grow); }
        if (flags & PRTF_LEFT_JUSTIFY) {
                for (i = total; i < width; ++i) { strPutChar(_ptr,CH_SPACE,grow); }
        }
}

pub u8 *formatArgs(u8 *_dst, u8 *fmt, u64[] args) {
        if (!fmt) { return null; }
        u64 uintarg, curlen = 0;
        f64 farg;
        i64 ch, sp = 0, len, flags, tlen,dec_len;
        u8 *ptr, *strarg, *tmp, *dst, *spec, buf[256];
        u8 **grow;

        /* Growth (SPutChar's and OutStr's MSize/Free dance) is only legal on a
            * buffer WE MAlloc'd. A caller-provided dst may live on the stack or mid-array,
            * where MSize reads garbage and Free aborts - so, as in TempleOS, the
            * caller's buffer is trusted to be big enough and never grown. */
        if (_dst == null) {
                dst = mem.alloc(STR_LEN);
                grow = &dst;
        } else {
                dst = _dst;
                grow = null;
        }

        ptr = dst;
        *ptr = '\0'; /* SPutChar terminates after each char: an empty result needs it too */
        while (ch = *fmt++) {
                if (ch == '%') {
                        spec = fmt - 1; /* the '%', for an unsupported conversion */
                        flags = 0;
                        /* The flags, in any order, as in C */
                        while (true) {
                                if (*fmt == '-')      { flags |= PRTF_LEFT_JUSTIFY; }
                                else if (*fmt == '0') { flags |= PRTF_PAD_ZERO; }
                                else if (*fmt == '+') { flags |= PRTF_PLUS; }
                                else if (*fmt == ' ') { flags |= PRTF_SPACE; }
                                else if (*fmt == '#') { flags |= PRTF_ALT; }
                                else { break; }
                                fmt++;
                        }
                        tlen = 0;
                        while ('0'<=*fmt<='9') {
                                tlen = tlen * 10 + *fmt++ -'0';
                        }
                        if (*fmt == '*') {
                                fmt++;
                                tlen = (u64)args[sp++];
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
                                        dec_len=args[sp++];
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
                                        strPutChar(&ptr,'%',grow);
                                }

                                case 'Q': {
                                        strarg = (u8*)args[sp++];
                                        strarg = quote(strarg,flags);
                                        outStr(strarg,grow,&ptr,tlen,flags);
                                        mem.free(strarg);
                                }

                                /* Presently there is only f64, %f/%e/%g as C prints them */
                                case 'e':
                                case 'g':
                                case 'f': {
                                        uintarg = (u64)args[sp++]; // the f64's bits
                                        strarg = f64ToFmtStr(uintarg,dec_len,*(fmt-1));
                                        len = len(strarg);
                                        u8 sign = 0; /* '-', or '+' / ' ' with those flags */
                                        if (uintarg >> 63)           { sign = '-'; }
                                        else if (flags & PRTF_PLUS)  { sign = '+'; }
                                        else if (flags & PRTF_SPACE) { sign = ' '; }
                                        if (sign) {
                                                len++;
                                        }
                                        /* No zero padding for inf/nan, '-' wins over '0' */
                                        if (flags & PRTF_LEFT_JUSTIFY || !('0' <= *strarg <= '9')) {
                                                flags &= ~PRTF_PAD_ZERO;
                                        }
                                        if (!(flags & (PRTF_PAD_ZERO|PRTF_LEFT_JUSTIFY))) {
                                                for (i64 idx = len; idx < tlen; ++idx) {
                                                        strPutChar(&ptr,CH_SPACE,grow);
                                                }
                                        }
                                        if (sign) {
                                                strPutChar(&ptr,sign,grow);
                                        }
                                        if (flags & PRTF_PAD_ZERO) {
                                                for (i64 idx = len; idx < tlen; ++idx) {
                                                        strPutChar(&ptr,'0',grow);
                                                }
                                        }
                                        for (tmp = strarg; *tmp; ++tmp) {
                                                strPutChar(&ptr,*tmp,grow);
                                        }
                                        if (flags & PRTF_LEFT_JUSTIFY) {
                                                for (i64 idx = len; idx < tlen; ++idx) {
                                                        strPutChar(&ptr,CH_SPACE,grow);
                                                }
                                        }
                                        mem.free(strarg);
                                }

                                case 's': {
                                        strarg = (u8 *)args[sp++];
                                        outStr(strarg,grow,&ptr,tlen,flags&~PRTF_TRUNCATE,dec_len);
                                }

                                /* %D, %T and %F were dates, times and a file's contents:
                                    * they need date and fs, which use str. Kept as written,
                                    * with what to use instead on stderr (as for a conversion
                                    * str doesn't know), the argument taken. */
                                case 'D':
                                case 'T':
                                case 'F': {
                                        sp++;
                                        u8 conv = *(fmt-1);
                                        u8 *instead = conv == 'D' ? "date.format(d, \"%m/%d/%Y\")"
                                                    : conv == 'T' ? "date.format(d, \"%H:%M:%S\")"
                                                    : "fs.readAll(path, &len) and %s";
                                        len = libc.snprintf(buf,sizeof(buf),
                                                "%%%c is not a conversion any more: use %s\n",conv,instead);
                                        libc.write(os.STDERR,buf,len);
                                        for (tmp = spec; tmp < fmt; ++tmp) {
                                                strPutChar(&ptr,*tmp,grow);
                                        }
                                }

                                case 'c': {
                                        uintarg = (u64)args[sp++];
                                        for (i64 i = 0; i < 8; ++i) {
                                                buf[i] = uintarg >> ((i*8) & 0xFF);
                                        }
                                        buf[8] = '\0';
                                        outStr(buf,grow,&ptr,tlen,flags);
                                }

                                case 'C': {
                                        uintarg = (u64)args[sp++];
                                        for (i64 i = 0; i < 8; ++i) {
                                                buf[i] = toUpper(uintarg >> ((i*8) & 0xFF));
                                        }
                                        buf[8] = '\0';
                                        outStr(buf,grow,&ptr,tlen,flags);
                                }

                                case 'd':
                                case 'i':
                                case 'u':
                                case 'o':
                                case 'x':
                                case 'X': {
                                        uintarg = (u64)args[sp++];
                                        strPrintInt(&ptr,grow,uintarg,*(fmt-1),flags,tlen,dec_len);
                                }

                                case 'p': {
                                        uintarg = (u64)args[sp++];
                                        len = hex(buf,uintarg);
                                        outStr(buf,grow,&ptr,tlen,flags);
                                }

                                default: {
                                        /* *(fmt-1) is not a conversion StrPrint knows. Kept in the
                                            * output as written, as C prints one, with a diagnostic on
                                            * stderr. As in C a conversion ends at a letter: what it may
                                            * still hold (`%ld`, `%+d`) is skipped and its argument taken,
                                            * so the conversions after it get the right ones. A '%' at the
                                            * very end stops at the NUL instead of reading past it. */
                                        u8 bad = *(fmt-1);
                                        if (!bad) {
                                                fmt--;
                                                len = libc.snprintf(buf,sizeof(buf),
                                                                                                            "Invalid format: '%%' with no conversion at the end\n");
                                        } else {
                                                u8 *end = null;
                                                for (tmp = fmt-1; *tmp && count(" +#'0123456789.hlLqjzt",*tmp);
                                                                    ++tmp) {
                                                        if ('a' <= *tmp <= 'z' || 'A' <= *tmp <= 'Z') { end = tmp; }
                                                }
                                                if ('a' <= *tmp <= 'z' || 'A' <= *tmp <= 'Z') { end = tmp; }
                                                if (end) {
                                                        sp++;
                                                        fmt = end+1;
                                                }
                                                len = libc.snprintf(buf,sizeof(buf),"Invalid format char: '%c'\n",bad);
                                        }
                                        libc.write(os.STDERR,buf,len);
                                        for (tmp = spec; tmp < fmt; ++tmp) {
                                                strPutChar(&ptr,*tmp,grow);
                                        }
                                }
                        }
                } else {
                        strPutChar(&ptr,ch,grow);
                }
        }
        return dst;
}

pub u8 *concatArgs(u64[] args) {
        u8 *buf = mem.alloc(256), *dst = buf;
        for (auto arg : args) {
                outStr((u8*)arg,&buf,&dst,0,0);
        }
        return buf;
}

pub u8 *concat(...args) {
        return concatArgs(args);
}

pub u8 *formatTo(u8 *dst=null,u8 *fmt,...args) {
        return formatArgs(dst,fmt,args);
}

/* Format into `dst`, which has room for `size` bytes, like C's snprintf:
    * at most size-1 characters are written, always followed by a '\0' (size
    * <= 0 writes nothing), and longer output is cut short. Returns dst.
    * StrPrint(dst, ...) has no bound, so use this for a fixed buffer. */
pub u8 *formatToSize(u8 *dst, i64 size, u8 *fmt, ...args) {
        if (!dst || size <= 0) { return dst; }
        u8 *full = formatArgs(null,fmt,args);
        i64 len = 0;
        if (full) {
                len = len(full);
                if (len > size - 1) { len = size - 1; }
                mem.copy(dst,full,len);
                mem.free(full);
        }
        dst[len] = '\0';
        return dst;
}

pub u8 *format(u8 *fmt,...args)
{// MAlloc StrPrint.
        u8 *res,*buf=formatArgs(null,fmt,args);
        res=dup(buf);
        mem.free(buf);
        return res;
}

/* Append formatted text to the heap string `_dst` (MAlloc, StrNew,
    * StrPrint(null, ...) or a previous CatPrint; null starts a new one),
    * growing it when it is full, and return it. The block may move and the
    * old pointer is then freed, so always write `s = CatPrint(s, ...)` and
    * never pass a string literal or a stack buffer. Each call measures the
    * whole of `_dst` to find its end, so n appends cost O(n^2) for a long
    * string: in a loop keep the length yourself with CatLenPrint, or
    * collect the pieces and StrPrint them once. */
pub u8 *appendFormat(u8 *_dst,u8 *fmt,...args) {
        u8 *s2 = formatArgs(null,fmt,args);
        if (!s2) { return _dst; }
        if (!_dst) { return s2; }
        i64 len = len(_dst);
        i64 s2len = len(s2);
        i64 size = mem.size(_dst);
        if (len + s2len >= size) {
                _dst = mem.realloc(_dst,(size*2)+s2len+1);
        }
        mem.copy(_dst+len,s2,s2len+1);
        mem.free(s2);
        return _dst;
}

/* CatPrint for a string whose length the caller keeps in `*_len`, so no
    * StrLen per call. `_dst` may be null (with *_len 0) to start one. */
pub u8 *appendFormatLen(u8 *_dst, i64 *_len, u8 *fmt, ...args) {
        i64 len = *_len;
        u8 *s2 = formatArgs(null,fmt,args);
        if (!s2) { return _dst; }
        i64 s2len = len(s2);
        i64 size = mem.size(_dst);
        if (len + s2len >= size) { /* +1 for the '\0' (size is 0 for null) */
                _dst = mem.realloc(_dst,(size*2)+s2len+1);
        }
        mem.copy(_dst+len,s2,s2len);
        len+=s2len;
        _dst[len] = '\0';
        *_len = len;
        mem.free(s2); /* the formatted text, now copied */
        return _dst;
}

pub bool globMatch(u8 *testStr, u8 *wildStr)
{// Wildcard match of the whole of test_str: '*' matches any run of
    // characters (also none) and '?' any one character; everything else
    // matches itself. false if either string is null.
        u8 *starWild = null, *starTest = null;
        if (!testStr || !wildStr) { return false; }
        while (*testStr) {
                if (*wildStr == '*') {
                        /* remember where to retry if the rest doesn't match */
                        starWild = ++wildStr;
                        starTest = testStr;
                } else if (*wildStr && (*wildStr == '?' || *wildStr == *testStr)) {
                        wildStr++;
                        testStr++;
                } else if (starWild) {
                        /* let the last '*' swallow one more character and retry */
                        wildStr = starWild;
                        testStr = ++starTest;
                } else {
                        return false;
                }
        }
        while (*wildStr == '*') { wildStr++; }
        return *wildStr == '\0';
}
