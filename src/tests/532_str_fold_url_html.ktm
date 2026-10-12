import str, test;

/* str (v0.6.0): ASCII case-insensitive compare and search, URL
 * percent-encoding (component, path, form) and decoding, HTML escaping. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool isThen(String s, u8[] want) {
    bool ok = str.equals(s, want);
    if (!ok) { printf("\n  got [%s] want [%s]", s, want); }
    s.free();
    return ok;
}

bool decodes(u8[] s, u8[] want, bool form = false) {
    String out;
    bool ok = form ? str.formDecode(s, &out) : str.urlDecode(s, &out);
    ok = ok && str.equals(out, want);
    out.free();
    return ok;
}

bool rejects(u8[] s) {
    String out = str.from("junk");
    bool ok = !str.urlDecode(s, &out) && out.len == 0;
    out.free();
    return ok;
}

i32 main() {
    printf("Test - str fold, url, html: ");

    check(str.equalsFold("Content-Length", "content-LENGTH"), "equalsFold");
    check(!str.equalsFold("Content-Length", "Content-Lengths"), "equalsFold length");
    check(!str.equalsFold("a[", "A{"), "equalsFold only folds letters");
    check(str.equalsFold("", ""), "equalsFold empty");
    check(!str.equalsFold("\xc3\xa9", "\xc3\x89"), "equalsFold leaves UTF-8 alone");
    check(str.compareFold("apple", "BANANA") < 0, "compareFold less");
    check(str.compareFold("Zed", "alpha") > 0, "compareFold greater");
    check(str.compareFold("ABC", "abc") == 0, "compareFold equal");
    check(str.compareFold("ab", "ABC") < 0, "compareFold shorter first");
    check(str.findFold("Connection: Keep-Alive", "keep-alive") == 12, "findFold");
    check(str.findFold("abc", "ABCD") == -1, "findFold longer needle");
    check(str.findFold("abc", "") == 0, "findFold empty needle");
    check(str.findFold("aaAB", "ab") == 2, "findFold after a partial match");
    check(str.containsFold("Upgrade, CLOSE", "close"), "containsFold");
    check(!str.containsFold("keep-alive", "close"), "containsFold missing");
    check(str.startsWithFold("HTTP://host/", "http://"), "startsWithFold");
    check(!str.startsWithFold("ht", "http"), "startsWithFold short");
    check(str.endsWithFold("index.HTML", ".html"), "endsWithFold");
    String hdr = str.from("X-Forwarded-For");
    check(hdr.equalsFold("x-forwarded-for"), "as a method on a String");
    hdr.free();

    check(isThen(str.urlEncode("a b&c=d/e?~_.-"), "a%20b%26c%3Dd%2Fe%3F~_.-"), "urlEncode");
    check(isThen(str.urlEncode("\xc3\xa9\x00\xff"), "%C3%A9%00%FF"), "urlEncode bytes");
    check(isThen(str.urlEncode(""), ""), "urlEncode empty");
    check(isThen(str.urlEncodePath("/docs/a b/%.txt"), "/docs/a%20b/%25.txt"), "urlEncodePath");
    check(isThen(str.formEncode("q=a b+c"), "q%3Da+b%2Bc"), "formEncode");

    check(decodes("a%20b%2fc%2F", "a b/c/"), "urlDecode");
    check(decodes("a+b", "a+b"), "urlDecode keeps +");
    check(decodes("a+b%2B", "a b+", true), "formDecode");
    check(decodes("", ""), "urlDecode empty");
    check(decodes("%C3%A9", "\xc3\xa9"), "urlDecode UTF-8");
    String z;
    check(str.urlDecode("a%00b", &z) && z.len == 3 && z.ptr[1] == 0, "urlDecode %00 is a byte");
    z.free();
    check(rejects("%"), "a lone %");
    check(rejects("ab%4"), "% and one digit");
    check(rejects("%zz"), "% and no hex");
    check(rejects("%4g"), "% and one hex digit");
    String round = str.urlEncode("any \x01 bytes / ? # %");
    String back;
    check(str.urlDecode(round, &back) && str.equals(back, "any \x01 bytes / ? # %"), "round trip");
    round.free();
    back.free();

    check(isThen(str.htmlEscape("<a href=\"x\">Tom & Jerry's</a>"),
                 "&lt;a href=&quot;x&quot;&gt;Tom &amp; Jerry&#39;s&lt;/a&gt;"), "htmlEscape");
    check(isThen(str.htmlEscape("plain \xc3\xa9"), "plain \xc3\xa9"), "htmlEscape plain");
    check(isThen(str.htmlEscape(""), ""), "htmlEscape empty");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
