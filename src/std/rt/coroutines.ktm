module rt;
import libc;

/* A released stack in its thread's cache (Fs()->coro_cache). It lives
 * in the top bytes of the stack itself, so caching allocates nothing. */
pub struct CoroCached;

pub struct CoroCached {
    CoroCached *next;
    i64 len;              /* bytes mapped, guard included */
    bool trimmed;         /* its pages below the top were given back */
};

/* Unmap a thread's cached stacks: FsFree calls it when the thread ends. */
pub void coroStacksFree(HCFs *fs) {
    CoroCached *n = fs.coroCache, *next;
    while (n) {
        next = n.next;
        libc.munmap((u8 *)n + sizeof(CoroCached) - n.len, n.len);
        n = next;
    }
    fs.coroCache = null;
    fs.coroCached = 0;
}
