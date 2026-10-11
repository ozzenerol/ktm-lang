module proc;

/* Processes (process.ktm): run a program by argv, without a shell (no
 * quoting, nothing in an argument is interpreted), and get its exit
 * status. Each of the child's stdin, stdout and stderr is: */
pub const i64 PROC_INHERIT = 0;  /* ours (the default) */

pub const i64 PROC_PIPE = 1;  /* a pipe; our end is Proc in_fd / out_fd / err_fd */

pub const i64 PROC_NULL = 2;  /* /dev/null */

pub const i64 PROC_STDOUT = 3;  /* stderr only: wherever its stdout goes (2>&1) */
