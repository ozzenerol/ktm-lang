module atomic;

/* Atomic operations on integers and pointers shared between threads:
 * a counter many threads add to, a flag one thread sets and another
 * reads, a lock-free swap. Every operation is sequentially consistent
 * (all threads see all of them in one order, and none moves past
 * another memory access), and each is one function call.
 *
 * load, store, add, sub, swap, and, or, xor and cas take an i64 *,
 * i32 *, u64 * or u32 *: overloads, so the type of the pointer picks the
 * width (the values are passed as i64, so a literal or any integer fits
 * every overload and the pointer alone decides). The pointer must be
 * aligned to its type's size (as a field, a local, a global or
 * mem.alloc'd memory always is): a misaligned one isn't atomic on x86_64
 * and faults on arm64. add, sub, swap, and, or
 * and xor return the value before the operation. loadPtr, storePtr,
 * swapPtr and casPtr do the same for a pointer. fence() is a full
 * memory barrier on its own.
 *
 * x86_64: LOCK XADD / XCHG / LOCK CMPXCHG; arm64 (Linux and macOS):
 * LDAXR/STLXR loops, LDAR/STLR, DMB ISH (every ARMv8 CPU; LSE is not
 * needed). The assembler has no mnemonics for most of these, so they
 * are written as their bytes. */

#ifdef IS_X86_64
/* RDI = p, RSI = v (cas: expected), RDX = desired; the result in RAX
 * (32-bit results sign-extended) */
asm {
_ATOMIC_LOAD64::
        MOV    RAX, [RDI]
        RET
_ATOMIC_STORE64::
        DU8    0x48, 0x87, 0x37   // XCHG [RDI], RSI
        RET
_ATOMIC_ADD64::
        DU8    0x48, 0x89, 0xF0   // MOV RAX, RSI
        DU8    0xF0, 0x48, 0x0F, 0xC1, 0x07   // LOCK XADD [RDI], RAX
        RET
_ATOMIC_SWAP64::
        DU8    0x48, 0x89, 0xF0   // MOV RAX, RSI
        DU8    0x48, 0x87, 0x07   // XCHG [RDI], RAX
        RET
_ATOMIC_CAS64::
        DU8    0x48, 0x89, 0xF0   // MOV RAX, RSI (expected)
        DU8    0xF0, 0x48, 0x0F, 0xB1, 0x17   // LOCK CMPXCHG [RDI], RDX (desired)
        DU8    0x0F, 0x94, 0xC0   // SETE AL
        DU8    0x48, 0x0F, 0xB6, 0xC0   // MOVZX RAX, AL
        RET
_ATOMIC_AND64::
        DU8    0x48, 0x8B, 0x07   // MOV RAX, [RDI]
@@1:
        DU8    0x48, 0x89, 0xC1   // MOV RCX, RAX
        DU8    0x48, 0x21, 0xF1   // AND RCX, RSI
        DU8    0xF0, 0x48, 0x0F, 0xB1, 0x0F   // LOCK CMPXCHG [RDI], RCX (RAX: what it was)
        JNZ    @@1
        RET
_ATOMIC_OR64::
        DU8    0x48, 0x8B, 0x07   // MOV RAX, [RDI]
@@1:
        DU8    0x48, 0x89, 0xC1   // MOV RCX, RAX
        DU8    0x48, 0x09, 0xF1   // OR RCX, RSI
        DU8    0xF0, 0x48, 0x0F, 0xB1, 0x0F   // LOCK CMPXCHG [RDI], RCX (RAX: what it was)
        JNZ    @@1
        RET
_ATOMIC_XOR64::
        DU8    0x48, 0x8B, 0x07   // MOV RAX, [RDI]
@@1:
        DU8    0x48, 0x89, 0xC1   // MOV RCX, RAX
        DU8    0x48, 0x31, 0xF1   // XOR RCX, RSI
        DU8    0xF0, 0x48, 0x0F, 0xB1, 0x0F   // LOCK CMPXCHG [RDI], RCX (RAX: what it was)
        JNZ    @@1
        RET
_ATOMIC_LOAD32::
        DU8    0x48, 0x63, 0x07   // MOVSXD RAX, DWORD [RDI]
        RET
_ATOMIC_STORE32::
        DU8    0x87, 0x37   // XCHG [RDI], ESI
        RET
_ATOMIC_ADD32::
        DU8    0x89, 0xF0   // MOV EAX, ESI
        DU8    0xF0, 0x0F, 0xC1, 0x07   // LOCK XADD [RDI], EAX
        DU8    0x48, 0x63, 0xC0   // MOVSXD RAX, EAX
        RET
_ATOMIC_SWAP32::
        DU8    0x89, 0xF0   // MOV EAX, ESI
        DU8    0x87, 0x07   // XCHG [RDI], EAX
        DU8    0x48, 0x63, 0xC0   // MOVSXD RAX, EAX
        RET
_ATOMIC_CAS32::
        DU8    0x89, 0xF0   // MOV EAX, ESI (expected)
        DU8    0xF0, 0x0F, 0xB1, 0x17   // LOCK CMPXCHG [RDI], EDX (desired)
        DU8    0x0F, 0x94, 0xC0   // SETE AL
        DU8    0x48, 0x0F, 0xB6, 0xC0   // MOVZX RAX, AL
        RET
_ATOMIC_AND32::
        DU8    0x8B, 0x07   // MOV EAX, [RDI]
@@1:
        DU8    0x89, 0xC1   // MOV ECX, EAX
        DU8    0x21, 0xF1   // AND ECX, ESI
        DU8    0xF0, 0x0F, 0xB1, 0x0F   // LOCK CMPXCHG [RDI], ECX (RAX: what it was)
        JNZ    @@1
        DU8    0x48, 0x63, 0xC0   // MOVSXD RAX, EAX
        RET
_ATOMIC_OR32::
        DU8    0x8B, 0x07   // MOV EAX, [RDI]
@@1:
        DU8    0x89, 0xC1   // MOV ECX, EAX
        DU8    0x09, 0xF1   // OR ECX, ESI
        DU8    0xF0, 0x0F, 0xB1, 0x0F   // LOCK CMPXCHG [RDI], ECX (RAX: what it was)
        JNZ    @@1
        DU8    0x48, 0x63, 0xC0   // MOVSXD RAX, EAX
        RET
_ATOMIC_XOR32::
        DU8    0x8B, 0x07   // MOV EAX, [RDI]
@@1:
        DU8    0x89, 0xC1   // MOV ECX, EAX
        DU8    0x31, 0xF1   // XOR ECX, ESI
        DU8    0xF0, 0x0F, 0xB1, 0x0F   // LOCK CMPXCHG [RDI], ECX (RAX: what it was)
        JNZ    @@1
        DU8    0x48, 0x63, 0xC0   // MOVSXD RAX, EAX
        RET
_ATOMIC_FENCE::
        DU8    0x0F, 0xAE, 0xF0   // MFENCE
        RET
}
#endif

