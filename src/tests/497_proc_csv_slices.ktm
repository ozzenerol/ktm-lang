import csv, mem, proc, str, test;

/* v0.3.1: proc and csv with u8[] and String, as the rest of the library
 * since v0.3.0. proc.shell takes a u8[]; shellQuote, shellOutput and run
 * give Strings; communicate takes its input as a u8[] and gives stdout and
 * stderr as Strings. csv.init takes a u8[] and csv.next gives a csv.Item
 * whose text is a view of the input (it was a csv.Slice of offsets). */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    printf("Test - proc and csv with u8[] and String: ");

    /* proc.shell: a slice that isn't NUL-terminated is the whole command */
    u8[] cmd = "exit 3; this part is not run";
    check(proc.shell(cmd[0:6]) == 3, "shell takes a u8[]");

    String q = proc.shellQuote("it's $HOME");
    defer q.free();
    check(q.equals("'it'\\''s $HOME'"), "shellQuote gives a String");
    String echoed = proc.shellOutput("printf '%%s' %s", q);
    defer echoed.free();
    check(echoed.equals("it's $HOME"), "shellOutput gives a String");
    String none = proc.shellOutput("true");
    defer none.free();
    check(none.ptr && none.len == 0, "empty output is an empty String");

    u8 *argv[] = {"sh", "-c", "echo hi; exit 4", null};
    i64 code = 0;
    String ran = proc.run(argv, &code);
    defer ran.free();
    check(ran.equals("hi") && code == 4, "run gives a String and the code");

    u8 *cat[] = {"sh", "-c", "cat; echo err >&2", null};
    proc.Opts o;
    mem.set(&o, 0, sizeof(proc.Opts));
    o.in = proc.PIPE;
    o.out = proc.PIPE;
    o.err = proc.PIPE;
    proc.Proc *p = proc.spawn(cat, &o);
    String out, err;
    u8[] input = "abcdef";
    i64 rc = proc.communicate(p, input[1:4], &out, &err);
    proc.release(p);
    check(rc == 0 && out.equals("bcd") && err.equals("err\n"), "communicate");
    out.free();
    err.free();

    /* csv: fields (quotes kept) and rows, as views of the text */
    u8[] text = "a,\"b,c\",d\r\n1,2\n";
    csv.Parser cp;
    csv.Item it;
    csv.init(&cp, text);
    String seen = str.new();
    defer seen.free();
    i64 kind;
    while ((kind = csv.next(&cp, &it)) != csv.EOF) {
        if (kind == csv.FIELD) { seen.appendFormat("[%d:%s]", it.idx, it.text); }
        else { seen.appendFormat("<row %d, %d bytes>", it.idx, it.text.len); }
    }
    check(seen.equals("[0:a][1:\"b,c\"][2:d]<row 0, 11 bytes>[0:1][1:2]<row 1, 4 bytes>"),
          "csv fields and rows");
    if (!seen.equals("[0:a][1:\"b,c\"][2:d]<row 0, 11 bytes>[0:1][1:2]<row 1, 4 bytes>")) {
        printf("  got %s\n", seen);
    }
    csv.init(&cp, text[0:5]);
    i64 fields = 0;
    while ((kind = csv.next(&cp, &it)) != csv.EOF) {
        if (kind == csv.FIELD) { fields++; }
    }
    check(fields == 2 && it.text.ptr == text.ptr, "csv of part of a text");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
