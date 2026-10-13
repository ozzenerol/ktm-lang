module libc;

pub extern "c" void exit(i32 status);

pub extern "c" i32 system(u8 *command);

/* exit's status for success and failure, as in C */
pub const i64 EXIT_SUCCESS = 0;
pub const i64 EXIT_FAILURE = 1;
