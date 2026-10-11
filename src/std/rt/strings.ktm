module rt;
import mem, str;

pub void __ktmPrintf(u8 *fmt,...args)
{// StrPrint to stdout, through C's stdio so it stays in
    // order with printf and the print statements that use it
        u8 *buf=str.formatArgs(null, fmt, args);
        printf("%s",buf);
        mem.free(buf);
}
