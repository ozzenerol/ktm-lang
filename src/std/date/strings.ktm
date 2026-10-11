module date;

#define TIMESTAMP_FMT "%d-%02d-%02d %02d:%02d:%02d"

public U8 *MPrintTime(CDate cdt)
{// @TempleOS
  CDateStruct ds;
  Date2Struct(&ds,cdt);
  return MStrPrint("%02d:%02d:%02d",ds.hour,ds.min,ds.sec);
}

public U8 *MPrintDate(CDate cdt)
{// @TempleOS
  CDateStruct ds;
  Date2Struct(&ds,cdt);
  return MStrPrint("%02d/%02d/%04d",ds.mon,ds.day_of_mon,ds.year);
}

public U8 *MPrintTimestampNow()
{// Get a timestamp
  I64 seconds = NowMilliseconds() / 1000; 
  U8 *timestamp = MAlloc(sizeof(U8)*32);
  auto ptm = localtime(&seconds);
  auto len = snprintf(timestamp,sizeof(U8)*32,TIMESTAMP_FMT,
          ptm->tm_year+1900,ptm->tm_mon+1,ptm->tm_mday,
          ptm->tm_hour,ptm->tm_min,ptm->tm_sec);
  timestamp[len] = '\0';
  return timestamp;
}

static U8 *_str_date_days[7] = {
  "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"
};

static U8 *_str_date_months[12] = {
  "january", "february", "march", "april", "may", "june", "july",
  "august", "september", "october", "november", "december"
};

/* Read up to `max_digits` decimal digits (after any spaces) */
static Bool _StrDateNum(U8 **_s, I64 max_digits, I64 *_out)
{
  U8 *s = *_s;
  I64 v = 0, n = 0;
  while (*s == ' ' || *s == '\t') s++;
  while (n < max_digits && *s >= '0' && *s <= '9') {
    v = v * 10 + (*s - '0');
    s++;
    n++;
  }
  if (n == 0) return FALSE;
  *_s = s;
  *_out = v;
  return TRUE;
}

/* A day or month name: the full name or its first three letters, any
 * case. Returns the index or -1. */
static I64 _StrDateName(U8 **_s, U8 **names, I64 count)
{
  U8 *s = *_s;
  for (I64 i = 0; i < count; ++i) {
    I64 len = StrLen(names[i]);
    if (StrNICmp(s, names[i], len) == 0) {
      *_s = s + len;
      return i;
    }
  }
  for (I64 i = 0; i < count; ++i) {
    if (StrNICmp(s, names[i], 3) == 0) {
      *_s = s + 3;
      return i;
    }
  }
  return -1;
}

/* similar to strptime. Conversions: %Y year, %m month (1-12), %d day,
 * %H hour (0-23, or 1-12 with %p), %M minutes, %s or %S seconds, %a day
 * name, %b month name (full or 3 letters), %p AM/PM, %z zone offset, %%.
 * A space in the format matches any run of spaces. Fields the format
 * doesn't set stay -1, except the time fields, which default to 0, and
 * zone_diff, which is CDATE_NO_ZONE without a %z.
 * Returns FALSE for empty input, input that ends before the format, text
 * that doesn't match the format, an unknown conversion or a field out of
 * range; text after the end of the format is ignored. */
