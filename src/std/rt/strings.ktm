module rt;
import cstr, mem;

pub void __ktmPrintf(u8 *fmt,...args)
{// cstr.formatTo to stdout, through C's stdio so it stays in
    // order with printf and the print statements that use it
        u8 *buf=cstr.formatArgs(null, fmt, args);
        printf("%s",buf);
        mem.free(buf);
}
