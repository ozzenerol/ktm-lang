/* The prelude: its pub names are seen by every file, with no import and
 * no prefix (`Error`, not `prelude.Error`). `import prelude;` is an
 * error: it is always imported. */
module prelude;

/* std.ktmh leaves out its own copies of these */
#define __KTM_PRELUDE__

#define EXIT_FAIL 1
#define EXIT_OK   0

/* The smallest and largest value of each integer type */
#define I8_MIN  (-0x7F - 1)
#define I8_MAX  0x7F
#define U8_MAX  0xFF
#define I16_MIN (-0x7FFF - 1)
#define I16_MAX 0x7FFF
#define U16_MAX 0xFFFF
#define I32_MIN (-0x7FFFFFFF - 1)
#define I32_MAX 0x7FFFFFFF
#define U32_MAX 0xFFFFFFFF
#define I64_MIN (-0x7fffffffffffffff - 1)
#define I64_MAX 0x7fffffffffffffff
#define U64_MAX 0xffffffffffffffff

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

/* The code of the Error a failed bounds check throws: an index, or a
 * sub-slice, outside a slice or an array */
#define ERR_BOUNDS 0x6B746D01
