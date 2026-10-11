module fs;

/* Mac libc has a $INODE64 suffix on opendir/readdir for x86 64-bit
 * builds (legacy of the i386 -> x86_64 transition). On Apple ARM the
 * suffix doesn't seem to exist. */
#ifdef IS_X86_64
pub _extern _opendir$INODE64 cDIR *opendir(u8 *dirname);
pub _extern _readdir$INODE64 Dirent *readdir(cDIR *dir);
#endif

#ifdef IS_ARM_64
pub _extern _opendir cDIR *opendir(u8 *dirname);
pub _extern _readdir Dirent *readdir(cDIR *dir);
#endif