#ifdef IS_ARM_64
/* X0 = p, X1 = v (cas: expected), X2 = desired; the result in X0
 * (32-bit results sign-extended); X9-X11 scratch */
asm {
_ATOMIC_LOAD64::
        DU8    0x09, 0xFC, 0xDF, 0xC8   // LDAR X9, [X0]
        MOV    X0, X9
        RET
_ATOMIC_STORE64::
        DU8    0x01, 0xFC, 0x9F, 0xC8   // STLR X1, [X0]
        RET
_ATOMIC_ADD64::
@@1:
        DU8    0x09, 0xFC, 0x5F, 0xC8   // LDAXR X9, [X0]
        ADD    X10, X9, X1
        DU8    0x0A, 0xFC, 0x0B, 0xC8   // STLXR W11, X10, [X0]
        CBNZ   W11, @@1
        MOV    X0, X9
        RET
_ATOMIC_SWAP64::
@@1:
        DU8    0x09, 0xFC, 0x5F, 0xC8   // LDAXR X9, [X0]
        DU8    0x01, 0xFC, 0x0B, 0xC8   // STLXR W11, X1, [X0]
        CBNZ   W11, @@1
        MOV    X0, X9
        RET
_ATOMIC_CAS64::
@@1:
        DU8    0x09, 0xFC, 0x5F, 0xC8   // LDAXR X9, [X0]
        CMP    X9, X1
        BNE    @@2
        DU8    0x02, 0xFC, 0x0B, 0xC8   // STLXR W11, X2, [X0]
        CBNZ   W11, @@1
        MOV    X0, 1
        RET
@@2:
        DU8    0x5F, 0x30, 0x03, 0xD5   // CLREX
        MOV    X0, 0
        RET
_ATOMIC_AND64::
@@1:
        DU8    0x09, 0xFC, 0x5F, 0xC8   // LDAXR X9, [X0]
        AND    X10, X9, X1
        DU8    0x0A, 0xFC, 0x0B, 0xC8   // STLXR W11, X10, [X0]
        CBNZ   W11, @@1
        MOV    X0, X9
        RET
_ATOMIC_OR64::
@@1:
        DU8    0x09, 0xFC, 0x5F, 0xC8   // LDAXR X9, [X0]
        ORR    X10, X9, X1
        DU8    0x0A, 0xFC, 0x0B, 0xC8   // STLXR W11, X10, [X0]
        CBNZ   W11, @@1
        MOV    X0, X9
        RET
_ATOMIC_XOR64::
@@1:
        DU8    0x09, 0xFC, 0x5F, 0xC8   // LDAXR X9, [X0]
        EOR    X10, X9, X1
        DU8    0x0A, 0xFC, 0x0B, 0xC8   // STLXR W11, X10, [X0]
        CBNZ   W11, @@1
        MOV    X0, X9
        RET
_ATOMIC_LOAD32::
        DU8    0x09, 0xFC, 0xDF, 0x88   // LDAR W9, [X0]
        SXTW   X0, W9
        RET
_ATOMIC_STORE32::
        DU8    0x01, 0xFC, 0x9F, 0x88   // STLR W1, [X0]
        RET
_ATOMIC_ADD32::
@@1:
        DU8    0x09, 0xFC, 0x5F, 0x88   // LDAXR W9, [X0]
        ADD    W10, W9, W1
        DU8    0x0A, 0xFC, 0x0B, 0x88   // STLXR W11, W10, [X0]
        CBNZ   W11, @@1
        SXTW   X0, W9
        RET
_ATOMIC_SWAP32::
@@1:
        DU8    0x09, 0xFC, 0x5F, 0x88   // LDAXR W9, [X0]
        DU8    0x01, 0xFC, 0x0B, 0x88   // STLXR W11, W1, [X0]
        CBNZ   W11, @@1
        SXTW   X0, W9
        RET
_ATOMIC_CAS32::
@@1:
        DU8    0x09, 0xFC, 0x5F, 0x88   // LDAXR W9, [X0]
        CMP    W9, W1
        BNE    @@2
        DU8    0x02, 0xFC, 0x0B, 0x88   // STLXR W11, W2, [X0]
        CBNZ   W11, @@1
        MOV    X0, 1
        RET
@@2:
        DU8    0x5F, 0x30, 0x03, 0xD5   // CLREX
        MOV    X0, 0
        RET
_ATOMIC_AND32::
@@1:
        DU8    0x09, 0xFC, 0x5F, 0x88   // LDAXR W9, [X0]
        AND    W10, W9, W1
        DU8    0x0A, 0xFC, 0x0B, 0x88   // STLXR W11, W10, [X0]
        CBNZ   W11, @@1
        SXTW   X0, W9
        RET
_ATOMIC_OR32::
@@1:
        DU8    0x09, 0xFC, 0x5F, 0x88   // LDAXR W9, [X0]
        ORR    W10, W9, W1
        DU8    0x0A, 0xFC, 0x0B, 0x88   // STLXR W11, W10, [X0]
        CBNZ   W11, @@1
        SXTW   X0, W9
        RET
_ATOMIC_XOR32::
@@1:
        DU8    0x09, 0xFC, 0x5F, 0x88   // LDAXR W9, [X0]
        EOR    W10, W9, W1
        DU8    0x0A, 0xFC, 0x0B, 0x88   // STLXR W11, W10, [X0]
        CBNZ   W11, @@1
        SXTW   X0, W9
        RET
_ATOMIC_FENCE::
        DMB    ISH
        RET
}
#endif

