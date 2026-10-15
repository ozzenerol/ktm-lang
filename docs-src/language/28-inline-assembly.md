# Inline assembly

An `asm { }` block holds machine instructions, assembled by ktm itself for x86_64 or arm64. The
routines it defines are called from ktm like any function. This chapter covers writing asm
routines, binding them to ktm prototypes with `_extern`, how arguments and results travel,
labels and loops, a function whose whole body is assembly, writing instructions as raw bytes,
supporting both processors, and the errors.

Assembly is for the few places ktm can't reach: a special instruction, an exact sequence for
performance, switching stacks. Everything else is clearer, safer and portable in ktm.

## A first routine

An `asm` block at the top level defines routines, each starting at a label followed by `::`.
`_extern LABEL prototype;` gives one a ktm name and type, and from then on it is called like a
function:

```ktm run
#ifdef IS_X86_64
asm {
_ADD_ONE::
    MOV RAX, RDI
    INC RAX
    RET
}
#endif

#ifdef IS_ARM_64
asm {
_ADD_ONE::
    ADD X0, X0, 1
    RET
}
#endif

_extern _ADD_ONE i64 addOne(i64 x);

i32 main() {
    printf("%d\n", addOne(41));
    return 0;
}
```

```output
42
```

- A routine runs exactly the instructions written: there is no prologue or epilogue, so it must
  end with `RET`.
- The block is assembled for the processor the program is compiled for, so code for each one
  goes under `#ifdef IS_X86_64` and `#ifdef IS_ARM_64` (see
  [The preprocessor](25-preprocessor.html)).
- Instructions and registers are written in upper case, Intel-style operand order on x86_64
  (destination first), standard arm64 syntax on arm64.

## Arguments and results

Arguments arrive, and the result leaves, where the platform's C calling convention puts them:

| | Integer and pointer arguments | Float arguments | Result |
| --- | --- | --- | --- |
| x86_64 | `RDI`, `RSI`, `RDX`, `RCX`, `R8`, `R9` | `XMM0` to `XMM7` | `RAX`, or `XMM0` for a float |
| arm64 | `X0` to `X7` | `D0` to `D7` | `X0`, or `D0` for a float |

Two arguments, and a conditional move:

```ktm run
#ifdef IS_X86_64
asm {
_MAX2::
    MOV RAX, RDI
    CMP RDI, RSI
    CMOVL RAX, RSI
    RET
}
#endif

#ifdef IS_ARM_64
asm {
_MAX2::
    CMP X0, X1
    CSEL X0, X0, X1, GT
    RET
}
#endif

_extern _MAX2 i64 max2(i64 a, i64 b);

i32 main() {
    printf("%d %d\n", max2(3, 9), max2(9, -3));
    return 0;
}
```

```output
9 9
```

Floats go through the floating-point registers:

```ktm run
#ifdef IS_X86_64
asm {
_FMUL::
    MULSD XMM0, XMM1
    RET
}
#endif

#ifdef IS_ARM_64
asm {
_FMUL::
    FMUL D0, D0, D1
    RET
}
#endif

_extern _FMUL f64 fmul(f64 a, f64 b);

i32 main() {
    printf("%.2f\n", fmul(1.5, 3.0));
    return 0;
}
```

```output
4.50
```

A pointer argument is an address in a register; memory is read through it. Here the second
field of a struct, 8 bytes in:

```ktm run
struct Pair {
    i64 first;
    i64 second;
};

#ifdef IS_X86_64
asm {
_SECOND::
    MOV RAX, [RDI+8]
    RET
}
#endif

#ifdef IS_ARM_64
asm {
_SECOND::
    LDR X0, [X0, 8]
    RET
}
#endif

_extern _SECOND i64 second(Pair *p);

i32 main() {
    Pair p = {3, 9};
    printf("%d\n", second(&p));
    return 0;
}
```

```output
9
```

The prototype is a promise: ktm checks calls against it, but not against what the instructions
really do. A routine that reads a register the convention doesn't give it, or forgets to put the
result where it belongs, returns garbage.

## Labels and loops

Inside a routine, `@@1:`, `@@2:` ... are local labels, for jumps within the routine; each routine
has its own. A loop that adds `n + (n - 1) + ... + 1`:

