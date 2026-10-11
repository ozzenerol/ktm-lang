module fzf;

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
pub _extern _STRHASH u64 strHash(u8 *str);
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

pub u8 ToLower(u8 ch) {
        if (ch >= 'A' && ch <= 'Z') return ch + 32;
        return ch;
}

pub u8 ToUpper(u8 ch) {
        if (ch >= 'a' && ch <= 'z') return ch - 32;
        return ch;
}

pub bool IsSpace(u8 ch) {
        return ch == ' ' || ch == '\t' || ch == '\n' ||
                                    ch == '\v' || ch == '\f' || ch == '\r';
}

pub u64 StrHash(u8 *str)
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
