/* The prelude: its pub names are seen by every file, with no import and
 * no prefix (`Error`, not `prelude.Error`). `import prelude;` is an
 * error: it is always imported. */
module prelude;

/* std.ktmh leaves out its own copies of these */
#define __KTM_PRELUDE__

#define EXIT_FAIL 1
#define EXIT_OK   0

#define I64_MIN (-0x7fffffffffffffff - 1)
#define I64_MAX 0x7fffffffffffffff
#define U64_MAX 0xffffffffffffffff
#define U8_MAX  0xFF
#define I8_MAX  0x7F
#define I8_MIN  0xFFFFFF80

/* What a throw carries and `catch (Error e)` binds: the code (usually an
 * enum member), a message (never NULL, "" without one), and the file and
 * line of the throw. The message is freed when the catch block is left:
 * copy it (StrNew) to keep it. */
pub struct Error
{
  i64 code;
  u8 *message;
  u8 *file;
  i32 line;
};
