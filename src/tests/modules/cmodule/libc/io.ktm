/* Test 438's fixture: a `libc` module, only C's declarations */
module libc;

pub extern "c" i64 write(i32 fd, u8 *buf, u64 n);
pub extern "c" u64 strlen(u8 *s);
pub const i32 STDOUT_FILENO = 1;
