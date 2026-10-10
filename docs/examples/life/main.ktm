// Conway's Game of Life on a small wrapping board.
//
//   ktm main.ktm -o life && ./life
//   ktm -jit main.ktm
//
// Shows: 2D arrays, nested loops, the % operator for wrap-around,
// #define constants and building output a line at a time.

#define W     12
#define H     8
#define STEPS 4

U8 board[H][W];
U8 next[H][W];

U0 Place(U8 *pattern, I64 top, I64 left)
{
  // `pattern` rows are separated by '/'; '#' is a live cell.
  I64 r = top, c = left;
  for (U8 *p = pattern; *p; p++) {
    if (*p == '/') {
      r++;
      c = left;
    } else {
      board[r][c] = *p == '#';
      c++;
    }
  }
}

I64 Neighbours(I64 r, I64 c)
{
  I64 n = 0;
  for (I64 dr = -1; dr <= 1; dr++) {
    for (I64 dc = -1; dc <= 1; dc++) {
      if (dr == 0 && dc == 0) continue;
      n += board[(r + dr + H) % H][(c + dc + W) % W];
    }
  }
  return n;
}

U0 Step()
{
  for (I64 r = 0; r < H; r++) {
    for (I64 c = 0; c < W; c++) {
      I64 n = Neighbours(r, c);
      // Born with 3 neighbours, survives with 2 or 3.
      next[r][c] = n == 3 || (board[r][c] && n == 2);
    }
  }
  MemCpy(board, next, sizeof(board));
}

I64 Population()
{
  I64 alive = 0;
  for (I64 r = 0; r < H; r++) {
    for (I64 c = 0; c < W; c++) {
      alive += board[r][c];
    }
  }
  return alive;
}

U0 Show(I64 gen)
{
  "generation %d, %d alive\n", gen, Population;
  U8 line[W + 1];
  line[W] = 0;
  for (I64 r = 0; r < H; r++) {
    for (I64 c = 0; c < W; c++) {
      line[c] = '.';
      if (board[r][c]) line[c] = '#';
    }
    "%s\n", line;
  }
}

U0 Main()
{
  Place(".#./..#/###", 0, 0);   // a glider, top left
  Place("###", 5, 7);           // a blinker, bottom right
  for (I64 gen = 0; gen <= STEPS; gen++) {
    Show(gen);
    Step;
  }
}
