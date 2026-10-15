# Memory

Where values live, how to allocate memory that outlives a function, how to give it back, and the
habits that keep a program free of leaks. ktm has no garbage collector: memory you allocate is yours
until you free it.

## Three places a value lives

- **Locals** live on the stack, from their declaration to the end of their block. They cost nothing
  to create and are freed automatically.
- **Globals** and `static` locals live for the whole program.
- **Heap memory** lives from the moment you allocate it until you free it. Use it when a value must
  outlive the function that makes it, or when its size is known only at run time.

## Allocating and freeing

The [mem](../std/index.html#mem) module allocates, resizes and frees.

| Function | Does |
| --- | --- |
| `mem.alloc(size)` | Allocates `size` bytes, not zeroed |
| `mem.allocZero(size)` | Allocates `size` bytes, all zero |
| `mem.realloc(p, size)` | Resizes; `mem.realloc(null, n)` is `mem.alloc(n)` |
| `mem.free(p)` | Gives the memory back; `mem.free(null)` does nothing |
| `mem.size(p)` | The size that was asked for (0 for `null`) |
| `mem.copy(dst, src, n)` | Copies `n` bytes; returns `dst` |
| `mem.set(dst, byte, n)` | Fills `n` bytes with `byte`; returns `dst` |

```ktm run
import mem;

void main() {
    i64 n = 4;
    i64 *nums = mem.allocZero(n * sizeof(i64));
    nums[2] = 7;
    printf("%d %d, %d bytes\n", nums[0], nums[2], mem.size(nums));
    mem.free(nums);
}
```

```output
0 7, 32 bytes
```

The result of `mem.alloc` is a `void *`, which converts to any pointer type without a cast.

### An array of structs

```ktm run
import mem;

struct Player { u8 *name; i64 score; };

void main() {
    Player *team = mem.allocZero(3 * sizeof(Player));
    team[0].name = "ada";
    team[0].score = 10;
    team[2].name = "linus";
    for (i64 i = 0; i < 3; i++) {
        printf("%s: %d\n", team[i].name ? team[i].name : "(empty)", team[i].score);
    }
    mem.free(team);
}
```

```output
ada: 10
(empty): 0
linus: 0
```

### A value that outlives its function

A function that makes something on the heap and returns the pointer hands its ownership to the
caller, who frees it later.

```ktm run
import mem;

struct Counter { i64 value; };

Counter *counterNew(i64 start) {
    Counter *c = mem.alloc(sizeof(Counter));
    c.value = start;
    return c;
}

void main() {
    Counter *c = counterNew(5);
    c.value++;
    printf("%d\n", c.value);
    mem.free(c);
}
```

```output
6
```

## Growing

`mem.realloc` gives a block a new size and keeps its contents (as much as fits). It may move the
block, so always use the pointer it returns.

```ktm run
import mem;

void main() {
    i64 cap = 2, len = 0;
    i64 *items = mem.alloc(cap * sizeof(i64));
    for (i64 i = 1; i <= 5; i++) {
        if (len == cap) {
            cap *= 2;
            items = mem.realloc(items, cap * sizeof(i64));
        }
        items[len] = i * i;
        len++;
    }
    for (i64 i = 0; i < len; i++) {
        printf("%d ", items[i]);
    }
    printf("(capacity %d)\n", cap);
    mem.free(items);
}
```

```output
1 4 9 16 25 (capacity 8)
```

You rarely need to write this: [vec.Vec](../std/index.html#vec/vec.Vec) is a growable array that does
it for you.

```ktm run
import vec;

void main() {
    vec.Vec<i64> items = vec.new<i64>();
    for (i64 i = 1; i <= 5; i++) {
        items.push(i * i);
    }
    for (auto x : items) {
        printf("%d ", x);
    }
    printf("(%d items)\n", items.len);
    items.free();
}
```

```output
1 4 9 16 25 (5 items)
```

## Copying and filling

```ktm run
import mem;

void main() {
    u8 a[8], b[8];
    mem.set(a, 'x', 7);
    a[7] = 0;
    mem.copy(b, "abcdefg", 8);    // 7 letters and the NUL
    printf("%s %s\n", a, b);

    i64 src[3] = {1, 2, 3}, dst[3];
    mem.copy(dst, src, sizeof(src));
    printf("%d %d %d\n", dst[0], dst[1], dst[2]);
}
```

```output
xxxxxxx abcdefg
1 2 3
```

`mem.copy` copies front to back. For a source and a destination that overlap, use `libc.memmove`.

## Who frees what

The rule that keeps programs leak-free: every allocation has one owner, who frees it once. The
standard library says who owns what in each function's description; the usual cases are:

- a function that returns a new `String`, a `vec.Vec`, a `map.Map` or a pointer from `mem.alloc`
  gives it to you: you free it;
- a function that takes a `u8[]` or a pointer only reads it: you still own it;
- memory a container holds belongs to the container, and its `free()` releases it.

### Freeing at the end of a block

`defer` runs a statement when the block ends, however it ends: the end of the block, a `return`, a
`break`, or an error. Writing the free right after the allocation means it can't be forgotten.

```ktm run
import mem;

i64 sumFirst(i64 n) {
    i64 *nums = mem.alloc(n * sizeof(i64));
    defer mem.free(nums);
    for (i64 i = 0; i < n; i++) {
        nums[i] = i;
    }
    if (n > 3) {
        return -1;      // the defer still frees nums
    }
    i64 s = 0;
    for (i64 i = 0; i < n; i++) {
        s += nums[i];
    }
    return s;
}

void main() {
    printf("%d %d\n", sumFirst(3), sumFirst(10));
}
```

```output
3 -1
```

### ad

For a value with a `free()` method, `ad` in front of its declaration means "free it at the end of the
block". It is the shortest way to own something.

```ktm run
import str, vec;

void main() {
    ad String name = str.from("ada");
    ad vec.Vec<i64> nums = vec.new<i64>();
    nums.push(1);
    nums.push(2);
    name.append(" lovelace");
    printf("%s, %d numbers\n", name, nums.len);
}
```

```output
ada lovelace, 2 numbers
```

Your own types get `ad` by having a `free()` method:

```ktm run
import mem;

struct Grid { i64 w; i64 h; u8 *cells; };

Grid gridNew(i64 w, i64 h) {
    Grid g = {w, h, mem.allocZero(w * h)};
    return g;
}

void Grid.free() {
    printf("freeing %dx%d\n", self.w, self.h);
    mem.free(self.cells);
}

void Grid.set(i64 x, i64 y) {
    self.cells[y * self.w + x] = 1;
}

void main() {
    ad Grid g = gridNew(4, 3);
    g.set(1, 2);
    printf("%d %d\n", g.cells[2 * 4 + 1], g.cells[0]);
}
```

```output
1 0
freeing 4x3
```

[defer and ad](16-defer-and-ad.html) covers both in depth.

## Finding memory bugs

Run a program with `-jit -Memsafe` and ktm watches every allocation. Freeing a block twice, or
freeing something that was never allocated, is reported (and skipped, so the heap stays healthy) as
it happens; memory still allocated at exit is reported then, each with the line that allocated it.

```ktm run
import mem;

void main() {
    u8 *a = mem.alloc(16);
    u8 *b = mem.alloc(32);
    b[0] = 1;
    mem.free(a);
    printf("done\n");
}
```

```output
done
```

That program leaks `b`, which `-Memsafe` reports when it ends:

```sh
$ ktm -jit -Memsafe leaky.ktm
done
memsafe: 1 allocation(s) (32 bytes) never freed
  0x55d1c2a4f2e8        32 bytes  at leaky.ktm:5 in main
```

[Safety and debugging](29-safety-and-debugging.html) shows the other checks.

## Where it differs from C

- `mem.allocZero` takes one argument, the total size, unlike `calloc(count, size)`.
- The allocators return `null` when memory runs out, or for an absurd size.
- Each block keeps its size in a hidden header, so only pointers from `mem.alloc`, `mem.allocZero`
  and `mem.realloc` may go to `mem.free`, `mem.size` and `mem.realloc`. Memory from C's `malloc`
  goes back to C's `free`.
