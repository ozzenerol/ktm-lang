module proc;
import cstr, libc, mem, str;

/* Run `command` with the shell (`sh -c`). Returns the command's exit
 * code (0-255), or -1 if it was killed by a signal or the shell could
 * not be run. The shell itself gives 127 for a command it can't find. */
pub i64 shell(u8[] command) {
    String cmd = str.from(command);     /* system(3) wants its NUL */
    i32 status = libc.system(cmd.cstr());
    cmd.free();
    if (status == -1) { return -1; }
    /* `system` returns a wait status: the low 7 bits are the signal that
     * killed the child, the exit code is in bits 8-15 */
    if (status & 0x7F) { return -1; }
    return (status >> 8) & 0xFF;
}

/* Call the shell with `printf`-like formatting. Returns what System
 * returns: the exit code (0-255), or -1 if the command was killed by a
 * signal, could not be run, or fmt is null. */
pub i32 shellf(u8 *fmt, ...args) {
    if (!fmt) { return -1; }
    /* Formatted even without arguments, so "%%" is always a single % */
    u8 *cmd = cstr.formatArgs(null, fmt, args);
    i32 retval = shell(cstr.view(cmd));
    mem.free(cmd);
    return retval;
}

/* s in single quotes for sh, as a new String (yours): each ' becomes
 * '\'' and an empty s gives ''. Nothing in it is expanded by the shell,
 * so proc.shellf("ls %s", q) passes s as one word. */
pub String shellQuote(u8[] s) {
    String q = str.withCap(s.len + 2);
    q.appendByte('\'');
    for (auto c : s) {
        if (c == '\'') {
            q.append("'\\''");
        } else {
            q.appendByte(c);
        }
    }
    q.appendByte('\'');
    return q;
}

/* Run a shell command, built with `printf`-like formatting, and return
 * what it printed to stdout as a new String (yours). One trailing newline
 * is removed. The exit status is not returned: a failing command gives
 * whatever it printed, often "". stderr is not captured. Returns when the
 * shell exits, not waiting for background jobs it started. An empty
 * String with a null ptr if fmt is null or the shell can't be started. */
pub String shellOutput(u8 *fmt, ...args) {
    String none;
    if (!fmt) { return none; }
    i64 len = 0;
    u8 *userCmd = cstr.formatArgs(null, fmt, args);
    /* The newline completes a command that ends in a backslash (a line
     * continuation) or in a here-doc delimiter */
    u8 *cmd = cstr.formatTo(null,"%s\n",userCmd);
    mem.free(userCmd);
    u8 *output = procShellOutput(cmd,&len);
    mem.free(cmd);
    if (!output) { return none; }
    if (len > 0 && output[len-1] == '\n') {
        output[--len] = '\0';
    }
    return str.adopt(output, len);
}
