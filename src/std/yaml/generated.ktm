module yaml;
import mem;

/* What the methods @yaml generates call (v0.14.0): not for programs */

/* k is the n bytes at s */
pub bool __keyIs(u8[] k, u8 *s, i64 n)
{
    if (k.len != n) { return false; }
    for (i64 i = 0; i < n; i++) {
        if (k.ptr[i] != s[i]) { return false; }
    }
    return true;
}

/* n zeroed bytes */
pub void *__alloc(i64 n)
{
    void *p = mem.alloc(n);
    mem.set(p, 0, n);
    return p;
}

/* p (or null) grown to n bytes */
pub void *__grow(void *p, i64 n)
{
    return mem.realloc(p, n);
}

pub void __free(void *p)
{
    if (p) { mem.free(p); }
}

pub void __zero(void *p, i64 n)
{
    mem.set(p, 0, n);
}

/* A string value into the u8 array at dst (cap bytes, NUL included) */
pub void __intoChars(Reader *r, u8 *dst, i64 cap)
{
    u8[] s = r.readStr();
    if (s.len >= cap) {
        r.fail("too long for its array");
    }
    mem.copy(dst, s.ptr, s.len);
    dst[s.len] = 0;
}
