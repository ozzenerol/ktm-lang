import cstr, fs, libc, mem, os, proc, test;

/* ENOENT, EACCES and ECHILD were missing although the proc.spawn docs name
 * them; so were ENETUNREACH, EHOSTUNREACH, EADDRNOTAVAIL, EMFILE, ENFILE
 * and the job-control signals SIGCONT, SIGTSTP, SIGSTOP, SIGTTIN and
 * SIGTTOU. Every errno and signal constant in std.ktmh is compared with the
 * platform's C headers (a C program compiled here), and the new ones are
 * checked against what the system really does where that is cheap. */

#define EC_C   "398-consts.c"
#define EC_EXE "398-consts"
#define EC_OUT "398-consts.out"
#define EC_NOX "398-noexec"

const i64 N_CONSTS = 37;

u8 *gNames[N_CONSTS];
i64 gValues[N_CONSTS];
i64 gN;

void add(u8 *name, i64 value) {
    gNames[gN] = name;
    gValues[gN] = value;
    gN++;
}

/* Every constant, in the order the C program prints them. */
void fill() {
    gN = 0;
    add("ENOENT", libc.ENOENT); add("EINTR", libc.EINTR); add("EBADF", libc.EBADF);
    add("ECHILD", libc.ECHILD); add("EACCES", libc.EACCES); add("EINVAL", libc.EINVAL);
    add("ENFILE", libc.ENFILE); add("EMFILE", libc.EMFILE); add("EPIPE", libc.EPIPE);
    add("EAGAIN", libc.EAGAIN); add("EWOULDBLOCK", libc.EWOULDBLOCK);
    add("EINPROGRESS", libc.EINPROGRESS); add("EADDRINUSE", libc.EADDRINUSE);
    add("EADDRNOTAVAIL", libc.EADDRNOTAVAIL); add("ENETUNREACH", libc.ENETUNREACH);
    add("ECONNRESET", libc.ECONNRESET); add("ETIMEDOUT", libc.ETIMEDOUT);
    add("ECONNREFUSED", libc.ECONNREFUSED); add("EHOSTUNREACH", libc.EHOSTUNREACH);
    add("SIGHUP", libc.SIGHUP); add("SIGINT", libc.SIGINT); add("SIGQUIT", libc.SIGQUIT);
    add("SIGKILL", libc.SIGKILL); add("SIGUSR1", libc.SIGUSR1); add("SIGUSR2", libc.SIGUSR2);
    add("SIGPIPE", libc.SIGPIPE); add("SIGALRM", libc.SIGALRM); add("SIGTERM", libc.SIGTERM);
    add("SIGCHLD", libc.SIGCHLD); add("SIGCONT", libc.SIGCONT); add("SIGSTOP", libc.SIGSTOP);
    add("SIGTSTP", libc.SIGTSTP); add("SIGTTIN", libc.SIGTTIN); add("SIGTTOU", libc.SIGTTOU);
    add("SA_RESTART", libc.SA_RESTART); add("SA_RESETHAND", libc.SA_RESETHAND);
    add("SA_NODEFER", libc.SA_NODEFER);
}

/* Compile and run a C program printing "NAME value" for each constant;
 * the number of mismatches (-1 if it can't run). */
