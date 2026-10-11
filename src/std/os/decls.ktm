module os;

/* POSIX file descriptor numbers */
const i64 STDIN = 0;

const i64 STDOUT = 1;

const i64 STDERR = 2;

#ifdef IS_X86_64
pub _extern _EXIT void exitProgram(i64 exit_code = EXIT_FAIL);
#endif

#ifdef IS_ARM_64
pub void Exit(i64 exit_code = EXIT_FAIL);
#endif
