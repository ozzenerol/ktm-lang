#include "testhelper.ktm"

/* System and Sh returned the raw wait status from C's system(), so
 * System("exit 3") gave 768. They must return the exit code (0-255),
 * or -1 if the command was killed by a signal or could not be run. */

I32 Main()
{
  "Test - System and Sh exit status: ";
  I64 correct = 0, tests = 14;

  if (System("exit 0") == 0) correct++;
  if (System("true") == 0) correct++;
  if (System("false") == 1) correct++;
  if (System("exit 3") == 3) correct++;
  if (System("exit 255") == 255) correct++;
  if (System("true | false") == 1) correct++;
  /* The shell reports a missing command as 127 */
  if (System("ktm_no_such_command_202 2>/dev/null") == 127) correct++;
  /* Killed by a signal */
  if (System("kill -9 $$") == -1) correct++;
  if (System("kill -TERM $$") == -1) correct++;
  if (System(NULL) == -1) correct++;

  /* Sh returns the same, with or without format arguments */
  if (Sh("exit 3") == 3) correct++;
  if (Sh("exit %d", 255) == 255 && Sh("exit %d", 0) == 0) correct++;
  if (Sh("kill -%d $$", 9) == -1) correct++;
  if (Sh(NULL) == -1) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
