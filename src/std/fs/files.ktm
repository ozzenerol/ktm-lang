module fs;
import cstr, libc, mem, os, str, vec;

/* Directories, paths and whole-file operations: openDir/next to read a
 * directory, list() its names, walk() a tree, join() paths, copyFile(),
 * rename() and move(), exists(), makeDirAll(). */

/* One entry of a directory. `kind` is what the entry itself is: a
 * symlink is a link, never what it points to. `name` and `path` (the
 * directory's path, a `/`, the name) are the Dir's own: they are valid
 * until its next next() or close(). */
pub struct DirEntry {
    u8[] name;
    FileKind kind;
    String path;
};

/* An open directory: fs.openDir(path), next() until it gives null, then
 * close() (`defer d.close();`, or declare it with `ad`). "." and ".."
 * are left out. Its fields are its own: use ok(), next() and close(). */
pub struct Dir {
    libc.DIR *handle;
    String base;        /* the directory's path and a `/` */
    DirEntry entry;
};

/* What a readdir() type says, an lstat() when it doesn't */
static FileKind entryKind(u8 type, u8 *path) {
    if (type == libc.DT_DIR) { return FileKind.dir; }
    if (type == libc.DT_REG) { return FileKind.file; }
    if (type == libc.DT_LNK) { return FileKind.link; }
    if (type != libc.DT_UNKNOWN) { return FileKind.other; }
    FileStat st;
    if (lstat(&st, path[0:cstr.len(path)]) != 0) { return FileKind.other; }
    return st.kind();
}

/* The directory at `path`, open; ok() is false when it can't be opened
 * (os.errno() says why), and next() then gives null */
pub Dir openDir(u8[] path) {
    Dir d;
    mem.set(&d, 0, sizeof(Dir));
    u8 cpath[FS_PATH_MAX];
    d.base = str.from(path);
    if (path.len && path[path.len - 1] != '/') { d.base.append("/"); }
    d.entry.path = str.withCap(d.base.len + 64);
    if (!fsPath(path, cpath)) { return d; }
    d.handle = libc.opendir(cpath);
    return d;
}

/* Is the directory open? */
pub bool Dir.ok() {
    return self.handle != null;
}

/* The next entry, null after the last one (or when it isn't open) */
pub DirEntry *Dir.next() {
    if (!self.handle) { return null; }
    libc.Dirent *ent;
    while ((ent = libc.readdir(self.handle)) != null) {
        u8 *name = (u8 *)ent.name;
        if (!cstr.cmp(name, ".") || !cstr.cmp(name, "..")) { continue; }
        i64 n = cstr.len(name);
        self.entry.path.clear();
        self.entry.path.append(self.base);
        self.entry.path.append(name[0:n]);
        self.entry.name = (self.entry.path.ptr + self.base.len)[0:n];
        self.entry.kind = entryKind(ent.type, self.entry.path.cstr());
        return &self.entry;
    }
    return null;
}

/* Closes it (once is enough; again does nothing) */
pub void Dir.close() {
    if (self.handle) { libc.closedir(self.handle); }
    self.handle = null;
    self.base.free();
    self.entry.path.free();
}

/* For `ad`: the same as close() */
pub void Dir.free() {
    self.close();
}

static i64 compareNames(String *a, String *b) {
    return str.compare(*a, *b);
}

/* The names in the directory at `path`, sorted; *ok (when given) is
 * false when it can't be read. Yours: free it with names.free(). */
pub vec.Vec<String> list(u8[] path, bool *ok = null) {
    vec.Vec<String> names = vec.new<String>();
    Dir d = openDir(path);
    defer d.close();
    if (ok) { *ok = d.ok(); }
    DirEntry *e;
    while ((e = d.next()) != null) {
        names.push(str.from(e.name));
    }
    names.sort(&compareNames);
    return names;
}

/* Frees what list() gave: the same as names.free(), which frees the
 * names too */
pub void freeList(vec.Vec<String> *names) {
    names.free();
}

static i32 walkDir(u8[] path, fn(DirEntry *, void *) bool visit, void *arg) {
    Dir d = openDir(path);
    defer d.close();
    if (!d.ok()) { return -1; }
    DirEntry *e;
    while ((e = d.next()) != null) {
        if (!visit(e, arg)) { return 1; }
        if (e.kind == FileKind.dir) {
            String sub = e.path.clone();
            i32 r = walkDir(sub, visit, arg);
            sub.free();
            if (r != 0) { return r; }
        }
    }
    return 0;
}

/* Every entry under the directory `root`, at any depth: visit(entry, arg)
 * for each, a directory before what is in it, without following
 * symlinks (a link to a directory is visited, not entered). visit
 * returns false to stop. 0 when every entry was visited, 1 when visit
 * stopped it, -1 when a directory couldn't be read (os.errno() says
 * why). */
