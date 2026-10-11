module fs;

#ifdef IS_MACOS
#define DIR_MAXPATHLEN (1024)
class Dirent
{
  U64 ino;           /* file number of entry */
  U64 seekoff;       /* seek offset (optional, used by servers) */
  U16 reclen;        /* length of this record */
  U16 namlen;        /* length of string in d_name */
  U8 type;           /* file type, one of the DT_* above */
  I8 name[DIR_MAXPATHLEN]; /* entry name, NUL-terminated */
};
#endif

#ifdef IS_LINUX
#define DIR_MAXPATHLEN (256)
class Dirent {
  U64 ino;           /* inode number (d_ino) */
  U64 seekoff;       /* position of the next entry, for seekdir (d_off) */
  U16 reclen;        /* length of this record */
  U8 type;           /* file type, one of the DT_* above; DT_UNKNOWN on
                      * filesystems that don't report it */
  I8 name[DIR_MAXPATHLEN]; /* entry name, NUL-terminated */
};
public cDIR *opendir(U8 *dirname);
public Dirent *readdir(cDIR *dir);
#endif

/* Mac x86 vs ARM symbol-name divergence -- see dir.ktm. */
#ifdef IS_X86_64
public _extern _opendir$INODE64 cDIR *opendir(U8 *dirname);
public _extern _readdir$INODE64 Dirent *readdir(cDIR *dir);
#endif

#ifdef IS_ARM_64
public _extern _opendir cDIR *opendir(U8 *dirname);
public _extern _readdir Dirent *readdir(cDIR *dir);
#endif

/* Portable (across MacOS and Linux) stat structure */
public class Fstat
{
  U64 st_dev;
  U64 st_ino;
  U64 st_nlink;
  U32 st_mode;
  U32 st_uid;
  U32 st_gid;
  U64 st_rdev;
  I64 st_size;
  I64 st_blksize;
  I64 st_blocks;
  cTimespec st_atim;
  cTimespec st_mtim;
  cTimespec st_ctim;
  cTimespec st_btim;
};

/* Default MkDir mode, 04775 (rwxrwxr-x; the set-user-ID bit means nothing
 * on a directory), minus the umask: drwxr-xr-x with the usual 022. */
#define MKDIR_DEFAULT (S_ISUID|S_IRWXU|S_IRWXG|S_IROTH|S_IXOTH)
