import test;

import proc;

/* System and Sh returned the raw wait status from C's system(), so
 * System("exit 3") gave 768. They must return the exit code (0-255),
 * or -1 if the command was killed by a signal or could not be run. */

i32 main() {
    printf("Test - System and Sh exit status: ");
    i64 correct = 0, tests = 14;

    if (proc.shell("exit 0") == 0) { correct++; }
    if (proc.shell("true") == 0) { correct++; }
    if (proc.shell("false") == 1) { correct++; }
    if (proc.shell("exit 3") == 3) { correct++; }
    if (proc.shell("exit 255") == 255) { correct++; }
    if (proc.shell("true | false") == 1) { correct++; }
    /* The shell reports a missing command as 127 */
    if (proc.shell("ktm_no_such_command_202 2>/dev/null") == 127) { correct++; }
    /* Killed by a signal */
    if (proc.shell("kill -9 $$") == -1) { correct++; }
    if (proc.shell("kill -TERM $$") == -1) { correct++; }
    if (proc.shell(null) == -1) { correct++; }

    /* Sh returns the same, with or without format arguments */
    if (proc.shellf("exit 3") == 3) { correct++; }
    if (proc.shellf("exit %d", 255) == 255 && proc.shellf("exit %d", 0) == 0) { correct++; }
    if (proc.shellf("kill -%d $$", 9) == -1) { correct++; }
    if (proc.shellf(null) == -1) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
