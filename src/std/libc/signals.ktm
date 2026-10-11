module libc;

pub extern "c" i32 sigaction(i32 sig, void *act, void *oldact);
