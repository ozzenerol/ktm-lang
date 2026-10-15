import str, test, vec;

/* str.join (v0.18.0): a u8[][], a vec.Vec<String> * or a vec.Vec<u8[]> *
 * with sep between the parts, as a new String; empty parts and an empty
 * sep kept as they are, no parts gives an empty String (as str.new), and
 * split then join gives the text back. */

i32 main()
{
    printf("Test - str.join: ");
    i64 correct = 0, tests = 0;

    scoped u8[][] parts = str.split("a,b,,c", ",");
    scoped String s1 = str.join(parts, " | ");
    tests++; if (str.equals(s1, "a | b |  | c")) { correct++; } else { printf("\n  [%s]", s1); }

    scoped String s2 = str.join(parts, ",");
    tests++; if (str.equals(s2, "a,b,,c")) { correct++; }

    scoped String s3 = str.join(parts, "");
    tests++; if (str.equals(s3, "abc")) { correct++; }

    scoped vec.Vec<String> words = vec.new<String>();
    words.push(str.format("create"));
    words.push(str.format("table"));
    words.push(str.format("users_%d", 2));
    scoped String s4 = str.join(&words, " ");
    tests++; if (str.equals(s4, "create table users_2") && s4.len == 20) { correct++; } else { printf("\n  [%s]", s4); }

    scoped vec.Vec<u8[]> views = vec.new<u8[]>();
    views.push("x");
    tests++; scoped String s5 = str.join(&views, ", ");
    if (str.equals(s5, "x")) { correct++; }
    views.push("y");
    tests++; scoped String s6 = str.join(&views, ", ");
    if (str.equals(s6, "x, y")) { correct++; }

    scoped vec.Vec<String> none = vec.new<String>();
    scoped String s7 = str.join(&none, " ");
    tests++; if (s7.len == 0 && str.equals(s7, "")) { correct++; }

    /* a String for printf: NUL-terminated */
    tests++; if (s4.ptr[s4.len] == 0) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
