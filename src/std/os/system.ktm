module os;

/* The calling thread's C errno: the reason the last failing libc call
 * (socket, connect, read, ...) gave. Only meaningful right after a call
 * that returned its error value. */
public I32 Errno()
{
  return *ErrnoPtr();
}

/* Set the calling thread's C errno, e.g. to 0 before a call that only
 * reports errors through errno. */
public U0 SetErrno(I32 err)
{
  *ErrnoPtr() = err;
}
