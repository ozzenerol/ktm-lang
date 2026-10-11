module fs;

#ifdef IS_LINUX
  #define F_GETOWN 0x9
  #define F_SETOWN 0x8
  #define F_GETLK  0x5
  #define F_SETLK  0x6
  #define F_SETLKW 0x7
#else
  #define F_GETOWN 0x5
  #define F_SETOWN 0x6
  #define F_GETLK  0x7
  #define F_SETLK  0x8
  #define F_SETLKW 0x9
#endif

/* record locking flags */
#ifdef IS_LINUX
  #define F_RDLCK 0
  #define F_WRLCK 1
  #define F_UNLCK 2
#else
  #define F_RDLCK 1
  #define F_UNLCK 2
  #define F_WRLCK 3
#endif

/* Just why? */
#ifdef IS_LINUX
  #define O_CREAT    0x040
  #define O_TRUNC    0x200
  #define O_EXCL     0x080
  #define O_NONBLOCK 0x800
  #define O_APPEND   0x400
  #define O_CLOEXEC  0x80000
#else
  #define O_CREAT    0x200
  #define O_TRUNC    0x400
  #define O_EXCL     0x800
  #define O_NONBLOCK 0x004
  #define O_APPEND   0x008
  #define O_CLOEXEC  0x1000000
#endif

/* Stantardise the stat function, though `std.ktmh` also allows 
 * calling the libc version. This is just much easier to use.
 * With the added benifit of including when a file was created 
 * for linux, which mac includes with `st_birthtimespec` */
public I32 Stat(Fstat *fst, U8 *pathname)
{
#if IS_LINUX
  Statx stx;

  #define AT_FDCWD -100
  #define AT_STATX_SYNC_AS_STAT 0x0000
  #define STATX_BTIME 0x0800U
  #define STATX_ATIME 0x0020U
  #define STATX_MTIME 0x0040U
  #define STATX_CTIME 0x0080U

  if (I32 ret = statx(AT_FDCWD, pathname, AT_STATX_SYNC_AS_STAT,
        STATX_BTIME, &stx); ret != 0) {
    return ret;
  }

  U64 st_dev = (stx.stx_dev_minor & 0xFF)           |
               ((stx.stx_dev_major & 0xFFF) << 8)   |
               ((stx.stx_dev_minor & ~0xFF)  << 12) |
               ((stx.stx_dev_major & ~0xFFF) << 32);

  U64 st_rdev = (stx.stx_rdev_minor & 0xFF)           |
                ((stx.stx_rdev_major & 0xFFF) << 8)   |
                ((stx.stx_rdev_minor & ~0xFF)  << 12) |
                ((stx.stx_rdev_major & ~0xFFF) << 32);

  fst->st_dev     = st_dev;
  fst->st_ino     = stx.stx_ino;
  fst->st_nlink   = stx.stx_nlink;
  fst->st_mode    = stx.stx_mode;
  fst->st_uid     = stx.stx_uid;
  fst->st_gid     = stx.stx_gid;
  fst->st_rdev    = st_rdev;
  fst->st_size    = stx.stx_size;
  fst->st_blksize = stx.stx_blksize;
  fst->st_blocks  = stx.stx_blocks;
  /* statx_timestamp's tv_nsec is a U32 followed by padding - copy
   * field-wise rather than struct-wise into cTimespec. */
  fst->st_atim.tv_sec  = stx.stx_atime.tv_sec;
  fst->st_atim.tv_nsec = stx.stx_atime.tv_nsec;
  fst->st_mtim.tv_sec  = stx.stx_mtime.tv_sec;
  fst->st_mtim.tv_nsec = stx.stx_mtime.tv_nsec;
  fst->st_ctim.tv_sec  = stx.stx_ctime.tv_sec;
  fst->st_ctim.tv_nsec = stx.stx_ctime.tv_nsec;
  fst->st_btim.tv_sec  = stx.stx_btime.tv_sec;
  fst->st_btim.tv_nsec = stx.stx_btime.tv_nsec;

#elif IS_MACOS
  cStat st;
  if (I32 ret = stat(pathname, &st); ret != 0) {
    return ret;
  }

  fst->st_dev = st.st_dev;
  fst->st_ino = st.st_ino;
  fst->st_nlink = st.st_nlink;
  fst->st_mode = st.st_mode;
  fst->st_uid = st.st_uid;
  fst->st_gid = st.st_gid;
  fst->st_rdev = st.st_rdev;
  fst->st_size = st.st_size;
  fst->st_blksize = st.st_blksize;
  fst->st_blocks = st.st_blocks;
  fst->st_atim = st.st_atimespec;
  fst->st_mtim = st.st_mtimespec;
  fst->st_ctim = st.st_ctimespec;
  fst->st_btim = st.st_birthtimespec;
#else
#error "Unknown platform"
#endif
  return 0;
}

