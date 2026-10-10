/* Test 440's fixture: copied into a test install's modules/ as the
 * runtime, rt. Its code is in every program; no program imports it. */
module rt;

pub i64 started() { return 41; }
