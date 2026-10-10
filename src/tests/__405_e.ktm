i32 main();
i64 calls = 0;
void Again() { if (calls < 2) main(); }
i32 main()
{
  calls++;
  Again();
  if (calls == 2) {
    printf("calls %d\n", calls);
    calls = 99;
  }
  return 0;
}
