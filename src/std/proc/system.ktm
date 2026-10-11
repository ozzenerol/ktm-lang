module proc;
import libc, mem, str;

/* Run `command` with the shell (`sh -c`). Returns the command's exit
 * code (0-255), or -1 if it was killed by a signal or the shell could
 * not be run (also for a null command). The shell itself gives 127 for
 * a command it can't find. */
pub i64 shell(u8 *command) {
    if (!command) { return -1; }
    i32 status = libc.system(command);
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
    u8 *cmd = str.formatArgs(null, fmt, args);
    i32 retval = shell(cmd);
    mem.free(cmd);
    return retval;
}

/* s in single quotes for sh, as a new string to Free: each ' becomes
 * '\'' and null gives ''. Nothing in it is expanded by the shell, so
 * Sh("ls %s", q) passes s as one word. */
pub u8 *shellQuote(u8 *s) {
    i64 len = 2, i, j = 0;
    u8 *q;
    if (!s) { s = ""; }
    for (i = 0; s[i]; i++) {
        if (s[i] == '\'') { len += 4; }
        else { len++; }
    }
    q = mem.alloc(len + 1);
    q[j++] = '\'';
    for (i = 0; s[i]; i++) {
        if (s[i] == '\'') {
            mem.copy(q + j, "'\\''", 4);
            j += 4;
        } else {
            q[j++] = s[i];
        }
    }
    q[j++] = '\'';
    q[j] = '\0';
    return q;
}

/* Run a shell command, built with `printf`-like formatting, and return
 * what it printed to stdout in a heap allocated string (Free it). One
 * trailing newline is removed. The exit status is not returned: a
 * failing command gives whatever it printed, often "". stderr is not
 * captured. Returns when the shell exits, not waiting for background
 * jobs it started. null if fmt is null or the shell can't be started. */
pub u8 *shellOutput(u8 *fmt, ...args) {
    if (!fmt) { return null; }
    i64 len = 0;
    u8 *userCmd = str.formatArgs(null, fmt, args);
    /* The newline completes a command that ends in a backslash (a line
     * continuation) or in a here-doc delimiter */
    u8 *cmd = str.formatTo(null,"%s\n",userCmd);
    mem.free(userCmd);
    u8 *output = procShellOutput(cmd,&len);
    mem.free(cmd);
    if (!output) { return null; }
    if (len > 0 && output[len-1] == '\n') {
        output[len-1] = '\0';
    }
    return output;
}
