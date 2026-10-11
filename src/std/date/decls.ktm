module date;

#define CDATE_YEAR_DAYS        365.2425

#define CDATE_YEAR_DAYS_INT    36524250

#define CDATE_BASE_DAY_OF_WEEK 0

/* zone_diff when there is no zone (Str2CDateStruct without %z); no
 * +-hhmm offset can be this. */
#define CDATE_NO_ZONE I64_MIN
