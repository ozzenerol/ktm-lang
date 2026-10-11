module mem;

/* The core memory functions are hand-written assembly on both x86_64 and
 * arm64 (memory.ktm). Each block from MAlloc, CAlloc and ReAlloc is
 * preceded by a hidden 8-byte header holding the size it was asked for,
 * so only these blocks can be passed to Free, MSize and ReAlloc, not
 * pointers from C's malloc. */

/* `size` uninitialised bytes; null when out of memory or when `size` is
 * too large (such as a negative number passed as a u64). */
pub void *alloc(u64 size);

/* Free a block from MAlloc, CAlloc or ReAlloc. null is ignored. */
pub void free(void *ptr);

/* The size the block was asked for (not the capacity malloc rounded it up
 * to); 0 for null. */
pub _extern _MSIZE u64 size(void *ptr);

/* Set `len` bytes at `dst` to the low byte of `ch`; returns `dst`. */
pub _extern _MEMSET void *set(void *dst, i32 ch, u64 len);

/* Copy `len` bytes from `src` to `dst`, front to back; returns `dst`.
 * Overlapping buffers are not supported: use memmove for those. */
pub _extern _MEMCPY void *copy(void *dst, void *src, u64 len);

/* `new_size` zeroed bytes. One argument, the total size, unlike C's
 * calloc(count, size). null when out of memory. */
pub void *allocZero(u64 newSize);

/* A new block of `new_size` bytes holding the start of `ptr` (as much as
 * fits); `ptr` is freed and the result is always a different pointer.
 * ReAlloc(null, n) is MAlloc(n). When out of memory it returns null and
 * leaves `ptr` as it was, still yours to free. */
pub void *realloc(void *ptr, u64 newSize);
