// Conway's Game of Life on a small wrapping board.
//
//   ktm main.ktm -o life && ./life
//   ktm -jit main.ktm
//
// Shows: 2D arrays, nested loops, the % operator for wrap-around,
// constants and building output a line at a time.

import mem;

const i64 W = 12;
const i64 H = 8;
const i64 STEPS = 4;

u8 board[H][W];
u8 next[H][W];

void place(u8 *pattern, i64 top, i64 left) {
    // `pattern` rows are separated by '/'; '#' is a live cell.
    i64 r = top, c = left;
    for (u8 *p = pattern; *p; p++) {
        if (*p == '/') {
            r++;
            c = left;
        } else {
            board[r][c] = *p == '#';
            c++;
        }
    }
}

i64 neighbours(i64 r, i64 c) {
    i64 n = 0;
    for (i64 dr = -1; dr <= 1; dr++) {
        for (i64 dc = -1; dc <= 1; dc++) {
            if (dr == 0 && dc == 0) { continue; }
            n += board[(r + dr + H) % H][(c + dc + W) % W];
        }
    }
    return n;
}

void step() {
    for (i64 r = 0; r < H; r++) {
        for (i64 c = 0; c < W; c++) {
            i64 n = neighbours(r, c);
            // Born with 3 neighbours, survives with 2 or 3.
            next[r][c] = n == 3 || (board[r][c] && n == 2);
        }
    }
    mem.copy(board, next, sizeof(board));
}

i64 population() {
    i64 alive = 0;
    for (i64 r = 0; r < H; r++) {
        for (i64 c = 0; c < W; c++) {
            alive += board[r][c];
        }
    }
    return alive;
}

void show(i64 gen) {
    printf("generation %d, %d alive\n", gen, population());
    u8 line[W + 1];
    line[W] = 0;
    for (i64 r = 0; r < H; r++) {
        for (i64 c = 0; c < W; c++) {
            line[c] = '.';
            if (board[r][c]) { line[c] = '#'; }
        }
        printf("%s\n", line);
    }
}

void main() {
    place(".#./..#/###", 0, 0);   // a glider, top left
    place("###", 5, 7);           // a blinker, bottom right
    for (i64 gen = 0; gen <= STEPS; gen++) {
        show(gen);
        step();
    }
}
