import libc;
import net.sock;

i32 main() {
  u8 *s = "hello\n";
  libc.write(1, s, libc.strlen(s));
  fn(u8 *) u64 len = &libc.strlen;
  printf("%d %d %d\n", sizeof(libc.timespec), len("abc"), sock.dist(2, 9));
  return 0;
}
