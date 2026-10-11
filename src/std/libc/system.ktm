module libc;

pub extern "c" void exit(i32 status);

pub extern "c" i32 system(u8 *command);
