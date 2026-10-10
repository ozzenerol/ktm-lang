module c;

pub struct timespec {
  i64 tv_sec;
  i64 tv_nsec;
};
pub enum clockid { realtime, monotonic };
pub extern "c" i32 clock_gettime(i32 clk, timespec *ts);
