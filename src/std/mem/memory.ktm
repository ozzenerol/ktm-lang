module mem;

/* Yup we can mix #define's and assembly */
const u64 ALIGN_16 = 0xFFFFFFFFFFFFFFF0;

#ifdef IS_X86_64
asm {
_MALLOC::
    PUSHQ  RBP
    MOVQ   RBP, RSP
    SUB    RSP,16
    MOV    -8[RBP], RDI
    ADD    RDI, 8
    JC     @@1           // size + 8 wrapped: fail
    CALL   malloc
    TEST   RAX, RAX
    JZ     @@2
    MOV    RCX, -8[RBP]
    MOV    [RAX], RCX
    ADD    RAX, 8
    LEAVE
    RET
@@1:
    MOVQ   RAX, 0
@@2:
    LEAVE
    RET

// RDI = size
// Different from c's calloc
_CALLOC::
    PUSHQ  RBP
    MOVQ   RBP, RSP
    SUB    RSP,16
    MOV    -8[RBP],RDI
    CALL   _MALLOC
    TEST   RAX, RAX
    JZ     @@1
    MOV    -16[RBP], RAX
    MOV    RDX, -8[RBP]
    MOV    RSI, 0
    MOV    RDI, -16[RBP]
    CALL   _MEMSET
    MOV    RAX, -16[RBP]
@@1:
    LEAVE
    RET

// RDI = void *ptr
// RSI = u64 size
_REALLOC:: // void *ReAlloc(void *ptr, i64 size);
    PUSHQ  RBP
    MOVQ   RBP, RSP
    SUB    RSP, 32
    TEST   RDI, RDI
    JZ     @@1
    MOV    -8[RBP], RDI
    MOV    -24[RBP], RSI // new_size
    MOV    RDI, RSI
    CALL   _MALLOC
    MOV    -16[RBP], RAX // new memory saved
    TEST   RAX, RAX
    JZ     @@2           // out of memory: keep the old block, return null
    MOV    RDI, -8[RBP]  // find out the old size
    CALL   _MSIZE
    MOV    RDX, RAX      // old size
    MOV    RCX, -24[RBP]
    CMP    RDX, RCX
    CMOVA  RDX, RCX      // copy min(old size, new size)
    MOV    RSI, -8[RBP]  // old pointer
    MOV    RDI, -16[RBP] // new pointer
    CALL   _MEMCPY       // copy over
    MOV    RDI, -8[RBP]
    CALL   _FREE
    MOV    RAX, -16[RBP]
    JMP    @@2
@@1: // If pointer is null
    MOV    RDI, RSI
    CALL   _MALLOC
@@2:
    LEAVE
    RET

/* How many bytes were allocated */
_MSIZE::
    PUSHQ  RBP
    MOVQ   RBP, RSP
    TEST   RDI, RDI
    JZ     @@2/* null CHECK */
    SUB    RDI, 8
    MOV    RAX, [RDI]
@@1:
    LEAVE
    RET
@@2:
    MOVQ   RAX, 0
    LEAVE
    RET

/* Gets to the start of the actual allocated buffer
 * And then calls free */
_FREE::
    PUSHQ  RBP
    MOVQ   RBP, RSP
    TEST   RDI,RDI
    JZ     @@1
    SUB    RDI, 8
    CALL   free
@@1:
    LEAVE
    RET

_MEMSET::
    PUSHQ RBP
    MOVQ  RBP, RSP
    MOVQ  RCX, RDX
    MOVQ  RDX, RDI   // return dst
    MOVB  AL,  SIL
    CLD
    REP   STOSB
    MOVQ  RAX, RDX
    LEAVE
    RET

_MEMCPY::
    PUSHQ  RBP
    MOV    RBP, RSP
    MOVQ   RAX, RDI  // return dst
    MOVQ   RCX, RDX
    CLD
    REP    MOVSB
    LEAVE
    RET
}
#endif