_extern _ATOMIC_LOAD64 i64 atomicLoad64(i64 *p);
_extern _ATOMIC_STORE64 void atomicStore64(i64 *p, i64 v);
_extern _ATOMIC_ADD64 i64 atomicAdd64(i64 *p, i64 v);
_extern _ATOMIC_SWAP64 i64 atomicSwap64(i64 *p, i64 v);
_extern _ATOMIC_CAS64 bool atomicCas64(i64 *p, i64 expected, i64 desired);
_extern _ATOMIC_AND64 i64 atomicAnd64(i64 *p, i64 v);
_extern _ATOMIC_OR64 i64 atomicOr64(i64 *p, i64 v);
_extern _ATOMIC_XOR64 i64 atomicXor64(i64 *p, i64 v);
_extern _ATOMIC_LOAD32 i32 atomicLoad32(i32 *p);
_extern _ATOMIC_STORE32 void atomicStore32(i32 *p, i32 v);
_extern _ATOMIC_ADD32 i32 atomicAdd32(i32 *p, i32 v);
_extern _ATOMIC_SWAP32 i32 atomicSwap32(i32 *p, i32 v);
_extern _ATOMIC_CAS32 bool atomicCas32(i32 *p, i32 expected, i32 desired);
_extern _ATOMIC_AND32 i32 atomicAnd32(i32 *p, i32 v);
_extern _ATOMIC_OR32 i32 atomicOr32(i32 *p, i32 v);
_extern _ATOMIC_XOR32 i32 atomicXor32(i32 *p, i32 v);
_extern _ATOMIC_FENCE void atomicFence();

/* load(p): the value at p. store(p, v): v at p. add(p, v), sub(p, v),
 * swap(p, v) (v at p), and, or, xor: the operation on the value at p,
 * returning the value from before it. cas(p, expected, desired): desired
 * at p if p holds expected, and whether it did. One set per width: */

