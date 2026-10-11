module libc;

pub extern "c" i8 *getcwd(u8 *buf, u64 size);

pub extern "c" i8 *getenv(u8 *buf);

pub extern "c" i32 isatty(i32 fd);
