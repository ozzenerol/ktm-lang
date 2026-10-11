module rt;
import libc, os;

#ifdef IS_X86_64
asm {
_EXIT::
        CALL    exit
        RET
}

pub _extern _EXIT void Exit(i64 exit_code=EXIT_FAIL);
#endif

#ifdef IS_ARM_64
pub void os.exit(i64 exit_code=EXIT_FAIL) {
    libc.exit(exit_code);
}
#endif