pub i32 walk(u8[] root, fn(DirEntry *, void *) bool visit, void *arg = null) {
    return walkDir(root, visit, arg);
}

/* `a/b`: one `/` between them; b alone when it is absolute or a is empty */
pub String join(u8[] a, u8[] b) {
    if (a.len == 0 || (b.len && b[0] == '/')) { return str.from(b); }
    String out = str.withCap(a.len + b.len + 1);
    out.append(a);
    if (a[a.len - 1] != '/' && b.len) { out.append("/"); }
    out.append(b);
    return out;
}

/* `a/b/c`: join(join(a, b), c) */
pub String join(u8[] a, u8[] b, u8[] c) {
    String ab = join(a, b);
    String out = join(ab, c);
    ab.free();
    return out;
}

/* Is there something at `path` (a symlink: is there something where it
 * points)? */
pub bool exists(u8[] path) {
    FileStat st;
    return stat(&st, path) == 0;
}

/* The directory `path` made, with every missing parent, as
 * makeDir(path, mode, true): 0 when `path` is a directory afterwards
 * (also when it already was), -1 when not */
pub i32 makeDirAll(u8[] path, i32 mode = MAKE_DIR_DEFAULT) {
    return makeDir(path, mode, true);
}

/* The regular file `from` copied to `to` (made, or emptied first), with
 * from's permission bits. 0, or -1 with os.errno(): `from` isn't a
 * regular file, the two are the same file, a read or a write failed
 * (`to` is then removed). */
pub i32 copyFile(u8[] from, u8[] to) {
    u8 cfrom[FS_PATH_MAX];
    u8 cto[FS_PATH_MAX];
    if (!fsPath(from, cfrom) || !fsPath(to, cto)) { return -1; }
    i32 in = libc.open(cfrom, libc.O_RDONLY | libc.O_CLOEXEC, 0);
    if (in == -1) { return -1; }
    FileStat src;
    FileStat dst;
    if (fstat(&src, in) != 0) {
        libc.close(in);
        return -1;
    }
    if (src.kind() != FileKind.file) {
        libc.close(in);
        os.setErrno(src.kind() == FileKind.dir ? libc.EISDIR : libc.EINVAL);
        return -1;
    }
    /* copying a file onto itself would empty it */
    if (stat(&dst, to) == 0 && dst.st_dev == src.st_dev && dst.st_ino == src.st_ino) {
        libc.close(in);
        os.setErrno(libc.EINVAL);
        return -1;
    }
    i32 out = libc.open(cto, libc.O_WRONLY | libc.O_CREAT | libc.O_TRUNC | libc.O_CLOEXEC,
                        (i32)(src.st_mode & 0x1FF));
    if (out == -1) {
        libc.close(in);
        return -1;
    }
    i64 cap = 65536;
    u8 *buf = mem.alloc(cap);
    i64 got;
    i32 result = 0;
    while (result == 0 && (got = libc.read(in, buf, cap)) != 0) {
        if (got < 0) {
            if (os.errno() == libc.EINTR) { continue; }
            result = -1;
            break;
        }
        i64 done = 0;
        while (done < got) {
            i64 w = libc.write(out, buf + done, got - done);
            if (w < 0) {
                if (os.errno() == libc.EINTR) { continue; }
                result = -1;
                break;
            }
            done += w;
        }
    }
    mem.free(buf);
    libc.close(in);
    if (libc.close(out) != 0) { result = -1; }
    if (result != 0) {
        i32 err = os.errno();
        libc.unlink(cto);
        os.setErrno(err);
    }
    return result;
}

/* `from` renamed to `to` (replacing a file there), on one filesystem: 0,
 * or -1 with os.errno() (EXDEV across filesystems: see move) */
pub i32 rename(u8[] from, u8[] to) {
    u8 cfrom[FS_PATH_MAX];
    u8 cto[FS_PATH_MAX];
    if (!fsPath(from, cfrom) || !fsPath(to, cto)) { return -1; }
    return libc.rename(cfrom, cto);
}

/* As rename(), and across filesystems a regular file is copied, then
 * removed: 0, or -1 with os.errno() (a directory can't move across
 * filesystems) */
pub i32 move(u8[] from, u8[] to) {
    if (rename(from, to) == 0) { return 0; }
    if (os.errno() != libc.EXDEV) { return -1; }
    FileStat st;
    if (lstat(&st, from) != 0) { return -1; }
    if (st.kind() != FileKind.file) {
        os.setErrno(libc.EXDEV);
        return -1;
    }
    if (copyFile(from, to) != 0) { return -1; }
    return remove(from);
}
