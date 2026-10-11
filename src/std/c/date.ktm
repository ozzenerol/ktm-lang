module c;

public class timeval
{ // c compatability
  I64 tv_sec;     /* seconds since Jan. 1, 1970 */
#ifdef IS_LINUX
  I64 tv_usec;    /* and microseconds (a long) */
#else
  I32 tv_usec;    /* and microseconds (suseconds_t, an int) */
  I32 __pad;      /* C pads the struct to 16 bytes */
#endif
};

public class tm
{ // c compatability
	I32	tm_sec;		/* seconds after the minute [0-60] */
	I32	tm_min;		/* minutes after the hour [0-59] */
	I32	tm_hour;	/* hours since midnight [0-23] */
	I32	tm_mday;	/* day of the month [1-31] */
	I32	tm_mon;		/* months since January [0-11] */
	I32	tm_year;	/* years since 1900 */
	I32	tm_wday;	/* days since Sunday [0-6] */
	I32	tm_yday;	/* days since January 1 [0-365] */
	I32	tm_isdst;	/* Daylight Savings Time flag */
	I64 tm_gmtoff;	/* offset from UTC in seconds */
	I8  *tm_zone;	/* timezone abbreviation */
};

extern "c" tm *localtime(I64 *clock);

extern "c" I32 gettimeofday(timeval *tp, U0 *tzp);
