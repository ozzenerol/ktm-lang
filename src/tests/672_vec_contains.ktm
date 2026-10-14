import str, test, vec;

/* Vec<T>.contains (v0.18.0): a String or u8[] by its text (not its
 * pointer), numbers and pointers by value, a struct by its bytes; an
 * empty Vec contains nothing. */

struct P { i64 x; i64 y; };

i32 main()
{
    printf("Test - vec contains: ");
    i64 correct = 0, tests = 0;

    ad vec.Vec<String> names = vec.new<String>();
    names.push(str.from("users"));
    names.push(str.from("orders"));
    ad String q = str.from("orders");
    ad String no = str.from("order");
    tests++; if (names.contains(q)) { correct++; }
    tests++; if (!names.contains(no)) { correct++; }

    ad vec.Vec<u8[]> v = vec.new<u8[]>();
    v.push("a");
    v.push("");
    u8[] text = "abc";
    tests++; if (v.contains(text[0:1])) { correct++; }      /* other memory, same text */
    tests++; if (v.contains("") && !v.contains("b") && !v.contains("ab")) { correct++; }

    ad vec.Vec<i64> n = vec.new<i64>();
    n.push(3);
    n.push(-1);
    tests++; if (n.contains(-1) && n.contains(3) && !n.contains(4)) { correct++; }

    ad vec.Vec<f64> f = vec.new<f64>();
    f.push(1.5);
    tests++; if (f.contains(1.5) && !f.contains(2.0)) { correct++; }

    ad vec.Vec<P> ps = vec.new<P>();
    P p = {1, 2};
    P r = {1, 3};
    ps.push(p);
    tests++; if (ps.contains(p) && !ps.contains(r)) { correct++; }

    ad vec.Vec<i64> none = vec.new<i64>();
    tests++; if (!none.contains(0)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
