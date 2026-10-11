import cstr, fs, libc, mem, proc, test;

/* cstr.quote (and %Q) wrote a control byte as `x01`, with no backslash: the
 * result could not be told from the text "x01" and did not read back as
 * the same string. It is now `\x01`, which a ktm string literal reads
 * back as the byte (ktm's `\x` takes at most two hex digits). */

#define PQ_SRC "./ktm-248-pq.ktm"
#define PQ_OUT "./ktm-248-pq.out"

bool is(u8 *got, u8 *want) {
    bool ok = got && !cstr.cmp(got, want);
    if (!ok) { printf("got [%s] want [%s]\n", got, want); }
    mem.free(got);
    return ok;
}

i32 main() {
    printf("Test - MPrintQ control escapes: ");
    i64 correct = 0, tests = 0, i;

    tests++;
    if (is(cstr.quote("a\x01"), "a\\x01") && is(cstr.quote("ax01"), "ax01")) { correct++; }
    tests++;
    if (is(cstr.quote("\x1b[0m\x7f"), "\\x1B[0m\\x7F")) { correct++; }
    tests++;
    if (is(cstr.quote("tab\tnl\ncr\rq\"bs\\"), "tab\\tnl\\ncr\\rq\\\"bs\\\\")) { correct++; }
    /* A hex digit after the escape stays a separate character */
    tests++;
    if (is(cstr.quote("\x01" "abc"), "\\x01abc")) { correct++; }
    tests++;
    if (is(cstr.formatTo(null, "[%Q]", "\x02z"), "[\\x02z]")) { correct++; }

    /* Every byte 1..255 reads back the same through a string literal */
    tests++;
    u8 all[256];
    for (i = 1; i < 256; i++) { all[i - 1] = i; }
    all[255] = '\0';
    u8 *q = cstr.quote(all);
    u8 *src = cstr.formatTo(null, "void main() { printf(\"%%s\", \"%s\"); }\n", q);
    proc.shell("rm -f " PQ_SRC " " PQ_OUT);
    fs.writeAll(PQ_SRC, src, cstr.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-jit " PQ_SRC " > " PQ_OUT);
    i64 len = 0;
    u8 *back = fs.readAll(PQ_OUT, &len);
    if (back && len == 255 && !cstr.cmp(back, all)) { correct++; }
    else { printf("read back %d bytes\n", len); }
    mem.free(back);
    mem.free(src);
    mem.free(q);
    proc.shell("rm -f " PQ_SRC " " PQ_OUT);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
