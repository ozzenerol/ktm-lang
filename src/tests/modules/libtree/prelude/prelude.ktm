/* Not built: a directory called prelude at the top of a tree is skipped,
 * the prelude being read by every compile */
module prelude;

pub i64 never() { return 0; }
