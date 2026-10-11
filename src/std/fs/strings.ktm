module fs;

/* The extension of a file name: from its first `.` that isn't followed by
 * `/` or `.` to the end (".tar.gz" of "archive.tar.gz"), a view of it;
 * empty when it has none */
pub u8[] ext(u8[] name)
{
    for (i64 i = 0; i < name.len; ++i) {
        if (name[i] == '.' && (i + 1 >= name.len || (name[i + 1] != '/' && name[i + 1] != '.'))) {
            return name[i:];
        }
    }
    return name[name.len:];
}

/* The file name without its extension (see ext), a view of it */
pub u8[] cutExt(u8[] name)
{
    return name[:name.len - ext(name).len];
}
