module fs;
import cstr, libc, mem;

/* 0 if `path` is a directory afterwards (made now or already there) */
static i32 makeDirIfMissing(u8 *path, i32 mode=MAKE_DIR_DEFAULT) {
    libc.cStat st;
    if (libc.mkdir(path, mode) == 0) {
        return 0;
    }
    if (libc.stat(path, &st) == 0 && (st.st_mode & libc.S_IFMT) == libc.S_IFDIR) {
        return 0;
    }
    return -1;
}

pub i32 removeDir(u8 *path, i64 flags=0) {
    i32 result,r2;
    i64 pathlen,buflen;
    u8 *buf;
    libc.DIR *dir;
    libc.cStat st;
    libc.Dirent *ent;
    result = -1;

    if (!path) { return -1; }

    if (!(flags & REMOVE_RECURSE)) {
        result = libc.rmdir(path);
        return result;
    }

    /* Never follow a symlink: opendir() would empty the directory it
     * points to. Rm removes the link itself. */
    if (libc.lstat(path,&st) != 0 || (st.st_mode & libc.S_IFMT) == libc.S_IFLNK) {
        return result;
    }

    if ((dir = libc.opendir(path)) == null) {
        return result;
    }

    result = 0;
    while (!result && (ent = libc.readdir(dir))) {
        r2 = -1;
        if (!cstr.cmp(ent.name,".") || !cstr.cmp(ent.name,"..")) {
            continue;
        }

        buf = cstr.formatTo(null,"%s/%s",path,ent.name);
        if (buf) {
            if (ent.type == libc.DT_DIR) {
                r2 = removeDir(buf,flags);
            } else if (ent.type != libc.DT_UNKNOWN) {
                /* Anything but a directory, links included, is unlinked; the
                 * type is a number, not a bit mask (DT_SOCK & DT_DIR != 0) */
                r2 = libc.unlink(buf);
            } else if (!libc.lstat(buf,&st)) {
                    if ((st.st_mode & libc.S_IFMT) == libc.S_IFDIR) {
                        r2 = removeDir(buf,flags);
                    } else {
                        r2 = libc.unlink(buf);
                    }
                }
            } else {
                r2 = -1;

            }
            mem.free(buf);
            result = r2;
    }
    libc.closedir(dir);

    if (!result && path) {
        result = libc.rmdir(path);
    }

    return result;
}

pub i32 remove(u8 *path, i64 flags=0)
{ /* DANGER Remove a file or directory, this is powerful but can cause a world 
     * of pain */
    i32 result;
    libc.cStat st;

    if (!path) { return -1; }

    /* lstat: a symlink is removed itself, never what it points to */
    if (libc.lstat(path,&st) == -1) {
        /* RM_FORCE: like rm -f, a file that does not exist is not an error */
        if (flags & REMOVE_FORCE) { return 0; }
        return -1;
    }

    /* An empty directory, or with RM_RECURSE everything in it */
    if ((st.st_mode & libc.S_IFMT) == libc.S_IFDIR) {
        return removeDir(path,flags);
    }

    result = libc.unlink(path);
    return result;
}

pub i32 makeDir(u8 *path, i32 mode=MAKE_DIR_DEFAULT, bool recurse=false)
{ /* Make a directory, by default does not recurse. The mode is
     * MKDIR_DEFAULT minus the umask. Returns 0 if path is a directory
     * afterwards, -1 if not */
    bool relative = true;
    u8 *ptr,*tmp;
    i64 i;
    i32 result = 0;
    if (path == null || *path == '\0') { return -1; }
    if (path[0] == '/') {
        relative = false;
    }

    if (recurse == false) {
        return makeDirIfMissing(path,mode);
    } else {
        /* Each prefix of `path` is copied here, so it needs path's length */
        if ((tmp = mem.alloc(cstr.len(path)+1)) == null) {
            return -1;
        }
        ptr = path;
        i = 0;
        if (*ptr == '.' && *(ptr + 1) == '/') {
            ptr += 2;
        }
        while (*ptr) {
            tmp[i++] = *ptr;
            if (i > 1 && *ptr == '/') {
                tmp[i] = '\0';
                if (makeDirIfMissing(tmp,mode) != 0) {
                    mem.free(tmp);
                    return -1;
                } 
            }
            ptr++;
        }
        if (*ptr == '\0' && *(ptr -1) != '/') {
            tmp[i] = '\0';
            result = makeDirIfMissing(tmp,mode);
        }
        mem.free(tmp);
    }
    return result;
}
