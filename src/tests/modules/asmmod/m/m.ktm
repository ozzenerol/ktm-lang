/* Test 441's fixture: asm blocks and _extern bindings in a module */
module m;
#ifdef IS_X86_64
asm {
_DOUBLE_IT::
    MOV RAX, RDI
    ADD RAX, RDI
    RET
}
#else
asm {
_DOUBLE_IT::
    ADD X0, X0, X0
    RET
}
#endif
pub _extern _DOUBLE_IT i64 twice(i64 x);
_extern _DOUBLE_IT i64 hidden(i64 x);
pub i64 four(i64 x) { return hidden(twice(x)); }
