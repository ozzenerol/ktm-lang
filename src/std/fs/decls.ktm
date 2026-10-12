module fs;
import libc;

/* Portable (across MacOS and Linux) stat structure */
pub struct FileStat {
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
    libc.timespec st_atim;
    libc.timespec st_mtim;
    libc.timespec st_ctim;
    libc.timespec st_btim;
};

/* What a FileStat describes: FileStat.kind() */
pub enum FileKind { file, dir, link, other };

/* Default fs.makeDir mode, 04775 (rwxrwxr-x; the set-user-ID bit means nothing
 * on a directory), minus the umask: drwxr-xr-x with the usual 022. */
pub const i64 MAKE_DIR_DEFAULT = (libc.S_ISUID|libc.S_IRWXU|libc.S_IRWXG|libc.S_IROTH|libc.S_IXOTH);

pub const i64 REMOVE_RECURSE = 0x1;  /* Remove directories and their contents recursively */

pub const i64 REMOVE_FORCE = 0x4;  /* A path that doesn't exist is not an error for Rm */
