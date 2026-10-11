module fs;
import str;

pub u8 *fileExt(u8 *src)
{// Find dot char in name.
    i64 ch;
    if (!src) { return null; }
    while (ch=*src++) {
        if (ch=='.' && *src!='/' && *src!='.') {
            return src-1;
        }
    }
    return null;
}

pub u8 *fileExtCut(u8 *src,u8 *dst=null)
{// Remove filename extension from str.
    u8 *ptr;
    if (!src) {
        if (dst) { *dst=0; }
        return dst;
    }
    if (ptr=fileExt(src)) {
        if (dst) {
            strCpy(dst,ptr+1);
        }
        *ptr=0;
    } else if (dst) {
        *dst=0;
    }
    return dst;
}
