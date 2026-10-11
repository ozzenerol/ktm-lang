module c;

/* strtok keeps its position in one global shared by every thread */
extern "c" U8 *strtok_r(U8 *str, U8 *sep, U8 **saveptr);
