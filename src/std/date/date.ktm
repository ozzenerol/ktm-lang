module date;
import libc, math, str;

pub i64 struct CDate {
  u32 time;
  i32 date;
}

;

pub struct CDateStruct {
  u64 sec10000,sec100,sec,min,hour,
    day_of_week,day_of_mon,mon;
  i32 year;
  i64 zone_diff;
};

u64 monStartDays1[12]={
  0,31,59,90,120,151,181,212,243,273,304,334};

u64 monStartDays2[12]={
  0,31,60,91,121,152,182,213,244,274,305,335};

pub u64 nowMs() {
  libc.timeval tm;
  libc.gettimeofday(&tm,null);
  return (tm.tv_sec*1000) +
         (tm.tv_usec/1000);
}

pub void nowParts(CDateStruct *_ds) {
  libc.timeval _tv;
  libc.tm *ptm;
  u64 milliseconds,seconds;
  i64 gmtoff;

  libc.gettimeofday(&_tv,null);
  milliseconds = (_tv.tv_sec*1000) +
                 (_tv.tv_usec/1000);
  seconds = milliseconds / 1000;
  ptm = libc.localtime(&seconds);

  /* sec100 is the hundredths, sec10000 the ten-thousandths below them
   * (0-99 each), as Struct2Date and Date2Struct use them */
  _ds.sec10000 = (_tv.tv_usec/100) % 100;
  _ds.sec100 = _tv.tv_usec/10000;
  _ds.sec  = (u64)ptm.tm_sec;
  _ds.min  = (u64)ptm.tm_min;
  _ds.hour = (u64)ptm.tm_hour;
  _ds.year = (u64)ptm.tm_year;
  _ds.mon  = (u64)ptm.tm_mon;
  _ds.mon += 1;
  _ds.day_of_week = (u64)ptm.tm_wday;
  _ds.day_of_mon = (u64)ptm.tm_mday;
  _ds.year += 1900;
  /* Offset from UTC as +-hhmm, like Str2CDateStruct's %z */
  gmtoff = ptm.tm_gmtoff/60;
  if (gmtoff < 0) { _ds.zone_diff = -((-gmtoff)/60*100 + (-gmtoff)%60); }
  else            { _ds.zone_diff = gmtoff/60*100 + gmtoff%60; }
}

pub i64 yearStart(i64 year)
{//32-bit day since AD 0, given year number.
  /* Proleptic Gregorian, year 0 a leap year. Day 0 is Jan 2 of year 0,
   * so years 1 to 3999 keep the day numbers of the original calendar
   * (which had no Feb 29 in years 0 and 4000). Years before 1 are moved
   * up by whole 400-year cycles of 97 leap days, so the divisions floor. */
  i64 y1=year-1,n=0;
  if (y1<0) {
    n=(399-y1)/400;
    y1+=400*n;
  }
  return year*365+y1/4-y1/100+y1/400-97*n;
}

pub i64 dayOfWeek(i64 i)
{//Day of week, given 32-bit day since AD 0.
  i+=BASE_DAY_OF_WEEK;
  if (i>=0) {
    return i % 7;
  } else {
    return 6-(6-i)%7;
  }
}

pub void toParts(CDateStruct *_ds,CDate cdt)
{//Cvt CDate to CDateStruct.
  i64 i,k,date=cdt.date;
  _ds.day_of_week=dayOfWeek(date);
  /* An estimate, can be a year off either way */
  _ds.year=(date+1)*100000/YEAR_DAYS_INT;
  i=yearStart(_ds.year);
  while (i>date) {
    _ds.year--;
    i=yearStart(_ds.year);
  }
  while (yearStart(_ds.year+1)<=date) {
    _ds.year++;
    i=yearStart(_ds.year);
  }
  date-=i;
  if (yearStart(_ds.year+1)-i==365) {
    k=0;
    while (k<11 && date>=monStartDays1[k+1]) {
      k++;
    }
    date-=monStartDays1[k];
  } else {
    k=0;
    while (k<11 && date>=monStartDays2[k+1]) {
      k++;
    }
    date-=monStartDays2[k];
  }
  _ds.mon=k+1;
  _ds.day_of_mon=date+1;
  k=((625*15*15*3*cdt.time)>>21)+1;
  _ds.sec10000=math.divModU64(&k,100);
  _ds.sec100=math.divModU64(&k,100);
  _ds.sec=math.divModU64(&k,60);
  _ds.min=math.divModU64(&k,60);
  _ds.hour  =k;
}