public Bool Str2CDateStruct(U8 *strdate, U8 *format, CDateStruct *t)
{
  I64 year = -1, mon = -1, day = -1, hour = 0, min = 0, sec = 0;
  I64 dow = -1, zone = CDATE_NO_ZONE, v, pm = -1, sign;
  Bool has_hour = FALSE;

  /* initialise the date struct's field to -1, error state */
  MemSet(t, -1, sizeof(CDateStruct));
  t->sec10000 = 0;
  t->sec100 = 0;
  t->sec = 0;
  t->min = 0;
  t->hour = 0;
  t->zone_diff = CDATE_NO_ZONE;

  if (!strdate || !format || !*strdate) {
    return FALSE;
  }

  while (*format) {
    if (*format == '%') {
      format++;
      switch (*format) {
        case 'a': /* day name */
          if ((dow = _StrDateName(&strdate, _str_date_days, 7)) < 0) {
            return FALSE;
          }
          break;
        case 'b': /* month name */
          if ((v = _StrDateName(&strdate, _str_date_months, 12)) < 0) {
            return FALSE;
          }
          mon = v + 1;
          break;
        case 'd': if (!_StrDateNum(&strdate, 2, &day)) return FALSE; break;
        case 'm': if (!_StrDateNum(&strdate, 2, &mon)) return FALSE; break;
        case 'Y': if (!_StrDateNum(&strdate, 4, &year)) return FALSE; break;
        case 'H':
          if (!_StrDateNum(&strdate, 2, &hour)) return FALSE;
          has_hour = TRUE;
          break;
        case 'M': if (!_StrDateNum(&strdate, 2, &min)) return FALSE; break;
        case 's':
        case 'S': if (!_StrDateNum(&strdate, 2, &sec)) return FALSE; break;
        case 'p': /* am or pm */
          while (*strdate == ' ') strdate++;
          if (StrNICmp(strdate, "AM", 2) == 0) {
            pm = 0;
          } else if (StrNICmp(strdate, "PM", 2) == 0) {
            pm = 1;
          } else {
            return FALSE;
          }
          strdate += 2;
          break;
        case 'z': /* timezone difference, e.g. +0100 */
          while (*strdate == ' ') strdate++;
          sign = 1;
          if (*strdate == '+') {
            strdate++;
          } else if (*strdate == '-') {
            sign = -1;
            strdate++;
          }
          if (!_StrDateNum(&strdate, 4, &zone)) return FALSE;
          if (zone / 100 > 23 || zone % 100 > 59) return FALSE;
          zone *= sign;
          break;
        case '%':
          if (*strdate != '%') return FALSE;
          strdate++;
          break;
        default:
          return FALSE;
      }
      format++;
    } else if (*format == ' ') {
      while (*format == ' ') format++;
      while (*strdate == ' ' || *strdate == '\t') strdate++;
    } else {
      if (*strdate != *format) {
        return FALSE;
      }
      strdate++;
      format++;
    }
  }

  if (pm != -1) {
    if (!has_hour || hour < 1 || hour > 12) {
      return FALSE;
    }
    if (pm == 0 && hour == 12) {
      hour = 0;
    } else if (pm == 1 && hour != 12) {
      hour += 12;
    }
  }
  if (hour > 23 || min > 59 || sec > 59) {
    return FALSE;
  }
  if (mon != -1 && (mon < 1 || mon > 12)) {
    return FALSE;
  }
  if (day != -1) {
    I64 max_day = 31;
    if (mon == 4 || mon == 6 || mon == 9 || mon == 11) {
      max_day = 30;
    } else if (mon == 2) {
      max_day = 29;
      if (year != -1 &&
          !((year % 4 == 0 && year % 100 != 0) || year % 400 == 0)) {
        max_day = 28;
      }
    }
    if (day < 1 || day > max_day) {
      return FALSE;
    }
  }

  t->year = year;
  t->mon = mon;
  t->day_of_mon = day;
  t->day_of_week = dow;
  t->hour = hour;
  t->min = min;
  t->sec = sec;
  t->zone_diff = zone;
  return TRUE;
}

public CDate StrToCDate(U8 *_str, U8 *fmt)
{// Parse with Str2CDateStruct into a CDate; 0 if that fails or the
 // format doesn't give the year, month and day
  CDateStruct ds;
  if (!Str2CDateStruct(_str,fmt,&ds)) {
    return 0;
  }
  if (ds.year(I64) == -1 || ds.mon(I64) == -1 || ds.day_of_mon(I64) == -1) {
    return 0;
  }
  return Struct2Date(&ds);
}
