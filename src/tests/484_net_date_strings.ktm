import date, libc, net, str, test;

/* net and date with String and u8[] (v0.3.0 step 6d): addresses are u8[]
 * (empty: any), net.send/net.sendTo take the bytes as a u8[],
 * net.recvFrom reads into a u8[] and gives the sender as a String,
 * net.peerAddr/net.addrToStr give Strings; date's formatting functions
 * give Strings and its parsing ones take u8[]. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    printf("Test - net and date with String and u8[]: ");
    String host = str.from("127.0.0.1");
    defer host.free();
    i32 srv = net.listen(0, host, 4);
    i32 cli = net.dial(host, net.localPort(srv));
    i32 conn = net.accept(srv);
    check(srv != -1 && cli != -1 && conn != -1, "listen, dial, accept with a String address");
    String peer = net.peerAddr(conn);
    defer peer.free();
    check(peer.equals("127.0.0.1"), "peerAddr is a String");

    u8[] msg = "hello there";
    check(net.send(cli, msg[0:5]) == 5, "send of a slice");
    u8 buf[32];
    i64 n = libc.recv(conn, buf, sizeof(buf), 0);
    check(n == 5 && buf[0:n].equals("hello"), "got just the slice");
    libc.close(conn);
    libc.close(cli);
    libc.close(srv);
    check(net.dial("", 1) == -1, "dial to an empty address fails");

    i32 a = net.udpSocket(0, "");
    i32 b = net.udpSocket(0, "127.0.0.1");
    check(a != -1 && b != -1, "udpSocket on any address and on one");
    check(net.sendTo(b, "ping!", "127.0.0.1", net.localPort(a)) == 5, "sendTo");
    String from;
    defer from.free();
    i32 port = 0;
    n = net.recvFrom(a, buf, &from, &port);
    check(n == 5 && buf[0:n].equals("ping!") && from.equals("127.0.0.1") && port == net.localPort(b),
          "recvFrom into a u8[], the sender as a String");
    libc.close(a);
    libc.close(b);

    date.Date d = date.parse("Mar 5 2024 1:30 PM", "%b %d %Y %H:%M %p");
    String day = date.formatDate(d), clock = date.formatTime(d);
    defer day.free();
    defer clock.free();
    check(day.equals("03/05/2024") && clock.equals("13:30:00"), "formatDate, formatTime");
    String f = date.format(d, "%Y-%m-%d %a");
    defer f.free();
    check(f.equals("2024-03-05 Tue"), "format");
    u8[] text = "xx 2024-02-29 yy";
    date.Parts ds;
    check(date.parseParts(text[3:13], "%Y-%m-%d", &ds) && ds.dayOfMon == 29, "parseParts of a slice");
    String stamp = date.timestampNow();
    defer stamp.free();
    check(stamp.len == 19 && stamp[4] == '-', "timestampNow");
    libc.timespec ts;
    ts.tv_sec = 0;
    ts.tv_nsec = 5;
    String iso = date.isoFromTimespec(&ts);
    defer iso.free();
    check(iso.equals("1970-01-01T00:00:00.000000005Z"), "isoFromTimespec");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
