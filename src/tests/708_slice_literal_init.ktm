import mem, str, test;

/* `T[] s = {a, b, c};` (v0.18.0): a list of Ts makes a hidden array the
 * slice views (it compiled with "Incompatible value" warnings and
 * crashed: the items went into the slice's ptr and len fields). Two items
 * that are a T * and a length are still the slice's own fields. Locals,
 * globals, nested lists for a slice of structs. */

struct P { i64 x; i64 y; };

String[] gTags = {str.from("g1"), str.from("g2"), str.from("g3")};
i64[] gNums = {7, 8};

i64 sum(i64[] xs) {
    i64 t = 0;
    for (auto x : xs) { t += x; }
    return t;
}

i32 main()
{
    printf("Test - slice literal initialisers: ");
    i64 correct = 0, tests = 0;

    String[] tags = {str.from("a"), str.from("b")};
    tests++; if (tags.len == 2 && str.equals(tags[0], "a") && str.equals(tags[1], "b")) { correct++; }

    i64[] nums = {1, 2, 3};
    tests++; if (nums.len == 3 && nums[2] == 3 && sum(nums) == 6) { correct++; }

    i64 *raw = mem.alloc(sizeof(i64) * 2);
    raw[0] = 5;
    raw[1] = 6;
    i64[] view = {raw, 2};
    tests++; if (view.len == 2 && view.ptr == raw && view[1] == 6) { correct++; }

    P[] ps = {{1, 2}, {3, 4}};
    tests++; if (ps.len == 2 && ps[1].y == 4 && ps[0].x == 1) { correct++; }

    u8[][] words = {"x", "yz"};
    tests++; if (words.len == 2 && str.equals(words[1], "yz")) { correct++; }

    i64[] one = {42}, none = {};
    tests++; if (one.len == 1 && one[0] == 42 && none.len == 0) { correct++; }

    tests++; if (gTags.len == 3 && str.equals(gTags[2], "g3") && gNums.len == 2 && gNums[1] == 8) { correct++; }

    nums[0] = 10;
    tests++; if (sum(nums) == 15) { correct++; }

    for (auto t : tags) { t.free(); }
    mem.free(raw);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