i64 compareWithC() {
    u8 *src = null, *out, *line, *sp;
    i64 i, bad = 0, n = 0;
    src = cstr.appendFormat(src, "#include <errno.h>\n#include <signal.h>\n#include <stdio.h>\n"
                                            "int main(void) {\n");
    for (i = 0; i < gN; i++) {
        src = cstr.appendFormat(src, "  printf(\"%s %%ld\\n\", (long)%s);\n", gNames[i], gNames[i]);
    }
    src = cstr.appendFormat(src, "  return 0;\n}\n");
    fs.writeAll(EC_C, src, cstr.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    mem.free(src);
    if (proc.shell("cc -w -o " EC_EXE " " EC_C " > " EC_OUT " 2>&1 && ./" EC_EXE " > " EC_OUT) != 0) {
        printf("could not build the C program\n");
        return -1;
    }
    out = fs.readAll(EC_OUT);
    if (!out) { return -1; }
    line = out;
    for (i = 0; i < gN && line && *line; i++) {
        sp = cstr.firstOf(line, " ");
        if (!sp || cstr.toInt(sp + 1) != gValues[i]) {
            printf("%s: std.ktmh %d, C %d\n", gNames[i], gValues[i], cstr.toInt(sp + 1));
            bad++;
        }
        n++;
        line = cstr.firstOf(line, "\n");
        if (line) { line++; }
    }
    mem.free(out);
    proc.shell("rm -f " EC_C " " EC_EXE " " EC_OUT);
    if (n != gN) { return -1; }
    return bad;
}

/* waitpid's WCONTINUED flag and the status of a continued child */
#ifdef IS_LINUX
const i64 WCONTINUED_ = 8;
const i64 CONTINUED_STATUS = 0xffff;
#else
const i64 WCONTINUED_ = 0x10;
const i64 CONTINUED_STATUS = 0x137f;
#endif

/* waitpid(pid, flags | WNOHANG) for up to 5 s: a discarded signal must
 * fail the test, not hang it */
bool waitFor(i32 pid, i32 *st, i32 flags) {
    for (i64 i = 0; i < 500; i++) {
        if (libc.waitpid(pid, st, flags | 1) == pid) { return true; }
        libc.poll(null, 0, 10);
    }
    printf("no status change for pid %d\n", pid);
    return false;
}

/* sig stops a running child (the kernel reports it as the stop signal)
 * and SIGCONT makes it run again. The child has its own process group:
 * in an orphaned group (no parent outside it in the session, as under
 * ssh) SIGTSTP, SIGTTIN and SIGTTOU are discarded. */
bool stopsAndContinues(i32 pid, i32 sig) {
    i32 st = 0;
    libc.kill(pid, sig);
    if (!waitFor(pid, &st, 2)) { return false; }                  /* WUNTRACED */
    if ((st & 0xff) != 0x7f || ((st >> 8) & 0xff) != sig) {
        printf("signal %d: wait status %x\n", sig, st);
        return false;
    }
    libc.kill(pid, libc.SIGCONT);
    if (!waitFor(pid, &st, WCONTINUED_)) { return false; }
    if (st != CONTINUED_STATUS) {
        printf("SIGCONT after %d: wait status %x\n", sig, st);
        return false;
    }
    return true;
}

i32 main() {
    printf("Test - errno and signal constants: ");
    i64 correct = 0, tests = 0, bad;
    u8 *argv[3];
    proc.Opts o;
    proc.Proc *p;
    i32 fd, st;
    libc.sockaddr_in sa;

    fill();
    tests++;
    bad = compareWithC();
    if (bad == 0) { correct++; }
    else { printf("Failed - %d constants differ from C\n", bad); }

    /* ENOENT: a missing file and a missing program */
    os.setErrno(0);
    fd = libc.open("398-no-such-dir/file", libc.O_RDONLY, 0);
    tests++; if (fd == -1 && os.errno() == libc.ENOENT) { correct++; }
    mem.set(&o, 0, sizeof(proc.Opts));
    o.in = o.out = o.err = proc.DEVNULL;
    argv[0] = "ktm-398-no-such-program";
    argv[1] = null;
    p = proc.spawn(argv, &o);
    tests++; if (!p && os.errno() == libc.ENOENT) { correct++; }

    /* EACCES: exec of a file without execute permission (root too) */
    fs.writeAll(EC_NOX, "#!/bin/sh\n", 10, libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    argv[0] = "./" EC_NOX;
    o.noPath = true;
    p = proc.spawn(argv, &o);
    tests++; if (!p && os.errno() == libc.EACCES) { correct++; }
    else { printf("Failed - EACCES: got %d\n", os.errno()); }
    fs.remove(EC_NOX);
    o.noPath = false;

    /* EADDRNOTAVAIL: bind to an address this host doesn't have (TEST-NET-1) */
    fd = libc.socket(libc.AF_INET, libc.SOCK_STREAM, 0);
    mem.set(&sa, 0, sizeof(libc.sockaddr_in));
    sa.sin_family = libc.AF_INET;
    libc.inet_pton(libc.AF_INET, "192.0.2.1", &sa.sin_addr);
    os.setErrno(0);
    tests++;
    if (libc.bind(fd, (libc.sockaddr *)(&sa), sizeof(libc.sockaddr_in)) == -1 &&
            os.errno() == libc.EADDRNOTAVAIL) { correct++; }
    else { printf("Failed - EADDRNOTAVAIL: got %d\n", os.errno()); }
    libc.close(fd);

    /* the job-control signals do what their names say */
    argv[0] = "sleep";
    argv[1] = "10";
    argv[2] = null;
    o.newGroup = true;
    p = proc.spawn(argv, &o);
    tests++;
    if (p && stopsAndContinues(p.pid, libc.SIGSTOP) && stopsAndContinues(p.pid, libc.SIGTSTP) &&
            stopsAndContinues(p.pid, libc.SIGTTIN) && stopsAndContinues(p.pid, libc.SIGTTOU)) {
        correct++;
            } else { printf("Failed - stop/continue signals\n"); }
    if (p) {
        proc.kill(p, libc.SIGKILL);
        proc.wait(p);
        tests++; if (p.termSignal == libc.SIGKILL) { correct++; }
        proc.release(p);
    }

    /* ECHILD: nothing left to wait for */
    os.setErrno(0);
    tests++; if (libc.waitpid(-1, &st, 1) == -1 && os.errno() == libc.ECHILD) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
