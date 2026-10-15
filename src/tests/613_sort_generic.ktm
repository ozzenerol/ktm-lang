import sort, test, vec;

/* Module sort (v0.10.0): sort.slice<T> with a comparator and sort.asc<T>
 * on numbers, through Vec.sort/sortAsc too: random, sorted, reversed and
 * all-equal input of 100000 (the worst cases of a naive quicksort), f64
 * with negatives, structs by a field, short slices. */

struct Pt { i64 x; i64 y; };
i64 byY(Pt *a, Pt *b) { return a.y - b.y; }
i64 desc(i64 *a, i64 *b) { return *b - *a; }

u64 gSeed = 12345;
i64 rnd() { gSeed = gSeed * 6364136223846793005 + 1; return (i64)(gSeed >> 33); }

bool ascending(i64[] s) {
    for (i64 i = 1; i < s.len; i++) { if (s[i - 1] > s[i]) { return false; } }
    return true;
}

i32 main() {
    printf("Test - sort: ");
    i64 correct = 0, tests = 0;
    i64 n = 100000;

    scoped vec.Vec<i64> v = vec.new<i64>(n);
    for (i64 i = 0; i < n; i++) { v.push(rnd() % 1000); }
    v.sortAsc();
    tests++; if (ascending(v.items())) { correct++; }
    v.sortAsc();
    tests++; if (ascending(v.items())) { correct++; }       /* already sorted */
    v.sort(&desc);
    tests++; if (v.get(0) >= v.get(n - 1)) { correct++; }  /* reversed */
    v.sortAsc();
    tests++; if (ascending(v.items())) { correct++; }       /* reversed input */
    for (i64 i = 0; i < n; i++) { v.set(i, 7); }
    v.sortAsc();
    tests++; if (ascending(v.items()) && v.get(0) == 7) { correct++; }

    f64 f[7] = {3.5, -1.25, 0.0, 2.0, -7.5, 2.0, 1e10};
    sort.asc(f[0:7]);
    tests++; if (f[0] == -7.5 && f[1] == -1.25 && f[6] == 1e10 && f[4] == 2.0) { correct++; }

    Pt pts[5] = {{1, 50}, {2, 10}, {3, 40}, {4, 20}, {5, 30}};
    sort.slice(pts[0:5], &byY);
    tests++; if (pts[0].x == 2 && pts[1].x == 4 && pts[4].x == 1) { correct++; }

    i64 one[1] = {5};
    i64 two[2] = {9, 3};
    sort.asc(one[0:1]);
    sort.asc(two[0:2]);
    sort.asc(two[0:0]);
    tests++; if (one[0] == 5 && two[0] == 3 && two[1] == 9) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
