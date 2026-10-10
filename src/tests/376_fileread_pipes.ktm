#include "testhelper.ktm"

/* FileRead only read regular files, up to their size: a FIFO or a pipe
 * gave NULL, and so did /proc files, which report size 0. Now anything
 * but a directory is read until end of file, and *_size is its length. */

extern "c" I32 mkfifo(U8 *path, U32 mode);
extern "c" I32 pipe(I32 *fds);

#define FIFO_PATH "__376_fifo"
#define REG_PATH  "__376_regular"
#define DIR_PATH  "__376_dir"
#define BIG_LEN   10000 /* more than one 4096-byte read */

I32 fifo_fd = -1;

/* BIG_LEN bytes, 'a' to 'z' over and over */
U8 *BigText()
{
  U8 *s = MAlloc(BIG_LEN + 1);
  for (I64 i = 0; i < BIG_LEN; i++) s[i] = 'a' + i % 26;
  s[BIG_LEN] = '\0';
  return s;
}

Bool IsBigText(U8 *s, I64 len)
{
  if (!s || len != BIG_LEN || StrLen(s) != BIG_LEN) return FALSE;
  for (I64 i = 0; i < BIG_LEN; i++) {
    if (s[i] != 'a' + i % 26) return FALSE;
  }
  return TRUE;
}

/* writes the text to the FIFO (opening it waits for the reader), or to
 * the pipe's write end when fifo_fd is set, then closes it */
U0 *Writer(U0 *arg)
{
  U8 *text = BigText();
  I32 fd = fifo_fd;
  if (fd < 0) fd = open(arg(U8 *), O_WRONLY, 0);
  if (fd >= 0) {
    write(fd, text, BIG_LEN);
    close(fd);
  }
  Free(text);
  return NULL;
}

U0 Cleanup()
{
  unlink(FIFO_PATH);
  unlink(REG_PATH);
  rmdir(DIR_PATH);
}

I32 Main()
{
  "Test - FileRead pipes and /proc: ";
  I64 correct = 0, tests = 0, len;
  U8 *s;
  pthread_t *th;
  Cleanup();

  /* /proc: size 0, but has content (Linux only) */
#ifdef IS_MACOS
  tests++; correct++;
#else
  len = -1;
  s = FileRead("/proc/self/status", &len);
  tests++;
  if (s && len > 0 && len == StrLen(s) && !StrNCmp(s, "Name:", 5)) correct++;
  else "/proc/self/status: %s, len %d ", s, len;
  Free(s);
#endif

  /* a FIFO, filled by another thread */
  tests++;
  if (mkfifo(FIFO_PATH, 420) == 0) {
    pthread_create(&th, NULL, &Writer, FIFO_PATH);
    len = -1;
    s = FileRead(FIFO_PATH, &len);
    pthread_join(th);
    if (IsBigText(s, len)) correct++; else "fifo: len %d ", len;
    Free(s);
  } else "mkfifo failed ";

  /* a pipe, through /dev/fd/N */
  I32 fds[2];
  tests++;
  if (pipe(fds) == 0) {
    U8 *path = StrPrint(NULL, "/dev/fd/%d", fds[0]);
    fifo_fd = fds[1];
    pthread_create(&th, NULL, &Writer, NULL);
    len = -1;
    s = FileRead(path, &len);
    pthread_join(th);
    close(fds[0]);
    if (IsBigText(s, len)) correct++; else "pipe: len %d ", len;
    Free(s);
    Free(path);
  } else "pipe failed ";

  /* regular files still read by size, an empty one as "" */
  U8 *text = BigText();
  FileWrite(REG_PATH, text, BIG_LEN);
  s = FileRead(REG_PATH, &len);
  tests++; if (IsBigText(s, len)) correct++; else "regular: len %d ", len;
  Free(s);
  Free(text);
  FileWrite(REG_PATH, "", 0);
  len = -1;
  s = FileRead(REG_PATH, &len);
  tests++; if (s && len == 0 && s[0] == '\0') correct++; else "empty file ";
  Free(s);

  /* a directory or a missing file: NULL and size 0 */
  mkdir(DIR_PATH);
  len = -1;
  s = FileRead(DIR_PATH, &len);
  tests++; if (!s && len == 0) correct++; else "directory read ";
  len = -1;
  s = FileRead("__376_missing", &len);
  tests++; if (!s && len == 0) correct++; else "missing file read ";

  Cleanup();
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
