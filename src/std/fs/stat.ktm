module fs;
import c, mem, str;

/* 0 if `path` is a directory afterwards (made now or already there) */
static i32 makeDirIfMissing(u8 *path, i32 mode=MAKE_DIR_DEFAULT) {
    c.cStat st;
    if (c.mkdir(path, mode) == 0) {
        return 0;
    }
    if (c.stat(path, &st) == 0 && (st.st_mode & c.S_IFMT) == c.S_IFDIR) {
        return 0;
    }
    return -1;
}

pub i32 removeDir(u8 *path, i64 flags=0) {
    i32 isVerbose = flags & RM_VERBOSE, result,r2;
    i64 pathlen,buflen;
    u8 *buf;
    cDIR *dir;
    c.cStat st;
    Dirent *ent;
    result = -1;

    if (!path) { return -1; }

    if (!(flags & REMOVE_RECURSE)) {
        result = c.rmdir(path);
        if (isVerbose) {
            if (result == 0) { printf("removed dir '%s'\n",path); }
            else             { printf("ERROR removing dir '%s', is it empty?\n",path); }
        }
        return result;
    }

    /* Never follow a symlink: opendir() would empty the directory it
     * points to. Rm removes the link itself. */
    if (c.lstat(path,&st) != 0 || (st.st_mode & c.S_IFMT) == c.S_IFLNK) {
        return result;
    }

    if ((dir = opendir(path)) == null) {
        return result;
    }

    result = 0;
    while (!result && (ent = readdir(dir))) {
        r2 = -1;
        if (!str.cmp(ent.name,".") || !str.cmp(ent.name,"..")) {
            continue;
        }

        buf = str.formatTo(null,"%s/%s",path,ent.name);
        if (buf) {
            if (ent.type == c.DT_DIR) {
                r2 = removeDir(buf,flags);
            } else if (ent.type != c.DT_UNKNOWN) {
                /* Anything but a directory, links included, is unlinked; the
                 * type is a number, not a bit mask (DT_SOCK & DT_DIR != 0) */
                r2 = c.unlink(buf);
                if (isVerbose) {
                    if (r2 == 0) { printf("Rm: %s\n",buf); }
                    else         { printf("Error Rm: %s\n",buf); }
                }
            } else if (!c.lstat(buf,&st)) {
                    if ((st.st_mode & c.S_IFMT) == c.S_IFDIR) {
                        r2 = removeDir(buf,flags);
                    } else {
                        r2 = c.unlink(buf);
                        if (isVerbose) {
                            if (r2 == 0) { printf("Rm: %s\n",buf); }
                            else         { printf("Error Rm: %s\n",buf); }
                        }
                    }
                }
            } else {
                r2 = -1;
                if (isVerbose) {
                    printf("Unknown file type to delete: 0x%X\n",ent.type);
                }

            }
            mem.free(buf);
            result = r2;
    }
    c.closedir(dir);

    if (!result && path) {
        result = c.rmdir(path);
        if (flags & RM_VERBOSE) {
            if (result == 0) {
            printf("RmDir %s\n",path);
            } else {
                printf("ERROR: RmDir %s\n",path);
            }
        }
    }

    return result;
}

pub i32 remove(u8 *path, i64 flags=0)
{ /* DANGER Remove a file or directory, this is powerful but can cause a world 
     * of pain */
    i32 result;
    c.cStat st;

    if (!path) { return -1; }

    /* lstat: a symlink is removed itself, never what it points to */
    if (c.lstat(path,&st) == -1) {
        /* RM_FORCE: like rm -f, a file that does not exist is not an error */
        if (flags & REMOVE_FORCE) { return 0; }
        return -1;
    }

    /* An empty directory, or with RM_RECURSE everything in it */
    if ((st.st_mode & c.S_IFMT) == c.S_IFDIR) {
        return removeDir(path,flags);
    }

    result = c.unlink(path);
    if (flags & RM_VERBOSE) {
        printf("Rm '%s'",path);
        if (result != 0) { printf(" failed\n"); }
        else             { printf("\n"); }
    }
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
        if ((tmp = mem.alloc(str.len(path)+1)) == null) {
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