pub CDate fromParts(CDateStruct *_ds)
{//Cvt CDateStruct to CDate.
  CDate cdt;
  i64 i1,i2,year=_ds.year,mon=(i64)_ds.mon-1;
  /* A month outside 1-12 carries into the year, as a day past the end
   * of the month carries into the next month: 13 is January of the next
   * year, 0 December of the one before */
  if (mon<0) {
    year-=(11-mon)/12;
    mon=11-(11-mon)%12;
  } else {
    year+=mon/12;
    mon%=12;
  }
  i1=yearStart(year);
  i2=yearStart(year+1);
  if (i2-i1==365) {
    i1+=monStartDays1[mon];
  } else {
    i1+=monStartDays2[mon];
  }
  /* The time in 1/10000 s; a day or more (or less than 0) carries into
   * the date, as a day past the end of the month carries into the month */
  i64 t=(i64)_ds.sec10000+100*((i64)_ds.sec100+100*((i64)_ds.sec
    +60*((i64)_ds.min+60*(i64)_ds.hour)));
  i64 days=t/864000000;
  t%=864000000;
  if (t<0) {
    t+=864000000;
    days--;
  }
  cdt.date=i1+_ds.day_of_mon-1+days;
  cdt.time=(t<<21)/(15*15*3*625);
  return cdt;
}

pub i32 firstDayOfMonth(i64 i)
{//First day of month, given 32-bit day since AD 0.
  CDateStruct ds;
  CDate cdt=0;
  cdt.date=i;
  toParts(&ds,cdt);
  ds.day_of_mon=1;
  cdt=fromParts(&ds);
  return cdt.date;
}

pub i64 lastDayOfMonth(i64 i)
{//Last day of month, given 32-bit day since AD 0.
  CDateStruct ds;
  CDate cdt=0;
  cdt.date=i;
  toParts(&ds,cdt);
  ds.mon++;
  if (ds.mon==13) {
    ds.mon=1;
    ds.year++;
  }
  ds.day_of_mon=1;
  cdt=fromParts(&ds);
  return cdt.date-1;
}

pub i32 firstDayOfYear(i64 i)
{//First day of year, given 32-bit day since AD 0.
  CDateStruct ds;
  CDate cdt=0;
  cdt.date=i;
  toParts(&ds,cdt);
  ds.day_of_mon=1;
  ds.mon=1;
  cdt=fromParts(&ds);
  return cdt.date;
}

pub i64 lastDayOfYear(i64 i)
{//Last day of year, given 32-bit day since AD 0.
  CDateStruct ds;
  CDate cdt=0;
  cdt.date=i;
  toParts(&ds,cdt);
  ds.day_of_mon=1;
  ds.mon=1;
  ds.year++;
  cdt=fromParts(&ds);
  return cdt.date-1;
}

pub CDate now()
{//Current datetime.
  CDateStruct ds;
  CDate cdt = 0;
  nowParts(&ds);
  cdt=fromParts(&ds);
  return cdt;
}

void civilFromDays(i64 z, i64 *y, i64 *m, i64 *d) {
  z += 719468; // shift epoch to 0000-03-01
  i64 era;

  if (z >= 0) {
    era = z / 146097;
  } else {
    era = (z - 146096) / 146097;
  }

  i64 doe = z - era * 146097; // [0, 146096]
  i64 yoe = (doe - doe/1460 + doe/36524 - doe/146096) / 365;
  i64 yr  = yoe + era * 400;
  i64 doy = doe - (365*yoe + yoe/4 - yoe/100); // [0, 365]
  i64 mp  = (5*doy + 2) / 153;
  *d = doy - (153*mp + 2)/5 + 1; // [1, 31]
  // [1, 12]
  if (mp < 10) {
    *m = mp + 3;
  } else {
    *m = mp - 9;
  }
  *y = yr + (*m <= 2);
}

// Writes e.g. 2026-07-05T14:30:00.123456789Z  (UTC)
pub void isoFromTimespec(libc.timespec *ts, u8 *buf) {
  i64 sec = ts.tv_sec, nsec = ts.tv_nsec;
  i64 days = sec / 86400, rem = sec % 86400;
  if (rem < 0) {
    rem += 86400;
    days--;
  }// floored div, for pre-1970
  i64 hh = rem/3600, mm = rem%3600/60, ss = rem%60;
  i64 y, mo, d;
  civilFromDays(days, &y, &mo, &d);
  str.formatTo(buf, "%04d-%02d-%02dT%02d:%02d:%02d.%09dZ", y, mo, d, hh, mm, ss, nsec);
}
