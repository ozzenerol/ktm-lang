import libc;
import net.sock;

i32 main() {
    u8 *s = "hello\n";
    libc.write(libc.STDOUTFILENO, s, libc.strlen(s));
    libc.timespec t;
    i32 r = libc.clock_gettime(libc.clockid.realtime, &t);
    fn(u8 *) u64 len = &libc.strlen;
    printf("%d %d %d %d\n", r, sizeof(libc.timespec), len("abc"), sock.dist(2, 9));
    return 0;
}
