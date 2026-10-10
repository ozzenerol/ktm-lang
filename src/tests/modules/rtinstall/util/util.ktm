/* A standard library module (in the test install's modules/): it may
 * import rt */
module util;
import rt;

pub i64 fromrt() { return rt.started() + 1; }
