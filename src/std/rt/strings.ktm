module rt;

public U0 Print(U8 *fmt,...)
{// @TempleOS - StrPrint to stdout, through C's stdio so it stays in
 // order with printf and the print statements that use it
  U8 *buf=StrPrintJoin(NULL,fmt,argc,argv);
  printf("%s",buf);
  Free(buf);
}