/* Read fd until end of file into a buffer that doubles as it fills:
 * pipes, FIFOs and devices have no size, and /proc files report 0. */
static U8 *FileReadToEof(I32 fd, I64 *_len)
{
  I64 cap = 4096, size = 0, rbytes;
  U8 *s = MAlloc(cap), *bigger;

  while (s) {
    if (size + 1 >= cap) {
      if ((bigger = ReAlloc(s, cap * 2)) == NULL) {
        break;
      }
      s = bigger;
      cap *= 2;
    }
    rbytes = read(fd,s+size,cap-size-1);
    if (rbytes == 0) {
      s[size] = '\0';
      *_len = size;
      return s;
    }
    if (rbytes < 0) {
      break;
    }
    size += rbytes;
  }
  Free(s);
  return NULL;
}

public U8 *FileRead(U8 *path, I64 *_size=NULL)
{
  if (_size) *_size = 0;
  if (!path) return NULL;
  I32 fd;
  I64 len, rbytes, size;
  U8 *s;
  cStat st;

  /* The library's fds are close-on-exec: programs it starts don't get them */
  if ((fd = open(path,O_RDONLY|O_CLOEXEC,438)) == -1) {
    return NULL;    
  }

  /* A directory has nothing to read (lseek on one gives a huge "size" on
   * some filesystems, LLONG_MAX on ext4) */
  if (fstat(fd,&st) != 0 || (st.st_mode & S_IFMT) == S_IFDIR) {
    close(fd);
    return NULL;
  }

  /* Only a regular file with a size is read up to that size; anything
   * else (a FIFO, a pipe, a device, a /proc file of size 0) to EOF. */
  if ((st.st_mode & S_IFMT) != S_IFREG || st.st_size == 0) {
    s = FileReadToEof(fd,&len);
    close(fd);
    if (s && _size) {
      *_size = len;
    }
    return s;
  }

  /* get the file size */
  if ((len = lseek(fd,0,SEEK_END)) < 0) {
    close(fd);
    return NULL;
  }

  lseek(fd,0,SEEK_SET);

  if ((s = MAlloc(len+100)) == NULL) {
    close(fd);
    return NULL;
  }
  size = 0;

  /* Ask only for what is left: a `len`-byte read at `s+size` runs past
   * the buffer, which qemu-user rejects with EFAULT when that crosses
   * the end of the heap. Stop on an error rather than adding -1. */
  while (size < len && (rbytes = read(fd,s+size,len-size)) > 0) {
    size += rbytes;
  }

  if (size != len) {
    close(fd);
    Free(s);
    return NULL;
  }

  if (_size) {
    *_size = len;
  }

  s[len] = '\0';
  close(fd);
  return s;
}

public Bool FileWrite(U8 *filename, U8 *buf, I64 size,
    I64 flags=O_CREAT|O_WRONLY|O_TRUNC)
{
  I64 towrite = size, nwritten = 0;
  I32 fd;
  I32 perms = 420;

  if (!filename || !buf || size < 0) {
    return FALSE;
  }

  if ((fd = open(filename,flags|O_CLOEXEC,perms)) == -1) {
    return FALSE;
  }

  while (towrite > 0) {
    nwritten = write(fd,buf,towrite);
    if (nwritten < 0) {
      close(fd);
      return FALSE;
    }
    buf += nwritten;
    towrite -= nwritten;
  }
  close(fd);
  return TRUE;
}

