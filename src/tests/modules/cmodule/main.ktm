import c;
import net.sock;

i32 main() {
  u8 *s = "hello\n";
  c.write(c.STDOUT_FILENO, s, c.strlen(s));
  c.timespec t;
  i32 r = c.clock_gettime(c.clockid.monotonic, &t);
  fn(u8 *) u64 len = &c.strlen;
  printf("%d %d %d %d\n", r, sizeof(c.timespec), len("abc"), sock.dist(2, 9));
  return 0;
}
