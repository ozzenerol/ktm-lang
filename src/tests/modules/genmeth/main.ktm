import bag, vec;

f64 half(i64 x) { return (f64)x / 2.0; }
u8 *name(i64 x) { return x > 1 ? "big" : "small"; }
i64 triple(f64 x) { return (i64)(x * 3.0); }

i32 main()
{
    bag.Bag b;
    b.add(1);
    b.add(2);
    b.add(3);
    vec.Vec<f64> h = b.map(&half);
    vec.Vec<u8 *> n = b.map<u8 *>(&name);
    printf("%.1f %.1f %s %s %d\n", h.data[0], h.data[2], n.data[0], n.data[2], b.countPlus("x"));
    bag.Box<i64> x = {7};
    printf("%.1f %d\n", x.then(&half).v, x.then(&half).then(&triple).v);
    h.free();
    n.free();
    b.items.free();
    return 0;
}
