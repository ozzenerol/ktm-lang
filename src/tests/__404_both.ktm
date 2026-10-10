i64 big = 5000000000;
i32 neg = -3;
u8 byte = 200;
i16 sh = -2;
f64 pi = 3.14159;
CDate day = 0;
void main()
{
  printf("top level %d\n", 1);
  printf("top level %d\n", 1);
  printf("%d %d %u %d\n", big, neg, byte, sh);
  printf("%d %d %u %d\n", big, neg, byte, sh);
  printf("%x %X %o\n", big, 255, 8);
  printf("%x %X %o\n", big, 255, 8);
  printf("%s|%5.2f|%c|%%\n", "str", pi, 'A');
  printf("%s|%5.2f|%c|%%\n", "str", pi, 'A');
  printf("%Q\n", "a\"b");
  printf("%Q\n", "a\"b");
  printf("%D\n", day);
  printf("%D\n", day);
  printf("no arguments\n");
  printf("no arguments\n");
  if (big > 0) { printf("in if %d\n", 2); }
  if (big > 0) { printf("in if %d\n", 2); }
  i64 n = printf("%d\n", 42);
  printf("count %d\n", n);
  printf("count %d\n", 3);
}
