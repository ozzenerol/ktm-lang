/* ktm's changes to mbedTLS's default configuration (mbedtls_config.h),
 * included at its end through MBEDTLS_USER_CONFIG_FILE. */

/* ktm servers run a coroutine loop per thread: the shared random
 * generator and PSA's state need locks */
#define MBEDTLS_THREADING_C
#define MBEDTLS_THREADING_PTHREAD

/* nobody needs these, and they only widen what can go wrong */
#undef MBEDTLS_SSL_RENEGOTIATION
#undef MBEDTLS_DEBUG_C
