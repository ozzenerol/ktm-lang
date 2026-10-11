module proc;

#ifdef IS_X86_64
asm {
_EXIT::
    CALL    exit
    RET
}

public _extern _EXIT U0 Exit(I64 exit_code=EXIT_FAIL);
#endif

#ifdef IS_ARM_64
public U0 Exit(I64 exit_code=EXIT_FAIL)
{
  exit(exit_code);
}
#endif

/* Run `command` with the shell (`sh -c`). Returns the command's exit
 * code (0-255), or -1 if it was killed by a signal or the shell could
 * not be run (also for a NULL command). The shell itself gives 127 for
 * a command it can't find. */
public I64 System(U8 *command)
{
  if (!command) return -1;
  I32 status = system(command);
  if (status == -1) return -1;
  /* `system` returns a wait status: the low 7 bits are the signal that
   * killed the child, the exit code is in bits 8-15 */
  if (status & 0x7F) return -1;
  return (status >> 8) & 0xFF;
}

#ifdef IS_LINUX
extern "c" I32 *__errno_location();
static I32 *ErrnoPtr() { return __errno_location(); }
#else
/* errno is *__error(); the raw name, as ktm doesn't prefix names starting with _ */
_extern ___error I32 *ErrnoPtr();
#endif

/* Call the shell with `printf`-like formatting. Returns what System
 * returns: the exit code (0-255), or -1 if the command was killed by a
 * signal, could not be run, or fmt is NULL. */
public I32 Sh(U8 *fmt, ...)
{
  if (!fmt) return -1;
  /* Formatted even without arguments, so "%%" is always a single % */
  U8 *cmd = StrPrintJoin(NULL,fmt,argc,argv);
  I32 retval = System(cmd);
  Free(cmd);
  return retval;
}

/* s in single quotes for sh, as a new string to Free: each ' becomes
 * '\'' and NULL gives ''. Nothing in it is expanded by the shell, so
 * Sh("ls %s", q) passes s as one word. */
public U8 *ShQuote(U8 *s)
{
  I64 len = 2, i, j = 0;
  U8 *q;
  if (!s) s = "";
  for (i = 0; s[i]; i++) {
    if (s[i] == '\'') len += 4;
    else len++;
  }
  q = MAlloc(len + 1);
  q[j++] = '\'';
  for (i = 0; s[i]; i++) {
    if (s[i] == '\'') {
      MemCpy(q + j, "'\\''", 4);
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
 * jobs it started. NULL if fmt is NULL or the shell can't be started. */
public U8 *Shlurp(U8 *fmt, ...)
{
  if (!fmt) return NULL;
  I64 len = 0;
  U8 *user_cmd = StrPrintJoin(NULL,fmt,argc,argv);
  /* The newline completes a command that ends in a backslash (a line
   * continuation) or in a here-doc delimiter */
  U8 *cmd = StrPrint(NULL,"%s\n",user_cmd);
  Free(user_cmd);
  U8 *output = ProcShellOutput(cmd,&len);
  Free(cmd);
  if (!output) return NULL;
  if (len > 0 && output[len-1] == '\n') {
    output[len-1] = '\0';
  }
  return output;
}
