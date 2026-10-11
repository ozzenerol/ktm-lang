module fs;
import cstr, libc, mem, os, str;

/* The longest path the functions here take (with its NUL) */
const i64 FS_PATH_MAX = 4096;
#ifdef IS_MACOS
const i32 FS_ENAMETOOLONG = 63;
#else
const i32 FS_ENAMETOOLONG = 36;
#endif

/* Stantardise the stat function, though `std.ktmh` also allows 
 * calling the libc version. This is just much easier to use.
 * With the added benifit of including when a file was created 
 * for linux, which mac includes with `st_birthtimespec` */
static i32 statC(FileStat *fst, u8 *pathname) {
#if IS_LINUX
    libc.Statx stx;

    const i64 AT_FDCWD = -100;
    const i64 AT_STATX_SYNC_AS_STAT = 0x0000;
    const i64 STATX_BTIME = 0x0800U;
    const i64 STATX_ATIME = 0x0020U;
    const i64 STATX_MTIME = 0x0040U;
    const i64 STATX_CTIME = 0x0080U;

    if (i32 ret = libc.statx(AT_FDCWD, pathname, AT_STATX_SYNC_AS_STAT,
                STATX_BTIME, &stx); ret != 0) {
        return ret;
    }

    u64 st_dev = (stx.stx_dev_minor & 0xFF)           |
                             ((stx.stx_dev_major & 0xFFF) << 8)   |
                             ((stx.stx_dev_minor & ~0xFF)  << 12) |
                             ((stx.stx_dev_major & ~0xFFF) << 32);

    u64 st_rdev = (stx.stx_rdev_minor & 0xFF)           |
                                ((stx.stx_rdev_major & 0xFFF) << 8)   |
                                ((stx.stx_rdev_minor & ~0xFF)  << 12) |
                                ((stx.stx_rdev_major & ~0xFFF) << 32);

    fst.st_dev     = st_dev;
    fst.st_ino     = stx.stx_ino;
    fst.st_nlink   = stx.stx_nlink;
    fst.st_mode    = stx.stx_mode;
    fst.st_uid     = stx.stx_uid;
    fst.st_gid     = stx.stx_gid;
    fst.st_rdev    = st_rdev;
    fst.st_size    = stx.stx_size;
    fst.st_blksize = stx.stx_blksize;
    fst.st_blocks  = stx.stx_blocks;
    /* statx_timestamp's tv_nsec is a u32 followed by padding - copy
     * field-wise rather than struct-wise into cTimespec. */
    fst.st_atim.tv_sec  = stx.stx_atime.tv_sec;
    fst.st_atim.tv_nsec = stx.stx_atime.tv_nsec;
    fst.st_mtim.tv_sec  = stx.stx_mtime.tv_sec;
    fst.st_mtim.tv_nsec = stx.stx_mtime.tv_nsec;
    fst.st_ctim.tv_sec  = stx.stx_ctime.tv_sec;
    fst.st_ctim.tv_nsec = stx.stx_ctime.tv_nsec;
    fst.st_btim.tv_sec  = stx.stx_btime.tv_sec;
    fst.st_btim.tv_nsec = stx.stx_btime.tv_nsec;

#elif IS_MACOS
    libc.cStat st;
    if (i32 ret = libc.stat(pathname, &st); ret != 0) {
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
static u8 *fileReadToEof(i32 fd, i64 *_len) {
    i64 cap = 4096, size = 0, rbytes;
    u8 *s = mem.alloc(cap), *bigger;

    while (s) {
        if (size + 1 >= cap) {
            if ((bigger = mem.realloc(s, cap * 2)) == null) {
                break;
            }
            s = bigger;
            cap *= 2;
        }
        rbytes = libc.read(fd,s+size,cap-size-1);
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
    mem.free(s);
    return null;
}

/* `path` as a C string, in `buf` (FS_PATH_MAX bytes): false, with errno
 * ENAMETOOLONG, when it doesn't fit */
bool fsPath(u8[] path, u8 *buf)
{
    if (path.len >= FS_PATH_MAX) {
        os.setErrno(FS_ENAMETOOLONG);
        return false;
    }
    if (path.len) { mem.copy(buf, path.ptr, path.len); }
    buf[path.len] = 0;
    return true;
}

/* The whole file at path, a String that is yours (`defer s.free();`), with
 * a NUL after it; its ptr is null when the file can't be read (os.errno()
 * says why). An empty file gives an empty String that has a ptr. */
pub String readAll(u8[] path) {
    String out;
    u8 cpath[FS_PATH_MAX];
    if (!fsPath(path, cpath)) { return out; }
    i32 fd;
    i64 len, rbytes, size;
    u8 *s;
    libc.cStat st;

    /* The library's fds are close-on-exec: programs it starts don't get them */
    if ((fd = libc.open(cpath,libc.O_RDONLY|libc.O_CLOEXEC,438)) == -1) {
        return out;
    }

    /* A directory has nothing to read (lseek on one gives a huge "size" on
     * some filesystems, LLONG_MAX on ext4) */
    if (libc.fstat(fd,&st) != 0 || (st.st_mode & libc.S_IFMT) == libc.S_IFDIR) {
        libc.close(fd);
        return out;
    }

    /* Only a regular file with a size is read up to that size; anything
     * else (a FIFO, a pipe, a device, a /proc file of size 0) to EOF. */
    if ((st.st_mode & libc.S_IFMT) != libc.S_IFREG || st.st_size == 0) {
        s = fileReadToEof(fd,&len);
        libc.close(fd);
        if (s) { out = str.adopt(s, len); }
        return out;
    }

    /* get the file size */
    if ((len = libc.lseek(fd,0,libc.SEEK_END)) < 0) {
        libc.close(fd);
        return out;
    }

    libc.lseek(fd,0,libc.SEEK_SET);

    if ((s = mem.alloc(len+1)) == null) {
        libc.close(fd);
        return out;
    }
    size = 0;

    /* Ask only for what is left: a `len`-byte read at `s+size` runs past
     * the buffer, which qemu-user rejects with EFAULT when that crosses
     * the end of the heap. Stop on an error rather than adding -1. */
    while (size < len && (rbytes = libc.read(fd,s+size,len-size)) > 0) {
        size += rbytes;
    }

    if (size != len) {
        libc.close(fd);
        mem.free(s);
        return out;
    }

    s[len] = '\0';
    libc.close(fd);
    out = str.adopt(s, len);
    return out;
}

/* data into the file at path (made with 0644 when flags create it); false
 * on an error */
pub bool writeAll(u8[] path, u8[] data,
        i64 flags=libc.O_CREAT|libc.O_WRONLY|libc.O_TRUNC) {
    u8 cpath[FS_PATH_MAX];
    if (!fsPath(path, cpath)) { return false; }
    i64 towrite = data.len, nwritten = 0;
    u8 *buf = data.ptr;
    i32 fd;
    i32 perms = 420;

    if ((fd = libc.open(cpath,flags|libc.O_CLOEXEC,perms)) == -1) {
        return false;
    }

    while (towrite > 0) {
        nwritten = libc.write(fd,buf,towrite);
        if (nwritten < 0) {
            libc.close(fd);
            return false;
        }
        buf += nwritten;
        towrite -= nwritten;
    }
    libc.close(fd);
    return true;
}

static i32 changeDirC(u8 *path) {
    u8 *newPath = null;
    u64 len = cstr.len(path);
    if ((len == 1 && path[0] == '~') ||
            (len == 2 && path[0] == '~' && path[1] == '/')) {
        u8 *home = libc.getenv("HOME");
        home = cstr.dup(home);
        newPath = home;
    } else if (len >= 3 && path[0] == '~' && path[1] == '/') {
        u8 *home = libc.getenv("HOME");
        u8 *p = path;
        if (*(p + 2) != '\0') {
            newPath = cstr.formatTo(,"%s/%s",home,p+2);
        } else {
            newPath = cstr.formatTo(,"%s",home);
        }
    } else {
        newPath = path;
    }
    i32 retVal = libc.chdir(newPath);
    if (newPath != path) {
        mem.free(newPath);
    }
    return retVal;
}

/* The working directory, a String that is yours; its ptr is null on an
 * error (getcwd's own block is libc malloc'd, which a String can't own) */
pub String currentDir() {
    String out;
    u8 *cwd = libc.getcwd(null, 0);
    if (!cwd) { return out; }
    out = str.from(cwd[0:cstr.len(cwd)]);
    libc.free(cwd);
    return out;
}

/* `ls`-style entry colours. */
#define DIR_COL_DIR "\033[1;34m"  /* directory  - bold blue  */

#define DIR_COL_LNK "\033[1;36m"  /* symlink    - bold cyan  */

#define DIR_COL_EXE "\033[1;32m"  /* executable - bold green */

#define DIR_COL_END "\033[0m"

struct DirHuman {
    u8 *pathname;
    u32 mode;  /* st_mode: file type + permission bits */
    u8  type;  /* Dirent type - catches symlinks, which Stat() follows */
};

DirHuman *dirHumanNew(u8 *pathname, FileStat *fst, u8 type) {
    DirHuman *dh = mem.alloc(sizeof(DirHuman));
    dh.pathname = pathname;
    dh.mode = fst.st_mode;
    dh.type = type;
    return dh;
}

void dirHumanRelease(void *_dh) {
    DirHuman *dh = _dh;
    if (dh) {
        mem.free(dh.pathname);
        mem.free(dh);
    }
}

/* The colour `ls` would paint this entry, or null for plain. */
u8 *dirHumanColor(DirHuman *dh) {
    if (dh.type == libc.DT_LNK) { return DIR_COL_LNK; }
    if (dh.type == libc.DT_DIR || (dh.mode & libc.S_IFMT) == libc.S_IFDIR) {
        return DIR_COL_DIR;
    }
    if (dh.mode & (libc.S_IXUSR|libc.S_IXGRP|libc.S_IXOTH)) { return DIR_COL_EXE; }
    return null;
}

/* Compare the runs of digits at the start of s1 and s2 by value, however
 * long they are: <0, 0 or >0 */
i64 cmpLeadingNumber(u8 *s1, u8 *s2) {
    i64 n1 = 0, n2 = 0;
    while (*s1 == '0') { s1++; }
    while (*s2 == '0') { s2++; }
    while ('0'<=s1[n1]<='9') { n1++; }
    while ('0'<=s2[n2]<='9') { n2++; }
    if (n1 != n2) { return n1 - n2; }
    return cstr.cmpLen(s1,s2,n1);
}

/* true if dh1 is listed before dh2: names that both start with a number
 * by that number, then everything by the plain name */
i64 cmpDirHuman(void *_dh1, void *_dh2) {
    DirHuman *dh1 = _dh1, *dh2 = _dh2;
    u8 *str1 = dh1.pathname;
    u8 *str2 = dh2.pathname;
    i64 cmp;
    if ('0'<=*str1<='9' && '0'<=*str2<='9') {
        if ((cmp = cmpLeadingNumber(str1,str2)) != 0) { return cmp < 0; }
    }
    return cstr.cmp(str1,str2) < 0;
}

/* What is at path (fst filled): 0, or -1 with errno set */
pub i32 stat(FileStat *fst, u8[] path)
{
    u8 cpath[FS_PATH_MAX];
    if (!fsPath(path, cpath)) { return -1; }
    return statC(fst, cpath);
}

/* The working directory changed to path (`~` and `~/...` are under
 * $HOME): 0, or -1 with errno set */
pub i32 changeDir(u8[] path)
{
    u8 cpath[FS_PATH_MAX];
    if (!fsPath(path, cpath)) { return -1; }
    return changeDirC(cpath);
}
