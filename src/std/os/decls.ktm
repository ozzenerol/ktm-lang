module os;

/* POSIX file descriptor numbers */
#define STDIN  0

#define STDOUT 1

#define STDERR 2

#ifdef IS_X86_64
public _extern _EXIT U0 Exit(I64 exit_code = EXIT_FAIL);
#endif

#ifdef IS_ARM_64
public U0 Exit(I64 exit_code = EXIT_FAIL);
#endif