public I32 Cd(U8 *path)
{
  U8 *new_path = NULL;
  U64 len = StrLen(path);
  if ((len == 1 && path[0] == '~') ||
      (len == 2 && path[0] == '~' && path[1] == '/')) {
    U8 *home = getenv("HOME");
    home = StrNew(home);
    new_path = home;
  } else if (len >= 3 && path[0] == '~' && path[1] == '/') {
    U8 *home = getenv("HOME");
    U8 *p = path;
    if (*(p + 2) != '\0') {
      new_path = StrPrint(,"%s/%s",home,p+2);
    } else {
      new_path = StrPrint(,"%s",home);
    }
  } else {
    new_path = path;
  }
  I32 ret_val = chdir(new_path);
  if (new_path != path) {
    Free(new_path);
  }
  return ret_val;
}

/* The working directory: written into buf if one is given, otherwise a
 * new string to Free (getcwd's own block is libc malloc'd, which Free
 * can't take). NULL on error. */
public U8 *Pwd(U8 *buf=NULL, U64 buf_size=0)
{
  U8 *cwd, *copy;
  if (buf) return getcwd(buf, buf_size);
  if ((cwd = getcwd(NULL, 0)) == NULL) return NULL;
  copy = StrNew(cwd);
  free(cwd);
  return copy;
}

/* `ls`-style entry colours. */
#define DIR_COL_DIR "\033[1;34m"  /* directory  - bold blue  */

#define DIR_COL_LNK "\033[1;36m"  /* symlink    - bold cyan  */

#define DIR_COL_EXE "\033[1;32m"  /* executable - bold green */

#define DIR_COL_END "\033[0m"

class DirHuman
{
  U8 *pathname;
  U32 mode;  /* st_mode: file type + permission bits */
  U8  type;  /* Dirent type - catches symlinks, which Stat() follows */
};

DirHuman *DirHumanNew(U8 *pathname, Fstat *fst, U8 type)
{
  DirHuman *dh = MAlloc(sizeof(DirHuman));
  dh->pathname = pathname;
  dh->mode = fst->st_mode;
  dh->type = type;
  return dh;
}

U0 DirHumanRelease(U0 *_dh)
{
  DirHuman *dh = _dh;
  if (dh) {
    Free(dh->pathname);
    Free(dh);
  }
}

/* The colour `ls` would paint this entry, or NULL for plain. */
U8 *DirHumanColor(DirHuman *dh)
{
  if (dh->type == DT_LNK) return DIR_COL_LNK;
  if (dh->type == DT_DIR || (dh->mode & S_IFMT) == S_IFDIR) {
    return DIR_COL_DIR;
  }
  if (dh->mode & (S_IXUSR|S_IXGRP|S_IXOTH)) return DIR_COL_EXE;
  return NULL;
}

/* Compare the runs of digits at the start of s1 and s2 by value, however
 * long they are: <0, 0 or >0 */
I64 CmpLeadingNumber(U8 *s1, U8 *s2)
{
  I64 n1 = 0, n2 = 0;
  while (*s1 == '0') s1++;
  while (*s2 == '0') s2++;
  while ('0'<=s1[n1]<='9') n1++;
  while ('0'<=s2[n2]<='9') n2++;
  if (n1 != n2) return n1 - n2;
  return StrNCmp(s1,s2,n1);
}

/* TRUE if dh1 is listed before dh2: names that both start with a number
 * by that number, then everything by the plain name */
I64 CmpDirHuman(U0 *_dh1, U0 *_dh2)
{
  DirHuman *dh1 = _dh1, *dh2 = _dh2;
  U8 *str1 = dh1->pathname;
  U8 *str2 = dh2->pathname;
  I64 cmp;
  if ('0'<=*str1<='9' && '0'<=*str2<='9') {
    if ((cmp = CmpLeadingNumber(str1,str2)) != 0) return cmp < 0;
  }
  return StrCmp(str1,str2) < 0;
}
