# Control flow

`if` and `else`, the three loops, the range-based `for`, `break` and `continue`, and `goto`.
[switch](06-switch.html) has its own chapter.

## Braces are required

The body of `if`, `else`, `for`, `while` and `do` is always a block in braces, even when it is one
statement. `else if` is the one exception. This rules out a whole class of bugs where a second line
looks guarded but isn't.

```ktm error
void main() {
    i64 x = 1;
    if (x) x = 2;
}
```

```output
braces required: the body of `if` goes in `{ ... }`
```

## Parentheses are optional

The condition can be written in parentheses, as in C, or without them, as in Go. Both mean the
same, and one program can mix them. Without parentheses, the condition runs up to the `{`.

```ktm run
void main() {
    i64 a = 1, b = 2;
    if (a < b) {
        printf("with parentheses\n");
    }
    if a < b {
        printf("without them\n");
    }
    if (a + b) * 2 > 5 {
        printf("a condition can start with a parenthesis of its own\n");
    }
}
```

```output
with parentheses
without them
a condition can start with a parenthesis of its own
```

The rest of this chapter uses parentheses; every example works without them too.

## if and else

```ktm run
void classify(i64 n) {
    if (n < 0) {
        printf("%d is negative\n", n);
    } else if (n == 0) {
        printf("%d is zero\n", n);
    } else if (n < 10) {
        printf("%d is small\n", n);
    } else {
        printf("%d is large\n", n);
    }
}

void main() {
    classify(-5);
    classify(0);
    classify(7);
    classify(100);
}
```

```output
-5 is negative
0 is zero
7 is small
100 is large
```

The condition is any number or pointer: zero and `null` are false, everything else true.

```ktm run
void main() {
    u8 *name = "ktm";
    i64 count = 0;
    if (name) {
        printf("has a name\n");
    }
    if (!count) {
        printf("count is zero\n");
    }
}
```

```output
has a name
count is zero
```

### A declaration in the if

A declaration can come before the condition, separated by `;`. It is visible in the `if` and in its
`else` branches, and nowhere else.

```ktm run
i64 lookup(u8 *key) { return key[0] == 'a' ? 42 : -1; }

void main() {
    if (i64 v = lookup("apple"); v >= 0) {
        printf("found %d\n", v);
    } else {
        printf("missing\n");
    }
    if (i64 v = lookup("kiwi"); v >= 0) {
        printf("found %d\n", v);
    } else {
        printf("missing (v is %d)\n", v);
    }
}
```

```output
found 42
missing (v is -1)
```

A declaration alone is a condition too: the variable's value is tested.

```ktm run
u8 *findUser(i64 id) { return id == 1 ? "ada" : null; }

void main() {
    if (u8 *name = findUser(1)) {
        printf("user 1 is %s\n", name);
    }
    if (u8 *name = findUser(2)) {
        printf("user 2 is %s\n", name);
    } else {
        printf("no user 2\n");
    }
}
```

```output
user 1 is ada
no user 2
```

## while

`while` runs its body as long as the condition is true. The condition is tested before each pass, so
the body may not run at all.

```ktm run
void main() {
    i64 n = 27, steps = 0;
    while (n != 1) {
        n = n % 2 == 0 ? n / 2 : 3 * n + 1;
        steps++;
    }
    printf("27 reaches 1 in %d steps\n", steps);

    i64 never = 0;
    while (never > 0) {
        printf("not printed\n");
    }
}
```

```output
27 reaches 1 in 111 steps
```

## do ... while

`do` tests the condition after each pass, so the body runs at least once. The `while` at the end
takes a `;`.

```ktm run
void main() {
    i64 n = 0;
    do {
        printf("pass %d\n", n);
        n++;
    } while (n < 3);

    i64 big = 100;
    do {
        printf("runs once, even though %d >= 3\n", big);
    } while (big < 3);
}
```

```output
pass 0
pass 1
pass 2
runs once, even though 100 >= 3
```

## for

The classic three-part `for`: a start, a condition tested before each pass, and a step run after
each pass. A variable declared in the start belongs to the loop.

```ktm run
void main() {
    for (i64 i = 0; i < 5; i++) {
        printf("%d ", i);
    }
    printf("\n");
    for (i64 i = 10; i > 0; i -= 3) {
        printf("%d ", i);
    }
    printf("\n");
}
```

```output
0 1 2 3 4 
10 7 4 1 
```

Each part can be left out. Several variables and several steps are separated by commas.

```ktm run
void main() {
    for (i64 lo = 0, hi = 9; lo < hi; lo++, hi--) {
        printf("(%d,%d) ", lo, hi);
    }
    printf("\n");

    i64 k = 0;
    for (; k < 3;) {
        k++;
    }
    printf("k = %d\n", k);
}
```