```ktm run
#ifdef IS_X86_64
asm {
_SUM_TO::
    XOR RAX, RAX
@@1:
    ADD RAX, RDI
    DEC RDI
    JNZ @@1
    RET
}
#endif

#ifdef IS_ARM_64
asm {
_SUM_TO::
    MOV X1, 0
@@1:
    ADD X1, X1, X0
    SUB X0, X0, 1
    CBNZ X0, @@1
    MOV X0, X1
    RET
}
#endif

_extern _SUM_TO i64 sumTo(i64 n);

i32 main() {
    printf("%d %d\n", sumTo(10), sumTo(100));
    return 0;
}
```

```output
55 5050
```

One block can define several routines, one after another; each `NAME::` starts the next.

## A whole function in assembly

A ktm function whose body is an `asm` block is a naked assembly function: its parameters arrive in
the registers above, and its body is the instructions, with no code added around them. It needs
no `_extern`:

```ktm run
i64 addThree(i64 x) {
#ifdef IS_X86_64
    asm {
        MOV RAX, RDI
        ADD RAX, 3
        RET
    }
#endif
#ifdef IS_ARM_64
    asm {
        ADD X0, X0, 3
        RET
    }
#endif
}

i32 main() {
    printf("%d\n", addThree(4));
    return 0;
}
```

```output
7
```

The block is the whole body: ktm code can't come before or after it, and the function must `RET`
itself.

## Macros and constants

The block is read after the preprocessor, so `#define`d names, character constants and
`sizeof(Type)` can be used as numbers:

```ktm run
#define STEP 5

struct Point { i64 x, y; };

#ifdef IS_X86_64
asm {
_ADD_STEP::
    MOV RAX, RDI
    ADD RAX, STEP
    ADD RAX, sizeof(Point)
    RET
}
#endif

#ifdef IS_ARM_64
asm {
_ADD_STEP::
    ADD X0, X0, STEP
    ADD X0, X0, sizeof(Point)
    RET
}
#endif

_extern _ADD_STEP i64 addStep(i64 x);

i32 main() {
    printf("%d\n", addStep(1));
    return 0;
}
```

```output
22
```

## Instructions as bytes

The assembler knows the common instructions: moves, arithmetic, logic, shifts, compares, jumps
and conditional moves, calls, the SSE and x87 float instructions on x86_64, and loads, stores,
branches, `CSEL`, barriers and float instructions on arm64. For anything else, `DU8` writes bytes
straight into the routine, with a comment saying what they are:

```ktm run
#ifdef IS_X86_64
asm {
_NEXT::
    DU8 0x48, 0x8D, 0x47, 0x01      // LEA RAX, [RDI+1]
    RET
}
#endif

#ifdef IS_ARM_64
asm {
_NEXT::
    DU8 0x00, 0x04, 0x00, 0x91      // ADD X0, X0, 1
    RET
}
#endif

_extern _NEXT i64 next(i64 x);

i32 main() {
    printf("%d\n", next(77));
    return 0;
}
```

```output
78
```

An arm64 instruction is 4 bytes, written in little-endian order. Take the bytes from another
assembler's listing (`objdump -d`). The library's `atomic` module writes its locked instructions
this way.

## In a module

A module's `asm` blocks and `_extern` bindings belong to the module: a binding is the module's
name, `pub` or private like any declaration (see
[Modules and packages](22-modules-and-packages.html)). This is how the standard library's `mem`,
`math`, `atomic` and `coro` modules use assembly.

```ktm
module fastmath;

#ifdef IS_X86_64
asm {
_FM_TWICE::
    MOV RAX, RDI
    ADD RAX, RDI
    RET
}
#endif

pub _extern _FM_TWICE i64 twice(i64 x);      // fastmath.twice for importers
```

## Errors

The assembler checks each instruction while the program is compiled, and reports an error at the
instruction that has it (here line 4, `MOV RAX`, on x86_64):

```ktm error
#ifdef IS_X86_64
asm {
_BAD::
    MOV RAX
    RET
}
#endif

#ifdef IS_ARM_64
asm {
_BAD::
    MOV X0
    RET
}
#endif

i32 main() {
    return 0;
}
```

```output
needs 2 operands
```

A function containing an `asm` block that doesn't end in `RET` gets a warning: execution would run
off its end.

## Rules

- `asm { NAME:: instructions }` at the top level defines routines; `_extern NAME prototype;`
  binds one to a ktm name. A function whose body is an `asm` block is itself the routine.
- Routines get arguments and return results by the C calling convention, and must `RET`; nothing
  is added around them.
- `@@N:` labels are local to their routine.
- `#define`s, character constants and `sizeof(T)` work inside a block; `DU8` writes raw bytes.
- Write a block per processor under `#ifdef IS_X86_64` / `#ifdef IS_ARM_64`.