/* The value at p */
pub i64 load(i64 *p) { return atomicLoad64(p); }
/* v stored at p */
pub void store(i64 *p, i64 v) { atomicStore64(p, v); }
/* v added to the value at p; returns the value from before */
pub i64 add(i64 *p, i64 v) { return atomicAdd64(p, v); }
/* v subtracted from the value at p; returns the value from before */
pub i64 sub(i64 *p, i64 v) { return atomicAdd64(p, (0 - v)); }
/* v stored at p; returns the value from before */
pub i64 swap(i64 *p, i64 v) { return atomicSwap64(p, v); }
/* The value at p ANDed with v; returns the value from before */
pub i64 and(i64 *p, i64 v) { return atomicAnd64(p, v); }
/* The value at p ORed with v; returns the value from before */
pub i64 or(i64 *p, i64 v) { return atomicOr64(p, v); }
/* The value at p XORed with v; returns the value from before */
pub i64 xor(i64 *p, i64 v) { return atomicXor64(p, v); }
/* desired stored at p if p holds expected; whether it did */
pub bool cas(i64 *p, i64 expected, i64 desired) { return atomicCas64(p, expected, desired); }

pub i32 load(i32 *p) { return atomicLoad32(p); }
pub void store(i32 *p, i64 v) { atomicStore32(p, (i32)v); }
pub i32 add(i32 *p, i64 v) { return atomicAdd32(p, (i32)v); }
pub i32 sub(i32 *p, i64 v) { return atomicAdd32(p, (i32)(0 - v)); }
pub i32 swap(i32 *p, i64 v) { return atomicSwap32(p, (i32)v); }
pub i32 and(i32 *p, i64 v) { return atomicAnd32(p, (i32)v); }
pub i32 or(i32 *p, i64 v) { return atomicOr32(p, (i32)v); }
pub i32 xor(i32 *p, i64 v) { return atomicXor32(p, (i32)v); }
pub bool cas(i32 *p, i64 expected, i64 desired) { return atomicCas32(p, (i32)expected, (i32)desired); }

pub u64 load(u64 *p) { return (u64)atomicLoad64((i64 *)p); }
pub void store(u64 *p, i64 v) { atomicStore64((i64 *)p, v); }
pub u64 add(u64 *p, i64 v) { return (u64)atomicAdd64((i64 *)p, v); }
pub u64 sub(u64 *p, i64 v) { return (u64)atomicAdd64((i64 *)p, (0 - v)); }
pub u64 swap(u64 *p, i64 v) { return (u64)atomicSwap64((i64 *)p, v); }
pub u64 and(u64 *p, i64 v) { return (u64)atomicAnd64((i64 *)p, v); }
pub u64 or(u64 *p, i64 v) { return (u64)atomicOr64((i64 *)p, v); }
pub u64 xor(u64 *p, i64 v) { return (u64)atomicXor64((i64 *)p, v); }
pub bool cas(u64 *p, i64 expected, i64 desired) { return atomicCas64((i64 *)p, expected, desired); }

pub u32 load(u32 *p) { return (u32)atomicLoad32((i32 *)p); }
pub void store(u32 *p, i64 v) { atomicStore32((i32 *)p, (i32)v); }
pub u32 add(u32 *p, i64 v) { return (u32)atomicAdd32((i32 *)p, (i32)v); }
pub u32 sub(u32 *p, i64 v) { return (u32)atomicAdd32((i32 *)p, (i32)(0 - v)); }
pub u32 swap(u32 *p, i64 v) { return (u32)atomicSwap32((i32 *)p, (i32)v); }
pub u32 and(u32 *p, i64 v) { return (u32)atomicAnd32((i32 *)p, (i32)v); }
pub u32 or(u32 *p, i64 v) { return (u32)atomicOr32((i32 *)p, (i32)v); }
pub u32 xor(u32 *p, i64 v) { return (u32)atomicXor32((i32 *)p, (i32)v); }
pub bool cas(u32 *p, i64 expected, i64 desired) { return atomicCas32((i32 *)p, (i32)expected, (i32)desired); }

/* A pointer read, written, swapped or compare-and-swapped atomically */
pub void *loadPtr(void **p) { return (void *)atomicLoad64((i64 *)p); }
pub void storePtr(void **p, void *v) { atomicStore64((i64 *)p, (i64)v); }
pub void *swapPtr(void **p, void *v) { return (void *)atomicSwap64((i64 *)p, (i64)v); }
pub bool casPtr(void **p, void *expected, void *desired) {
    return atomicCas64((i64 *)p, (i64)expected, (i64)desired);
}

/* A full memory barrier: no load or store moves across it */
pub void fence() { atomicFence(); }
