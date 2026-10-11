module geo;
import mem;

i64 gCalls = 0;

i64 square(i64 n) {
    gCalls++;
    return n * n;
}

pub Point *makePoint(i64 x, i64 y) {
    Point *p = mem.alloc(sizeof(Point));
    p.x = x;
    p.y = y;
    return p;
}

pub i64 calls() { return gCalls; }
