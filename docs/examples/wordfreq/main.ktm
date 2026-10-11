// Word frequency: reads a text file and prints its most common words.
//
//   ktm main.ktm -o wordfreq && ./wordfreq sample.txt
//   ktm -jit main.ktm sample.txt
//
// Shows: reading a file (fs), scanning strings, counting in a map.StrMap,
// sorting a vec.PtrVec, structs and command-line arguments.

import cstr, fs, map, mem, str, vec;

const i64 TOP_N = 8;
const i64 MAX_WORD = 64;

struct WordCount {
    String word;
    i64 count;
};

// Sort predicate: true when a ranks above b. Most frequent first, then
// alphabetical so ties have a stable order.
i64 ranksHigher(void *aArg, void *bArg) {
    WordCount *a = aArg;
    WordCount *b = bArg;
    if (a.count != b.count) {
        return a.count > b.count;
    }
    return str.compare(a.word, b.word) < 0;
}

bool isLetter(u8 c) {
    return ('a' <= c <= 'z') || ('A' <= c <= 'Z');
}

void countWord(map.StrMap *counts, u8[] word) {
    WordCount *wc = counts.get(word);
    if (wc == null) {
        wc = mem.alloc(sizeof(WordCount));
        wc.word = str.from(word);
        wc.count = 0;
        counts.set(word, wc);           // the map copies the key
    }
    wc.count++;
}

i32 main(i32 argc, u8 **argv) {
    u8[] path = "sample.txt";
    if (argc > 1) {
        path = cstr.view(argv[1]);
    }

    String text = fs.readAll(path);
    defer text.free();
    if (!text.ptr) {
        printf("cannot read %s\n", path);
        return 1;
    }
    i64 size = text.len;

    map.StrMap *counts = map.strNew();
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
            countWord(counts, word[0:len]);
            total++;
            len = 0;
        }
    }

    vec.PtrVec *ranked = vec.ptrNew();
    i64 idx = 0;
    WordCount *wc;
    while (counts.valueIter(&idx, &wc)) {
        ranked.push(wc);
    }
    ranked.sort(&ranksHigher);

    printf("%d words, %d distinct\n", total, ranked.size);
    for (i64 i = 0; i < TOP_N && i < ranked.size; i++) {
        WordCount *top = ranked.entries[i];
        printf("%2d. %-10s %d\n", i + 1, top.word.cstr(), top.count);
    }

    return 0;
}
