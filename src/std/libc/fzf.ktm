module libc;

/* strtok keeps its position in one global shared by every thread */
pub extern "c" u8 *strtok_r(u8 *str, u8 *sep, u8 **saveptr);