#ifdef __aarch64__
asm {

_MALLOC::
    STP    X29, X30, [SP, -16]! // save FP/LR - we make a call
    STR    X0, [SP, -16]!       // save size across the call (X1 is caller-saved)
    ADD    X0, X0, 8            // add 8, big enough for a u64 header
    LDR    X1, [SP]
    CMP    X0, X1
    B.LO   @@1                  // size + 8 wrapped: fail
    BL     malloc               // X0 = malloc(size + 8)
    CBZ    X0, @@1
    LDR    X1, [SP]             // reload size
    STR    X1, [X0]             // store the u64 size header at [ptr]
    ADD    X0, X0, 8            // step the pointer past the header
    B      @@2
@@1:
    MOV    X0, 0
@@2:
    LDR    X1, [SP], 16         // drop the saved size
    LDP    X29, X30, [SP], 16   // restore FP/LR
    RET

_FREE::
    STP    X29, X30, [SP, -16]!
    CBZ    X0, @@1
    SUB    X0, X0, 8
    BL     free
@@1:
    LDP    X29, X30, [SP], 16
    RET

_MSIZE::
    CBZ    X0, @@1
    SUB    X0, X0, 8
    LDR    X0, [X0]
@@1:
    RET

/* Assumes the X0 pointer is not null */
_MEMSET::
    MOV   X3, X0     // return dst
    CBZ	  X2, @@2
    ADD	  X2, X0, X2
@@1:
    STRB  W1, [X0], 1
    CMP   X2, X0
    BNE   @@1
@@2:
    MOV   X0, X3
    RET

_MEMCPY::
    MOV   X4, X0     // return dst
    CBZ	  X2, @@2
    ADD	  X2, X0, X2
@@1:
    LDRB  W3, [X1], 1
    STRB  W3, [X0], 1
    CMP   X2, X0
    BNE   @@1
@@2:
    MOV   X0, X4
    RET

_CALLOC::
    STP    X29, X30, [SP, -32]!
    STR    X0, [SP, 16]
    BL     _MALLOC
    CBZ    X0, @@1
    MOV    W1, 0
    LDR    X2, [SP, 16]
    BL     _MEMSET
@@1:
    LDP    X29, X30, [SP], 32
    RET

_REALLOC::
    // We make a few calls, so we save this
    STP    X29, X30, [SP, -48]!
    CBZ    X0, @@1
    STR    X0, [SP, 16] // store the original pointer
    STR    X1, [SP, 32] // store size
    MOV    X0, X1       // Move the size
    BL     _MALLOC
    CBZ    X0, @@2      // out of memory: keep the old block, return null
    STR    X0, [SP, 24] // store new pointer
    LDR    X0, [SP, 16] // Load old pointer
    BL     _MSIZE       // find size of old pointer
    // if (old_size == new_size) // do nothing else MIN of old vs new.
    LDR    X3, [SP, 32]
    CMP    X0, X3
    CSEL   X2, X0, X3, LS // get min size
    LDR    X1, [SP, 16]   // get old pointer
    LDR    X0, [SP, 24]   // get new pointer
    BL     _MEMCPY
    LDR    X0, [SP, 16]
    BL     _FREE
    LDR    X0, [SP, 24]
    B      @@2
@@1:
    MOV    X0, X1
    BL     _MALLOC
@@2:
    LDP    X29, X30, [SP], 48
    RET
}

#endif

pub _extern _MALLOC void *memAlloc(u64 size);

pub _extern _FREE void memFree(void *ptr);

pub _extern _MSIZE u64 memSize(void *ptr);

pub _extern _MEMSET void *memSet(void *dst, i32 ch, u64 len);

pub _extern _MEMCPY void *memCpy(void *dst, void *src, u64 len);

pub _extern _CALLOC void *memAllocZero(u64 newSize);

pub _extern _REALLOC void *memRealloc(void *ptr, u64 newSize);
