module sort;

/* Sorting a slice in place. slice<T> takes cmp(a, b): negative when a
 * goes first, 0 when they are equal, positive when b goes first; asc<T>
 * sorts a slice of numbers (or anything `<` compares) smallest first.
 * Both are an introsort-free quicksort with insertion sort for short runs:
 * O(n log n) on random data, and the middle element as the pivot keeps
 * sorted and reversed input fast. Not stable. */

const i64 SMALL = 16;

void swap<T>(T *a, T *b) { T t = *a; *a = *b; *b = t; }

void sliceRange<T>(T *s, i64 lo, i64 hi, fn(T *, T *) i64 cmp) {
    while (hi - lo > SMALL) {
        i64 mid = lo + (hi - lo) / 2;
        swap(&s[mid], &s[hi]);
        i64 store = lo;
        for (i64 i = lo; i < hi; i++) {
            if (cmp(&s[i], &s[hi]) < 0) { swap(&s[i], &s[store]); store++; }
        }
        swap(&s[store], &s[hi]);
        /* recurse into the smaller half, loop on the larger: O(log n) stack */
        if (store - lo < hi - store) {
            sliceRange(s, lo, store - 1, cmp);
            lo = store + 1;
        } else {
            sliceRange(s, store + 1, hi, cmp);
            hi = store - 1;
        }
    }
    for (i64 i = lo + 1; i <= hi; i++) {
        for (i64 j = i; j > lo && cmp(&s[j], &s[j - 1]) < 0; j--) { swap(&s[j], &s[j - 1]); }
    }
}

/* s sorted in place by cmp(a, b): negative when a goes first, 0 when they
 * are equal, positive when b goes first. Not stable. */
pub void slice<T>(T[] s, fn(T *, T *) i64 cmp) {
    if (s.len > 1) { sliceRange(s.ptr, 0, s.len - 1, cmp); }
}

void ascRange<T>(T *s, i64 lo, i64 hi) {
    while (hi - lo > SMALL) {
        i64 mid = lo + (hi - lo) / 2;
        swap(&s[mid], &s[hi]);
        i64 store = lo;
        for (i64 i = lo; i < hi; i++) {
            if (s[i] < s[hi]) { swap(&s[i], &s[store]); store++; }
        }
        swap(&s[store], &s[hi]);
        if (store - lo < hi - store) {
            ascRange(s, lo, store - 1);
            lo = store + 1;
        } else {
            ascRange(s, store + 1, hi);
            hi = store - 1;
        }
    }
    for (i64 i = lo + 1; i <= hi; i++) {
        for (i64 j = i; j > lo && s[j] < s[j - 1]; j--) { swap(&s[j], &s[j - 1]); }
    }
}

/* s sorted in place, smallest first, by `<` (numbers, or anything `<`
 * compares). Not stable. */
pub void asc<T>(T[] s) {
    if (s.len > 1) { ascRange(s.ptr, 0, s.len - 1); }
}
