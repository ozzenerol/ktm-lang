import test;

import libc, net;

/* NetSetSocketNoDelay returned false when setsockopt succeeded and true
 * when it failed. It must return true on success (and really set
 * TCP_NODELAY) and false on failure. */

i64 getIntOpt(i32 fd, i32 level, i32 opt) {
    i32 val = -1;
    u32 len = sizeof(i32);
    if (libc.getsockopt(fd, level, opt, &val, &len) == -1) { return -1; }
    return val;
}

i32 main() {
    printf("Test - NetSetSocketNoDelay result: ");
    i64 correct = 0, tests = 9;

    i32 fd = libc.socket(libc.AF_INET, libc.SOCK_STREAM, 0);
    if (fd >= 0) { correct++; }

    /* Off by default, on after a successful call that returns true. */
    if (getIntOpt(fd, libc.IPPROTO_TCP, libc.TCP_NODELAY) == 0) { correct++; }
    if (net.setNoDelay(fd) == true) { correct++; }
    if (getIntOpt(fd, libc.IPPROTO_TCP, libc.TCP_NODELAY) != 0) { correct++; }
    /* Setting it again still succeeds. */
    if (net.setNoDelay(fd) == true) { correct++; }

    /* Sibling setters keep their (already correct) results. */
    if (net.setReuseAddr(fd) == true &&
            getIntOpt(fd, libc.SOL_SOCKET, libc.SO_REUSEADDR) != 0) { correct++; }
    if (net.setNonBlocking(fd) == true &&
            (libc.fcntl(fd, libc.F_GETFL, 0) & libc.O_NONBLOCK)) { correct++; }
    libc.close(fd);

    /* Failure: an invalid and a closed descriptor give false. */
    if (net.setNoDelay(-1) == false) { correct++; }
    if (net.setNoDelay(fd) == false &&
            net.setReuseAddr(fd) == false &&
            net.setNonBlocking(fd) == false) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
