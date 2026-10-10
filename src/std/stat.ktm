#define RM_RECURSE  0x1
#define RM_VERBOSE  0x2
#define RM_FORCE    0x4

/* 0 if `path` is a directory afterwards (made now or already there) */
static I32 MkDirIfNotExist(U8 *path, I32 mode=MKDIR_DEFAULT)
{
  cStat st;
  if (mkdir(path, mode) == 0) {
    return 0;
  }
  if (stat(path, &st) == 0 && (st.st_mode & S_IFMT) == S_IFDIR) {
    return 0;
  }
  return -1;
}

public I32 RmDir(U8 *path, I64 flags=0)
{
  I32 is_verbose = flags & RM_VERBOSE, result,r2;
  I64 pathlen,buflen;
  U8 *buf;
  cDIR *dir;
  cStat st;
  Dirent *ent;
  result = -1;

  if (!path) return -1;

  if (!(flags & RM_RECURSE)) {
    result = rmdir(path);
    if (is_verbose) {
      if (result == 0) "removed dir '%s'\n",path;
      else             "ERROR removing dir '%s', is it empty?\n",path;
    }
    return result;
  }

  /* Never follow a symlink: opendir() would empty the directory it
   * points to. Rm removes the link itself. */
  if (lstat(path,&st) != 0 || (st.st_mode & S_IFMT) == S_IFLNK) {
    return result;
  }

  if ((dir = opendir(path)) == NULL) {
    return result;
  }

  result = 0;
  while (!result && (ent = readdir(dir))) {
    r2 = -1;
    if (!StrCmp(ent->name,".") || !StrCmp(ent->name,"..")) {
      continue;
    }

    buf = StrPrint(NULL,"%s/%s",path,ent->name);
    if (buf) {
      if (ent->type == DT_DIR) {
        r2 = RmDir(buf,flags);
      } else if (ent->type != DT_UNKNOWN) {
        /* Anything but a directory, links included, is unlinked; the
         * type is a number, not a bit mask (DT_SOCK & DT_DIR != 0) */
        r2 = unlink(buf);
        if (is_verbose) {
          if (r2 == 0) "Rm: %s\n",buf;
          else         "Error Rm: %s\n",buf;
        }
      } else if (!lstat(buf,&st)) {
          if ((st.st_mode & S_IFMT) == S_IFDIR) {
            r2 = RmDir(buf,flags);
          } else {
            r2 = unlink(buf);
            if (is_verbose) {
              if (r2 == 0) "Rm: %s\n",buf;
              else         "Error Rm: %s\n",buf;
            }
          }
        }
      } else {
        r2 = -1;
        if (is_verbose) {
          "Unknown file type to delete: 0x%X\n",ent->type;
        }

      }
      Free(buf);
      result = r2;
  }
  closedir(dir);

  if (!result && path) {
    result = rmdir(path);
    if (flags & RM_VERBOSE) {
      if (result == 0) {
      "RmDir %s\n",path;
      } else {
        "ERROR: RmDir %s\n",path;
      }
    }
  }

  return result;
}

public I32 Rm(U8 *path, I64 flags=0)
{ /* DANGER Remove a file or directory, this is powerful but can cause a world 
   * of pain */
  I32 result;
  cStat st;

  if (!path) return -1;

  /* lstat: a symlink is removed itself, never what it points to */
  if (lstat(path,&st) == -1) {
    /* RM_FORCE: like rm -f, a file that does not exist is not an error */
    if (flags & RM_FORCE) return 0;
    return -1;
  }

  /* An empty directory, or with RM_RECURSE everything in it */
  if ((st.st_mode & S_IFMT) == S_IFDIR) {
    return RmDir(path,flags);
  }

  result = unlink(path);
  if (flags & RM_VERBOSE) {
    "Rm '%s'",path;
    if (result != 0) " failed\n";
    else             "\n";
  }
  return result;
}

public I32 MkDir(U8 *path, I32 mode=MKDIR_DEFAULT, Bool recurse=FALSE)
{ /* Make a directory, by default does not recurse. The mode is
   * MKDIR_DEFAULT minus the umask. Returns 0 if path is a directory
   * afterwards, -1 if not */
  Bool relative = TRUE;
  U8 *ptr,*tmp;
  I64 i;
  I32 result = 0;
  if (path == NULL || *path == '\0') return -1;
  if (path[0] == '/') {
    relative = FALSE;
  }

  if (recurse == FALSE) {
    return MkDirIfNotExist(path,mode);
  } else {
    /* Each prefix of `path` is copied here, so it needs path's length */
    if ((tmp = MAlloc(StrLen(path)+1)) == NULL) {
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
        if (MkDirIfNotExist(tmp,mode) != 0) {
          Free(tmp);
          return -1;
        } 
      }
      ptr++;
    }
    if (*ptr == '\0' && *(ptr -1) != '/') {
      tmp[i] = '\0';
      result = MkDirIfNotExist(tmp,mode);
    }
    Free(tmp);
  }
  return result;
}
