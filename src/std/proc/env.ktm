module proc;
import libc, mem, os, str;

/* The environment, this program's own path, the CPU count, and what a
 * wait status from libc.waitpid means */

/* The environment variable's value, a copy; empty when it isn't set (or
 * is set to ""): hasEnv tells them apart */
pub String getenv(u8[] name)
{
    String n = str.from(name), v;
    defer n.free();
    u8 *p = libc.getenv(n.cstr());
    if (p) { v.append(p[0:libc.strlen(p)]); }
    return v;
}

pub bool hasEnv(u8[] name)
{
    String n = str.from(name);
    defer n.free();
    return libc.getenv(n.cstr()) != null;
}

/* Set (or replace) an environment variable; programs started after this
 * see it. False when the name is empty or has a '=' (errno EINVAL) */
pub bool setenv(u8[] name, u8[] value)
{
    String n = str.from(name), v = str.from(value);
    defer n.free();
    defer v.free();
    return libc.setenv(n.cstr(), v.cstr(), 1) == 0;
}

pub bool unsetenv(u8[] name)
{
    String n = str.from(name);
    defer n.free();
    return libc.unsetenv(n.cstr()) == 0;
}

#ifdef IS_MACOS
/* the raw Mach-O name: ktm doesn't prefix names starting with _ */
_extern __NSGetExecutablePath i32 procExecutablePath(u8 *buf, u32 *size);
const i32 PROC_SC_NPROCESSORS_ONLN = 58;
#else
const i32 PROC_SC_NPROCESSORS_ONLN = 84;
#endif

/* The absolute path of the running program's executable, symbolic links
 * resolved; empty if the system can't say. (Under ktm -jit it is ktm's.) */
pub String selfPath()
{
    String out;
    u8 buf[4096];
#ifdef IS_MACOS
    u32 size = sizeof(buf);
    if (procExecutablePath(buf, &size) != 0) { return out; }
#else
    i64 n = libc.readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    if (n <= 0) { return out; }
    buf[n] = 0;
#endif
    u8 *real = libc.realpath(buf, null);
    if (!real) { return out; }
    out.append(real[0:libc.strlen(real)]);
    libc.free(real);
    return out;
}

/* The number of CPUs online (at least 1) */
pub i64 cpuCount()
{
    i64 n = libc.sysconf(PROC_SC_NPROCESSORS_ONLN);
    return n > 0 ? n : 1;
}

/* ---- a status from libc.waitpid (the WIFEXITED family; the same
 * encoding on Linux and macOS) ---- */

/* It exited by itself: exitCode(st) is its code */
pub bool exited(i32 st) { return (st & 0x7F) == 0; }

/* A signal ended it: termSignal(st) is which */
pub bool signaled(i32 st) { return (st & 0x7F) != 0 && (st & 0x7F) != 0x7F; }

/* It was stopped (waitpid with WUNTRACED): stopSignal(st) is which */
pub bool stopped(i32 st) { return (st & 0xFF) == 0x7F; }

pub i64 exitCode(i32 st) { return (st >> 8) & 0xFF; }

pub i64 termSignal(i32 st) { return st & 0x7F; }

pub i64 stopSignal(i32 st) { return (st >> 8) & 0xFF; }

/* The status as a shell gives it in $?: the exit code, or 128 + the
 * signal that ended or stopped it */
pub i64 statusCode(i32 st)
{
    if (stopped(st)) { return 128 + stopSignal(st); }
    if (signaled(st)) { return 128 + termSignal(st); }
    return exitCode(st);
}
