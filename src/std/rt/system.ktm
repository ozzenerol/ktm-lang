module rt;

#ifdef IS_X86_64
asm {
_EXIT::
        CALL    exit
        RET
}

pub _extern _EXIT void Exit(i64 exit_code=EXIT_FAIL);
#endif

#ifdef IS_ARM_64
pub void Exit(i64 exit_code=EXIT_FAIL) {
    exit(exit_code);
}
#endif
