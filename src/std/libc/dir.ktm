module libc;

pub struct cDIR;

#ifdef IS_MACOS
#define DIR_MAXPATHLEN 1024

pub struct Dirent {
    u64  ino;      /* file number of entry */
    u64  seekoff;  /* seek offset (optional, used by servers) */
    u16  reclen;   /* length of this record */
    u16  namlen;   /* length of string in d_name */
    u8   type;     /* file type, see below */
    i8   name[DIR_MAXPATHLEN]; /* entry name (up to MAXPATHLEN bytes) */
};
#endif

#ifdef IS_LINUX
#define DIR_MAXPATHLEN 256
pub struct Dirent {
    u64  ino;      /* file number of entry */
    u64  seekoff;  /* seek offset (optional, used by servers) */
    u16  reclen;   /* length of this record */
    u8   type;     /* file type, see below */
    i8   name[DIR_MAXPATHLEN]; /* entry name (up to MAXPATHLEN bytes) */
};
pub extern "c" cDIR *opendir(u8 *dirname);
pub extern "c" Dirent *readdir(cDIR *dir);
#endif
