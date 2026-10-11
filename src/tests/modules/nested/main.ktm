import web, web.http;
import h = web.http;
import n = web;

i32 main() {
    web.Conn *c = http.open(3);
    printf("%d %d\n", c.fd, c.port);
    printf("%d %d %d\n", http.get("/a"), h.get("/abc"), n.port());
    n.Conn *d = h.open(4);
    printf("%d\n", d.fd + d.port);
    return 0;
}
