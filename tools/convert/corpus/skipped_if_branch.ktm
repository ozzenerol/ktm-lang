#ifdef MIGRATE_TEST_NEVER_DEFINED
I64 Never(U8 *p) { "%d\n", p(I64); return p(I64) + 1; }
#else
I64 Never(U8 *p) { return 0; }
#endif
U0 Main()
{
  "%d\n", Never(NULL);
}
