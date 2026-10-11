module fs;

#ifdef IS_MACOS
#define DIR_MAXPATHLEN (1024)
struct Dirent {
    u64 ino;           /* file number of entry */
    u64 seekoff;       /* seek offset (optional, used by servers) */
    u16 reclen;        /* length of this record */
    u16 namlen;        /* length of string in d_name */
    u8 type;           /* file type, one of the DT_* above */
    i8 name[DIR_MAXPATHLEN]; /* entry name, NUL-terminated */
};
#endif

#ifdef IS_LINUX
#define DIR_MAXPATHLEN (256)
struct Dirent {
    u64 ino;           /* inode number (d_ino) */
    u64 seekoff;       /* position of the next entry, for seekdir (d_off) */
    u16 reclen;        /* length of this record */
    u8 type;           /* file type, one of the DT_* above; DT_UNKNOWN on
                                            * filesystems that don't report it */
    i8 name[DIR_MAXPATHLEN]; /* entry name, NUL-terminated */
};
pub cDIR *opendir(u8 *dirname);
pub Dirent *readdir(cDIR *dir);
#endif

/* Mac x86 vs ARM symbol-name divergence -- see dir.ktm. */
#ifdef IS_X86_64
pub _extern _opendir$INODE64 cDIR *opendir(u8 *dirname);
pub _extern _readdir$INODE64 Dirent *readdir(cDIR *dir);
#endif

#ifdef IS_ARM_64
pub _extern _opendir cDIR *opendir(u8 *dirname);
pub _extern _readdir Dirent *readdir(cDIR *dir);
#endif

/* Portable (across MacOS and Linux) stat structure */
pub struct Fstat {
    u64 st_dev;
    u64 st_ino;
    u64 st_nlink;
    u32 st_mode;
    u32 st_uid;
    u32 st_gid;
    u64 st_rdev;
    i64 st_size;
    i64 st_blksize;
    i64 st_blocks;
    cTimespec st_atim;
    cTimespec st_mtim;
    cTimespec st_ctim;
    cTimespec st_btim;
};

/* Default MkDir mode, 04775 (rwxrwxr-x; the set-user-ID bit means nothing
 * on a directory), minus the umask: drwxr-xr-x with the usual 022. */
pub const i64 MKDIR_DEFAULT = (S_ISUID|S_IRWXU|S_IRWXG|S_IROTH|S_IXOTH);

pub const i64 RM_RECURSE = 0x1;  /* Remove directories and their contents recursively */

pub const i64 RM_FORCE = 0x4;  /* A path that doesn't exist is not an error for Rm */