```output
(0,9) (1,8) (2,7) (3,6) (4,5) 
k = 3
```

Without parentheses, `for` also has Go's two short forms: `for cond { }` is a `while`, and `for { }`
loops until something leaves it.

```ktm run
void main() {
    i64 n = 0;
    for n < 3 {
        n++;
    }
    for {
        n++;
        if n == 10 { break; }
    }
    printf("%d\n", n);
}
```

```output
10
```

## break and continue

`break` leaves the innermost loop; `continue` skips to its next pass (in a `for`, the step still
runs).

```ktm run
void main() {
    for (i64 i = 0; i < 10; i++) {
        if (i == 2) { continue; }
        if (i == 6) { break; }
        printf("%d ", i);
    }
    printf("\n");
}
```

```output
0 1 3 4 5 
```

In nested loops, they act on the innermost one:

```ktm run
void main() {
    for (i64 row = 0; row < 3; row++) {
        for (i64 col = 0; col < 3; col++) {
            if (col > row) { break; }
            printf("(%d,%d)", row, col);
        }
    }
    printf("\n");
}
```

```output
(0,0)(1,0)(1,1)(2,0)(2,1)(2,2)
```

To leave several loops at once, return from a function, or use `goto` (below).

```ktm run
bool findPair(i64 *nums, i64 n, i64 target, i64 *a, i64 *b) {
    for (i64 i = 0; i < n; i++) {
        for (i64 j = i + 1; j < n; j++) {
            if (nums[i] + nums[j] == target) {
                *a = i;
                *b = j;
                return true;
            }
        }
    }
    return false;
}

void main() {
    i64 nums[] = {3, 8, 11, 4};
    i64 a, b;
    if (findPair(nums, 4, 12, &a, &b)) {
        printf("nums[%d] + nums[%d] = 12\n", a, b);
    }
}
```

```output
nums[1] + nums[3] = 12
```

## The range-based for

`for (T x : items)` runs once per element, in order. It works on:

- fixed-size arrays,
- slices (`T[]`), `String`s included,
- any struct with `data` and `len` fields (or `entries` and `size`), such as
  [vec.Vec](../std/index.html#vec/vec.Vec).

```ktm run
import vec;

void main() {
    i64 primes[] = {2, 3, 5, 7, 11};
    i64 sum = 0;
    for (auto p : primes) {
        sum += p;
    }
    printf("sum = %d\n", sum);

    u8[] word = "ktm";
    for (u8 c : word) {
        printf("[%c]", c);
    }
    printf("\n");

    vec.Vec<f64> v = vec.new<f64>();
    v.push(1.5);
    v.push(2.5);
    for (f64 x : v) {
        printf("%.1f ", x);
    }
    printf("\n");
    v.free();
}
```

```output
sum = 28
[k][t][m]
1.5 2.5 
```

The loop variable is a copy of the element. Changing it changes nothing in the array; to change the
elements, loop over the index:

```ktm run
void main() {
    i64 nums[] = {1, 2, 3};
    for (auto n : nums) {
        n *= 10;
    }
    printf("%d %d %d\n", nums[0], nums[1], nums[2]);
    for (i64 i = 0; i < 3; i++) {
        nums[i] *= 10;
    }
    printf("%d %d %d\n", nums[0], nums[1], nums[2]);
}
```

```output
1 2 3
10 20 30
```

`break` and `continue` work in a range-based `for` as in any loop:

```ktm run
void main() {
    u8[] text = "find the q here";
    i64 at = 0;
    for (u8 c : text) {
        if (c == 'q') { break; }
        at++;
    }
    printf("q is at %d\n", at);
}
```

```output
q is at 9
```

## goto

`goto label;` jumps to `label:` in the same function. It is useful for retry loops and for leaving
nested loops at once.

```ktm run
void main() {
    i64 tries = 0;
retry:
    tries++;
    if (tries < 3) {
        printf("try %d failed\n", tries);
        goto retry;
    }
    printf("succeeded on try %d\n", tries);
}
```

```output
try 1 failed
try 2 failed
succeeded on try 3
```

```ktm run
void main() {
    for (i64 i = 0; i < 10; i++) {
        for (i64 j = 0; j < 10; j++) {
            if (i * j == 42) {
                printf("%d * %d = 42\n", i, j);
                goto done;
            }
        }
    }
done:
    printf("done\n");
}
```

```output
6 * 7 = 42
done
```

A `goto` can't jump forward past a `defer` into the rest of its block, since the deferred statement
would then run without its setup having happened.
