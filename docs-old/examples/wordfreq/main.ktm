// Word frequency: reads a text file and prints its most common words.
//
//   ktm main.ktm -o wordfreq && ./wordfreq sample.txt
//   ktm -jit main.ktm sample.txt
//
// Shows: reading a file (fs), scanning strings, counting in a map.Map,
// sorting a vec.Vec, structs and command-line arguments.

import cstr, fs, map, str, vec;

const i64 TOP_N = 8;
const i64 MAX_WORD = 64;

struct WordCount {
    u8[] word;
    i64 count;
};

// Most frequent first, then alphabetical so ties have a stable order.
i64 byRank(WordCount *a, WordCount *b) {
    if (a.count != b.count) {
        return b.count - a.count;
    }
    return str.compare(a.word, b.word);
}

bool isLetter(u8 c) {
    return ('a' <= c <= 'z') || ('A' <= c <= 'Z');
}

i32 main(i32 argc, u8 **argv) {
    u8[] path = "sample.txt";
    if (argc > 1) {
        path = cstr.view(argv[1]);
    }

    scoped String text = fs.readAll(path);
    if (!text.ptr) {
        printf("cannot read %.*s\n", (i32)path.len, path.ptr);
        return 1;
    }
    i64 size = text.len;

    scoped map.Map<u8[], i64> counts = map.new<u8[], i64>();
    u8 word[MAX_WORD];
    i64 len = 0, total = 0;

    // One pass over the text; i == size flushes the final word.
    for (i64 i = 0; i <= size; i++) {
        u8 c = 0;
        if (i < size) { c = text[i]; }
        if (isLetter(c)) {
            if (len < MAX_WORD - 1) {
                word[len++] = str.toLower(c);
            }
        } else if (len > 0) {
            counts.set(word[0:len], counts.getOr(word[0:len], 0) + 1);   // the map copies the key
            total++;
            len = 0;
        }
    }

    scoped vec.Vec<WordCount> ranked = vec.new<WordCount>(counts.len + 1);
    i64 it = 0;
    WordCount wc;
    while (counts.next(&it, &wc.word, &wc.count)) {
        ranked.push(wc);
    }
    ranked.sort(&byRank);

    printf("%d words, %d distinct\n", total, ranked.len);
    for (i64 i = 0; i < TOP_N && i < ranked.len; i++) {
        WordCount top = ranked.get(i);
        printf("%2d. %-10.*s %d\n", i + 1, (i32)top.word.len, top.word.ptr, top.count);
    }

    return 0;
}
