module fs;

public U8 *FileExtDot(U8 *src)
{//@TempleOS - Find dot char in name.
  I64 ch;
  if (!src) return NULL;
  while (ch=*src++)
    if (ch=='.' && *src!='/' && *src!='.')
      return src-1;
  return NULL;
}

public U8 *FileExtRem(U8 *src,U8 *dst=NULL)
{//@TempleOS - Remove filename extension from str.
  U8 *ptr;
  if (!src) {
    if (dst) *dst=0;
    return dst;
  }
  if (ptr=FileExtDot(src)) {
    if (dst)
      StrCpy(dst,ptr+1);
    *ptr=0;
  } else if (dst)
    *dst=0;
  return dst;
}
