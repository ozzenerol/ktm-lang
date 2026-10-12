module date;

pub const f64 YEAR_DAYS = 365.2425;

pub const i64 YEAR_DAYS_INT = 36524250;

pub const i64 BASE_DAY_OF_WEEK = 0;

/* zone_diff when there is no zone (date.parseParts without %z); no
 * +-hhmm offset can be this. */
pub const i64 NO_ZONE = I64_MIN;
