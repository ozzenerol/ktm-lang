module mem;

/* The core memory functions are hand-written assembly on both x86_64 and
 * arm64 (memory.ktm). Each block from MAlloc, CAlloc and ReAlloc is
 * preceded by a hidden 8-byte header holding the size it was asked for,
 * so only these blocks can be passed to Free, MSize and ReAlloc, not
 * pointers from C's malloc. */

/* `size` uninitialised bytes; NULL when out of memory or when `size` is
 * too large (such as a negative number passed as a U64). */
public _extern _MALLOC U0 *MAlloc(U64 size);

/* Free a block from MAlloc, CAlloc or ReAlloc. NULL is ignored. */
public _extern _FREE U0 Free(U0 *ptr);

/* The size the block was asked for (not the capacity malloc rounded it up
 * to); 0 for NULL. */
public _extern _MSIZE U64 MSize(U0 *ptr);

/* Set `len` bytes at `dst` to the low byte of `ch`; returns `dst`. */
public _extern _MEMSET U0 *MemSet(U0 *dst, I32 ch, U64 len);

/* Copy `len` bytes from `src` to `dst`, front to back; returns `dst`.
 * Overlapping buffers are not supported: use memmove for those. */
public _extern _MEMCPY U0 *MemCpy(U0 *dst, U0 *src, U64 len);

/* `new_size` zeroed bytes. One argument, the total size, unlike C's
 * calloc(count, size). NULL when out of memory. */
public _extern _CALLOC U0 *CAlloc(U64 new_size);

/* A new block of `new_size` bytes holding the start of `ptr` (as much as
 * fits); `ptr` is freed and the result is always a different pointer.
 * ReAlloc(NULL, n) is MAlloc(n). When out of memory it returns NULL and
 * leaves `ptr` as it was, still yours to free. */
public _extern _REALLOC U0 *ReAlloc(U0 *ptr, U64 new_size);
