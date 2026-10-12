import coll;
i32 main() {
    coll.Vec<i64> v = coll.vecNew<i64>();
    v.push(4); v.push(9); v.push(2);
    ad coll.Vec<f64> f = coll.vecNew<f64>(2);
    f.push(1.5);
    printf("%d %d %.1f %d\n", v.len, coll.maxOf(&v), f.get(0), coll.count());
    v.free();
    return 0;
}
