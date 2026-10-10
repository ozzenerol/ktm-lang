#define MAX 64
#define HALF (MAX / 2)   // half
#define PI 3.14159
#define BIG 0xFFFFFFFFFFFFFFFF
#define NAME "x"
#define DBG 1
#if DBG
#endif
I64 buf[MAX];
U0 Main() { "%d %d %.2f %u %s\n", MAX, HALF, PI, BIG, NAME; }
