module os;

/* POSIX file descriptor numbers */
pub const i64 STDIN = 0;

pub const i64 STDOUT = 1;

pub const i64 STDERR = 2;

#ifdef IS_X86_64
pub _extern _EXIT void exit(i64 exit_code = EXIT_FAIL);
#endif

#ifdef IS_ARM_64
pub void Exit(i64 exit_code = EXIT_FAIL);
#endif
