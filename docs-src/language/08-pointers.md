# Pointers

A pointer holds the address of a value. This chapter covers taking addresses, reading and writing
through pointers, pointers to structs, `null`, pointer arithmetic, and the conversions between
pointer types. For a pointer that knows its length, see [Arrays and slices](10-arrays-and-slices.html).

## Address and dereference

`&x` is the address of `x`; its type is `T *` for an `x` of type `T`. `*p` is the value `p` points
to, for reading or writing.

```ktm run
void main() {
    i64 x = 5;
    i64 *p = &x;
    printf("%d\n", *p);
    *p = 10;
    printf("%d %d\n", x, *p);
}
```

```output
5
10 10
```

Two pointers to the same variable see the same value:

```ktm run
void main() {
    i64 score = 1;
    i64 *a = &score;
    i64 *b = &score;
    *a += 10;
    *b *= 2;
    printf("%d %d\n", score, a == b);
}
```

```output
22 1
```

A pointer to a pointer works the same way, one level at a time:

```ktm run
void main() {
    i64 x = 1;
    i64 *p = &x;
    i64 **pp = &p;
    **pp = 20;
    printf("%d\n", x);
}
```

```output
20
```

## Pointers as parameters

Arguments are copies, so a function that should change a caller's variable takes its address.

```ktm run
void swap(i64 *a, i64 *b) {
    i64 t = *a;
    *a = *b;
    *b = t;
}

void main() {
    i64 x = 1, y = 2;
    swap(&x, &y);
    printf("%d %d\n", x, y);
}
```

```output
2 1
```

It is also how a function reads a big struct without copying it:

```ktm run
struct Stats { i64 count; i64 sum; i64 min; i64 max; };

void add(Stats *s, i64 v) {
    if (s.count == 0 || v < s.min) { s.min = v; }
    if (s.count == 0 || v > s.max) { s.max = v; }
    s.count++;
    s.sum += v;
}

void main() {
    Stats s;
    i64 values[] = {7, 3, 9, 4};
    for (auto v : values) {
        add(&s, v);
    }
    printf("%d values, sum %d, %d..%d\n", s.count, s.sum, s.min, s.max);
}
```

```output
4 values, sum 23, 3..9
```

## Pointers to structs

`.` works through a pointer: on a `Point *p`, `p.x` is the field `x` of the struct `p` points to.
C's `p->x` means the same and works too.

```ktm run
struct Point { i64 x; i64 y; };

void main() {
    Point a = {1, 2};
    Point *p = &a;
    p.x = 11;
    p->y = 22;
    printf("%d %d %d %d\n", a.x, a.y, p.x, (*p).y);
}
```

```output
11 22 11 22
```

The automatic dereference goes one level only. On a `Point **pp`, write `(*pp).x`:

```ktm run
struct Point { i64 x; i64 y; };

void reset(Point **pp) {
    (*pp).x = 0;
}

void main() {
    Point a = {5, 6};
    Point *p = &a;
    reset(&p);
    printf("%d %d\n", a.x, a.y);
}
```

```output
0 6
```

## null

`null` is the pointer to nothing. A pointer declared without a value starts as `null`, and a pointer
is false in a condition when it is `null`.

```ktm run
struct Node { i64 value; Node *next; };

void main() {
    Node c = {3, null};
    Node b = {2, &c};
    Node a = {1, &b};
    for (Node *n = &a; n; n = n.next) {
        printf("%d ", n.value);
    }
    printf("\n");
}
```

```output
1 2 3 
```

Reading or writing through `null` stops the program with a crash report that names the function and
line:

```ktm run
struct Point { i64 x; i64 y; };

i64 getX(Point *p) {
    return p.x;
}

void main() {
    Point *nothing = null;
    printf("before\n");
    printf("%d\n", getX(nothing));
}
```

```output
before
crash: null pointer dereference (address 0x0)
  at example.ktm:4 in getX (inlined)
  called from example.ktm:10 in main
```

`(inlined)` says the compiler copied `getX` into `main` instead of calling it; the report still shows
it as its own step. [Safety and debugging](29-safety-and-debugging.html) covers crash reports in full.

## Pointer arithmetic

Adding `n` to a pointer moves it `n` elements forward (not `n` bytes). `p[i]` is `*(p + i)`.
Subtracting two pointers gives the number of elements between them.

```ktm run
void main() {
    i64 nums[] = {10, 20, 30, 40};
    i64 *p = nums;
    printf("%d %d %d\n", *p, *(p + 2), p[3]);
    i64 *last = &nums[3];
    printf("%d elements apart\n", last - p);
}
```

```output
10 30 40
3 elements apart
```

Walking a C string with a pointer:

```ktm run
void main() {
    u8 *s = "hello";
    u8 *end = s;
    while (*end) {
        end++;
    }
    printf("%d bytes, the last is %c\n", end - s, *(end - 1));
}
```

```output
5 bytes, the last is o
```

- `n + p` is the same as `p + n`; `n - p` is an error.
- `++` and `--` on a pointer move it one element.
- A pointer compares with another pointer, or with `null`.

## Converting between pointer types

- `void *` and the byte pointers (`u8 *`, `i8 *`, `bool *`) convert to and from any pointer without
  a cast.
- A pointer to a struct converts to a pointer to the struct it extends (see
  [Structs](12-structs-and-unions.html)).
- Any other conversion between pointer types needs a cast.

```ktm run
void main() {
    f64 f = 1.5;
    void *any = &f;
    f64 *back = any;
    u8 *bytes = &f;
    i64 *bits = (i64 *)&f;
    printf("%.1f %d %x\n", *back, bytes[7], *bits);
}
```

```output
1.5 63 3ff8000000000000
```

A `void *` is how a function takes "a pointer to anything", together with what it needs to know about
it:

```ktm run
void dump(void *data, i64 size) {
    u8 *b = data;
    for (i64 i = 0; i < size; i++) {
        printf("%02x ", b[i]);
    }
    printf("\n");
}

void main() {
    i32 n = 0x11223344;
    u16 small = 0xABCD;
    dump(&n, sizeof(n));
    dump(&small, sizeof(small));
}
```

```output
44 33 22 11 
cd ab 
```

## Pointers that outlive their target

A local lives until its function returns. A pointer to it must not be used after that: the memory
is reused by the next call. Return the value itself, or memory from the heap
([Memory](09-memory.html)).

```ktm run
i64 *wrong() {
    i64 local = 42;
    return &local;     // the address of a local that ends here
}

i64 right() {
    i64 local = 42;
    return local;      // a copy of the value
}

void main() {
    printf("%d\n", right());
}
```

```output
42
```
