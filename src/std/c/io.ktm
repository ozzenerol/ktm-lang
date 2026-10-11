module c;

public extern "c" I8 *getcwd(U8 *buf, U64 size);

public extern "c" I8 *getenv(U8 *buf);

public extern "c" I32 isatty(I32 fd);
