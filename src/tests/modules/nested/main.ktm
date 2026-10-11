import net, net.http;
import h = net.http;
import n = net;

i32 main() {
    net.Conn *c = http.open(3);
    printf("%d %d\n", c.fd, c.port);
    printf("%d %d %d\n", http.get("/a"), h.get("/abc"), n.port());
    n.Conn *d = h.open(4);
    printf("%d\n", d.fd + d.port);
    return 0;
}
