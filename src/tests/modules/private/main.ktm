import net;

i32 main()
{
  net.Conn *c = net.dial(3);
  net.Conn copy = *c;                /* the layout is public */
  net.Conn lit = net.literal();
  net.Tls t;
  t.fd = 1;
  t.level = 2;
  "%d %d %d %s %s %d\n", c.fd, sizeof(net.Conn), c.count(), net.peek(&copy),
      net.peek(&lit), copy.fd;
  return 0;
}
