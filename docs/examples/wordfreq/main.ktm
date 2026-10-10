// Word frequency: reads a text file and prints its most common words.
//
//   ktm main.ktm -o wordfreq && ./wordfreq sample.txt
//   ktm -jit main.ktm sample.txt
//
// Shows: reading files, scanning strings, StrMap, PtrVec sorting,
// classes and command-line arguments.

#define TOP_N    8
#define MAX_WORD 64

class WordCount
{
  U8 *word;
  I64 count;
};

// Sort predicate: TRUE when a ranks above b. Most frequent first, then
// alphabetical so ties have a stable order.
I64 RanksHigher(U0 *_a, U0 *_b)
{
  WordCount *a = _a;
  WordCount *b = _b;
  if (a->count != b->count) {
    return a->count > b->count;
  }
  return StrCmp(a->word, b->word) < 0;
}

Bool IsLetter(U8 c)
{
  return ('a' <= c <= 'z') || ('A' <= c <= 'Z');
}

U0 CountWord(StrMap *counts, U8 *word)
{
  WordCount *wc = StrMapGet(counts, word);
  if (wc == NULL) {
    wc = MAlloc(sizeof(WordCount));
    wc->word = StrNew(word);       // the map keeps this pointer as its key
    wc->count = 0;
    StrMapSet(counts, wc->word, wc);
  }
  wc->count++;
}

I32 Main(I32 argc, U8 **argv)
{
  U8 *path = "sample.txt";
  if (argc > 1) {
    path = argv[1];
  }

  I64 size;
  U8 *text = FileRead(path, &size);
  if (text == NULL) {
    "cannot read %s\n", path;
    return 1;
  }

  StrMap *counts = StrMapNew();
  U8 word[MAX_WORD];
  I64 len = 0, total = 0;

  // One pass over the text; i == size flushes the final word.
  for (I64 i = 0; i <= size; i++) {
    U8 c = 0;
    if (i < size) c = text[i];
    if (IsLetter(c)) {
      if (len < MAX_WORD - 1) {
        word[len++] = ToLower(c);
      }
    } else if (len > 0) {
      word[len] = 0;
      CountWord(counts, word);
      total++;
      len = 0;
    }
  }

  PtrVec *ranked = PtrVecNew();
  I64 idx = 0;
  WordCount *wc;
  while (StrMapValueIter(counts, &idx, &wc)) {
    PtrVecPush(ranked, wc);
  }
  PtrVecSort(ranked, &RanksHigher);

  "%d words, %d distinct\n", total, ranked->size;
  for (I64 i = 0; i < TOP_N && i < ranked->size; i++) {
    WordCount *top = ranked->entries[i];
    "%2d. %-10s %d\n", i + 1, top->word, top->count;
  }

  Free(text);
  return 0;
}
