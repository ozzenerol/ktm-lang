# Arrays and slices

A fixed-size array holds its elements in place. A slice `T[]` is a pointer and a length: a view of
some `T`s that knows how many there are, and checks every index. This chapter covers both, how they
convert into each other, and when to use which.

## Arrays

`T name[n]` is `n` values of type `T`, one after the other. Indexes start at 0.

```ktm run
void main() {
    i64 squares[5];
    for (i64 i = 0; i < 5; i++) {
        squares[i] = i * i;
    }
    for (i64 i = 0; i < 5; i++) {
        printf("%d ", squares[i]);
    }
    printf("\n%d bytes\n", sizeof(squares));
}
```

```output
0 1 4 9 16 
40 bytes
```

### Initialiser lists

A list in braces gives the first values; the rest are zero. Without a size, the list sets it.

```ktm run
i64 gCounts[4] = {1, 2};

void main() {
    i64 days[] = {31, 28, 31};
    f64 weights[3] = {0.5};
    printf("%d %d %d %d\n", gCounts[0], gCounts[1], gCounts[2], gCounts[3]);
    printf("%d days in %d months\n", days[0] + days[1] + days[2], sizeof(days) / sizeof(i64));
    printf("%.1f %.1f %.1f\n", weights[0], weights[1], weights[2]);
}
```

```output
1 2 0 0
90 days in 3 months
0.5 0.0 0.0
```

### Arrays of arrays

```ktm run
void main() {
    i64 grid[2][3] = {{1, 2, 3}, {4, 5, 6}};
    for (i64 r = 0; r < 2; r++) {
        for (i64 c = 0; c < 3; c++) {
            printf("%d ", grid[r][c]);
        }
        printf("\n");
    }
    i64 partial[2][3] = {{1}, {4, 5}};
    printf("%d %d %d | %d %d %d\n", partial[0][0], partial[0][1], partial[0][2],
           partial[1][0], partial[1][1], partial[1][2]);
}
```

```output
1 2 3 
4 5 6 
1 0 0 | 4 5 0
```

As in C, inner braces can be left out: the values fill the inner arrays (and structs) in order.

```ktm run
struct Point { i64 x; i64 y; };

Point gPoints[] = {1, 2, 3};      // (1, 2) and (3, 0)

void main() {
    i64 m[2][2] = {1, 2, 3};
    printf("%d %d %d %d\n", m[0][0], m[0][1], m[1][0], m[1][1]);
    printf("%d points: (%d %d) (%d %d)\n", sizeof(gPoints) / sizeof(Point),
           gPoints[0].x, gPoints[0].y, gPoints[1].x, gPoints[1].y);
}
```

```output
1 2 3 0
2 points: (1 2) (3 0)
```

### What arrays can't do

- An array can't be assigned as a whole: copy it with `mem.copy`, or use a slice.
- Array indexes are not checked. An index out of range reads or writes memory that isn't the
  array's. Slice indexes are checked, which is one reason to pass arrays as slices.
