import web;

i32 main() {
    web.Conn *c = web.dial(3);
    web.Conn copy = *c;                /* the layout is public */
    web.Conn lit = web.literal();
    web.Tls t;
    t.fd = 1;
    t.level = 2;
    printf("%d %d %d %s %s %d\n", c.fd, sizeof(web.Conn), c.count(), web.peek(&copy),
            web.peek(&lit), copy.fd);
    return 0;
}
