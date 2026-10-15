/* Monocypher (vendor/monocypher, unchanged) compiled into libktmtls with
 * every public name prefixed ktm_mc_ (ktm_monocypher_names.h): crypto's
 * Ed25519 keys use it, as mbedTLS has no Ed25519. Its two files are two
 * units (each has static helpers of the same names): ktmed25519sig.c is
 * the other. */
#include "ktm_monocypher_names.h"
#include "monocypher.c"