- An array's size is fixed when it is declared. For a list that grows, use
  [vec.Vec](../std/index.html#vec/vec.Vec).

```ktm error
void main() {
    i64 a[3] = {1, 2, 3};
    i64 b[3];
    b = a;
}
```

```output
error:
```

## Slices

A slice `T[]` is two things: a pointer to the first element, and a length. It is a view: making one
copies nothing.

```ktm run
void main() {
    i64 nums[6] = {1, 2, 3, 4, 5, 6};
    i64[] all = nums;
    i64[] mid = all[1:4];
    printf("%d %d | %d %d %d\n", all.len, mid.len, mid[0], mid[1], mid[2]);
    printf("%s, %d bytes\n", typeof(mid), sizeof(mid));
}
```

```output
6 3 | 2 3 4
i64[], 16 bytes
```

- An array converts to a slice of all its elements wherever a slice is expected.
- `s.len` is the length, `s.ptr` the pointer, `s[i]` an element.

### Sub-slices

`s[a:b]` is the elements from `a` up to, not including, `b`. Leave out `a` for the start and `b` for
the end.

```ktm run
void main() {
    i64 nums[] = {10, 20, 30, 40, 50};
    i64[] s = nums;
    i64[] head = s[:2];
    i64[] tail = s[3:];
    i64[] middle = s[1:4];
    printf("%d %d | %d %d | %d %d %d\n", head[0], head[1], tail[0], tail[1],
           middle[0], middle[1], middle[2]);
    printf("%d %d\n", s[2:2].len, s[:].len);
}
```

```output
10 20 | 40 50 | 20 30 40
0 5
```

### A view writes into what it views

A slice doesn't own its elements: writing through it writes the array (or whatever it views).

```ktm run
void main() {
    i64 nums[4] = {1, 2, 3, 4};
    i64[] tail = nums[2:];
    tail[0] = 30;
    printf("%d %d %d %d\n", nums[0], nums[1], nums[2], nums[3]);
}
```

```output
1 2 30 4
```

### A slice from a list

`T[] s = {...}` keeps the values in a hidden array that the slice views, for as long as the function
runs (for a global, the whole program).

```ktm run
void main() {
    i64[] xs = {4, 8, 15};
    u8[][] words = {"red", "green", "blue"};
    printf("%d %d %s %d\n", xs.len, xs[2], words[1], words.len);
}
```

```output
3 15 green 3
```

## Passing arrays as slices

A function that takes a slice accepts an array, a slice, or part of either. It always knows the
length, so it doesn't need a separate count.

```ktm run
i64 sum(i64[] xs) {
    i64 total = 0;
    for (auto x : xs) {
        total += x;
    }
    return total;
}

i64 maxOf(i64[] xs) {
    i64 best = xs[0];
    for (i64 i = 1; i < xs.len; i++) {
        if (xs[i] > best) { best = xs[i]; }
    }
    return best;
}

void main() {
    i64 nums[6] = {4, 8, 15, 16, 23, 42};
    printf("%d %d %d\n", sum(nums), sum(nums[:3]), maxOf(nums[1:4]));
}
```

```output
108 27 16
```

A function can change the elements of a slice it is given:

```ktm run
void doubleAll(i64[] xs) {
    for (i64 i = 0; i < xs.len; i++) {
        xs[i] *= 2;
    }
}

void main() {
    i64 nums[] = {1, 2, 3, 4};
    doubleAll(nums[1:3]);
    printf("%d %d %d %d\n", nums[0], nums[1], nums[2], nums[3]);
}
```

```output
1 4 6 4
```

## Every index is checked

Indexing a slice, and taking a sub-slice, is checked against the length, compiled and in the JIT.
Out of range throws `ERR_BOUNDS`, an [error](15-errors.html) you can catch; uncaught, it stops the
program and says where.

```ktm run
void main() {
    i64 nums[3] = {1, 2, 3};
    i64[] s = nums;
    try {
        printf("%d\n", s[3]);
    } catch (Error e) {
        printf("caught: %s\n", e.message);
    }
    try {
        i64[] t = s[2:5];
    } catch (Error e) {
        printf("caught: %s\n", e.message);
    }
}
```

```output
caught: index 3 out of range for length 3
caught: slice 2:5 out of range for length 3
```

- A check costs one comparison and a branch.
- The compiler leaves it out where it can't fail: `s[i]` in `for (i64 i = 0; i < s.len; i++)` when
  the body changes neither `i` nor `s`.
- `s.ptr[i]` is the unchecked way, for code that has checked its range itself.

## Slices and pointers

`p[0:n]` turns a raw pointer into a slice of `n` elements. It can't be checked: you vouch for `n`.
The other way, `s.ptr` is the pointer.

```ktm run
import mem;

void main() {
    i64 *raw = mem.allocZero(4 * sizeof(i64));
    i64[] s = raw[0:4];
    s[3] = 9;
    printf("%d %d %d\n", s.len, raw[3], s.ptr == raw);
    mem.free(raw);
}
```

```output
4 9 1
```

## Text is a u8[]

A string literal converts to a `u8[]` of its bytes, without the NUL at the end. So does a `String`.
Most functions that read text take a `u8[]`.

```ktm run
void main() {
    u8[] word = "hello";
    printf("%s %d %c %s\n", word, word.len, word[1], word[1:4]);
}
```

```output
hello 5 e ell
```

A `u8` buffer is all of its bytes: a `u8 buf[64]` passed as a `u8[]` has length 64, not the length
of the text in it. `cstr.view(buf)` is the text up to its NUL.

```ktm run
import cstr;

void main() {
    u8 buf[64];
    cstr.copy(buf, "hi");
    u8[] whole = buf;
    printf("%d %d %d\n", whole.len, cstr.view(buf).len, buf[0:2].len);
}
```

```output
64 2 2
```

[Strings](11-strings.html) covers text in depth.

## Comparing and freeing slices

- `a == b` on slices is an error. Compare text with `a.equals(b)`, and identity with
  `a.ptr == b.ptr`.
- A slice a function allocated for you, like the parts `str.split` returns, is yours: free it with
  `s.free()`.
- A slice of an array, or of memory someone else owns, isn't yours to free.
- `s.len` and `s.ptr` are read-only: to change them, assign a whole new slice.

```ktm run
import str;

void main() {
    u8[] a = "abc";
    u8 buf[3] = {'a', 'b', 'c'};
    u8[] b = buf;
    printf("%d %d\n", a.equals(b), a.ptr == b.ptr);
    u8[][] parts = str.split("x,y,z", ",");
    printf("%d parts, the last is %s\n", parts.len, parts[2]);
    parts.free();
}
```

```output
1 0
3 parts, the last is z
```

## Slices of anything

Slices of structs, slices as fields, and slices of slices all work.

```ktm run
struct User { u8[] name; i64[] scores; };

i64 total(i64[] xs) {
    i64 t = 0;
    for (auto x : xs) { t += x; }
    return t;
}

void main() {
    i64 a[] = {3, 4};
    i64 b[] = {10};
    User users[2] = {{"ann", a}, {"bob", b}};
    User[] all = users;
    for (auto u : all) {
        printf("%s: %d\n", u.name, total(u.scores));
    }
}
```

```output
ann: 7
bob: 10
```

## Arrays or slices?

- Use an **array** for a fixed number of values that live in one place: a buffer, a small table.
- Use a **slice** for parameters and for parts of something: it carries its length and every index
  is checked.
- Use a [vec.Vec](../std/index.html#vec/vec.Vec) for a list that grows. `items.data[0:items.len]` is a
  slice of its elements, and a range-based `for` walks it.
