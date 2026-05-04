#!/usr/bin/env python3
"""
Analyze reachcalendar.ics and show what events our Kotlin parser would find for today.
Also dumps anything that might be getting dropped so we can spot bugs.
"""

import re
import sys
from datetime import date, datetime, timedelta, timezone, time as dtime
from zoneinfo import ZoneInfo

TARGET_DATE = date.today()          # change to e.g. date(2026, 5, 4) to pin a date
WINDOW_START = datetime.combine(TARGET_DATE, dtime.min)
WINDOW_END   = datetime.combine(TARGET_DATE, dtime.max)
LOCAL_TZ     = ZoneInfo("America/Los_Angeles")   # adjust to your timezone

ICS_FILE = "reachcalendar.ics"

# ── Windows TZ name → IANA ────────────────────────────────────────────────────
WIN_TZ = {
    "Pacific Standard Time":  "America/Los_Angeles",
    "Mountain Standard Time": "America/Denver",
    "Central Standard Time":  "America/Chicago",
    "Eastern Standard Time":  "America/New_York",
    "UTC":                    "UTC",
    "GMT Standard Time":      "Europe/London",
    "Romance Standard Time":  "Europe/Paris",
    "W. Europe Standard Time":"Europe/Berlin",
    "AUS Eastern Standard Time":"Australia/Sydney",
    "Tokyo Standard Time":    "Asia/Tokyo",
    "China Standard Time":    "Asia/Shanghai",
    "India Standard Time":    "Asia/Kolkata",
}

def win_tz(name):
    return ZoneInfo(WIN_TZ.get(name, "UTC"))

# ── ICS unfolding ─────────────────────────────────────────────────────────────
def unfold(text):
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    out = []
    for line in lines:
        if line.startswith((" ", "\t")) and out:
            out[-1] += line[1:]
        else:
            out.append(line)
    return out

# ── datetime parsing (mirrors Kotlin parseDt) ─────────────────────────────────
DT_FMT   = "%Y%m%dT%H%M%S"
DATE_FMT = "%Y%m%d"

def parse_dt(key, value):
    """Returns (datetime_local, is_all_day) or (None, False) on failure."""
    v = value.strip().rstrip("Z") if value.strip().endswith("Z") else value.strip()
    is_utc = value.strip().endswith("Z")
    is_all_day = "VALUE=DATE" in key.upper() or (len(v) == 8 and "T" not in v)

    # Extract TZID from key if present
    tzid_match = re.search(r"TZID=([^;:]+)", key)
    tzid = tzid_match.group(1) if tzid_match else None

    try:
        if is_all_day:
            d = datetime.strptime(v[:8], DATE_FMT)
            return (d, True)
        dt_str = v[:15]
        dt = datetime.strptime(dt_str, DT_FMT)
        if is_utc:
            dt = dt.replace(tzinfo=timezone.utc).astimezone(LOCAL_TZ).replace(tzinfo=None)
        elif tzid:
            tz = ZoneInfo(WIN_TZ.get(tzid, tzid))
            try:
                dt = dt.replace(tzinfo=tz).astimezone(LOCAL_TZ).replace(tzinfo=None)
            except Exception:
                pass  # unknown tz — treat as local
        return (dt, False)
    except Exception as e:
        return (None, False)

# ── Duration parsing ──────────────────────────────────────────────────────────
def parse_duration(value):
    d = timedelta()
    s = value.lstrip("P")
    t_idx = s.find("T")
    date_part = s[:t_idx] if t_idx >= 0 else s
    time_part = s[t_idx+1:] if t_idx >= 0 else ""
    for m, unit in [(re.search(r"(\d+)W", date_part), "weeks"),
                    (re.search(r"(\d+)D", date_part), "days"),
                    (re.search(r"(\d+)H", time_part), "hours"),
                    (re.search(r"(\d+)M", time_part), "minutes")]:
        if m:
            d += timedelta(**{unit: int(m.group(1))})
    return d

# ── RRULE expansion ───────────────────────────────────────────────────────────
DAY_MAP = {"MO":0,"TU":1,"WE":2,"TH":3,"FR":4,"SA":5,"SU":6}

def parse_byday(byday_str):
    """Returns list of (position_or_None, weekday_int)."""
    result = []
    for part in byday_str.split(","):
        part = part.strip()
        day_str = part[-2:].upper()
        pos_str = part[:-2]
        pos = int(pos_str) if pos_str else None
        result.append((pos, DAY_MAP.get(day_str)))
    return result

def nth_weekday_of_month(year, month, weekday, n):
    """Return the date of the Nth weekday (1=first, -1=last) in year/month."""
    from calendar import monthrange
    _, days_in_month = monthrange(year, month)
    if n > 0:
        first = date(year, month, 1)
        delta = (weekday - first.weekday()) % 7
        d = first + timedelta(days=delta + (n-1)*7)
    else:
        last = date(year, month, days_in_month)
        delta = (last.weekday() - weekday) % 7
        d = last - timedelta(days=delta + (-n-1)*7)
    return d if d.month == month else None

def expand_rrule(rrule, dt_start, ex_dates, window_start, window_end):
    params = {}
    for part in rrule.split(";"):
        if "=" in part:
            k, v = part.split("=", 1)
            params[k.upper()] = v

    freq     = params.get("FREQ", "").upper()
    interval = int(params.get("INTERVAL", 1))
    max_count = int(params.get("COUNT", 10**9))
    until_raw = params.get("UNTIL")
    rule_until = None
    if until_raw:
        dt, _ = parse_dt("", until_raw)
        rule_until = dt
    effective_end = min(window_end, rule_until) if rule_until else window_end

    byday_raw   = params.get("BYDAY", "")
    byday       = parse_byday(byday_raw) if byday_raw else []
    positional  = [(p, w) for p, w in byday if p is not None]
    plain       = [(p, w) for p, w in byday if p is None]
    bymonthday  = [int(x) for x in params.get("BYMONTHDAY","").split(",") if x]
    bymonth     = [int(x) for x in params.get("BYMONTH","").split(",") if x]

    results = []
    dropped_by_ex = []

    def emit(dt):
        if dt < dt_start or dt > effective_end:
            return
        if dt in ex_dates:
            dropped_by_ex.append(dt)
            return
        if dt >= window_start:
            results.append(dt)

    def month_candidates(year, month, fallback_day):
        from calendar import monthrange
        _, days_in_month = monthrange(year, month)
        if positional:
            return [nth_weekday_of_month(year, month, w, p) for p, w in positional]
        elif plain:
            target_wdays = {w for _, w in plain}
            d = date(year, month, 1)
            cands = []
            for _ in range(days_in_month):
                if d.weekday() in target_wdays:
                    cands.append(d)
                d += timedelta(days=1)
            return cands
        elif bymonthday:
            cands = []
            for day in bymonthday:
                actual = days_in_month + day + 1 if day < 0 else day
                if 1 <= actual <= days_in_month:
                    cands.append(date(year, month, actual))
            return cands
        else:
            actual_day = min(fallback_day, days_in_month)
            return [date(year, month, actual_day)]

    t = dt_start.time()

    if freq == "DAILY":
        cur = dt_start
        skip = max(0, int((window_start - cur).days // interval) - 1)
        cur += timedelta(days=skip * interval)
        count = 0
        while cur <= effective_end and count < max_count:
            emit(cur)
            cur += timedelta(days=interval)
            count += 1

    elif freq == "WEEKLY":
        target_wdays = {w for _, w in plain} if plain else {dt_start.weekday()}
        anchor = dt_start
        skip = max(0, int((window_start - anchor).days // (interval * 7)) - 1)
        anchor += timedelta(weeks=skip * interval)
        safety = 0
        while anchor <= effective_end and len(results) < max_count and safety < 3000:
            for i in range(7):
                c = anchor + timedelta(days=i)
                if c.weekday() in target_wdays:
                    emit(c)
            anchor += timedelta(weeks=interval)
            safety += 1

    elif freq == "MONTHLY":
        from calendar import monthrange
        cur_year, cur_month = dt_start.year, dt_start.month
        months_to_win = max(0, (window_start.year - cur_year) * 12 + (window_start.month - cur_month) - 1)
        months_to_win = (months_to_win // interval) * interval
        total_months = cur_year * 12 + cur_month - 1 + months_to_win
        cur_year, cur_month = divmod(total_months, 12)
        cur_month += 1
        safety = 0
        while datetime(cur_year, cur_month, 1) <= effective_end and len(results) < max_count and safety < 500:
            for d in month_candidates(cur_year, cur_month, dt_start.day):
                if d:
                    emit(datetime.combine(d, t))
            total_months = cur_year * 12 + cur_month - 1 + interval
            cur_year, cur_month = divmod(total_months, 12)
            cur_month += 1
            safety += 1

    elif freq == "YEARLY":
        year = dt_start.year
        skip_years = max(0, ((window_start.year - year) // interval - 1) * interval)
        year = max(dt_start.year, year + skip_years)
        safety = 0
        while year <= effective_end.year + 1 and len(results) < max_count and safety < 200:
            target_months = bymonth if bymonth else [dt_start.month]
            for month_num in target_months:
                for d in month_candidates(year, month_num, dt_start.day):
                    if d:
                        emit(datetime.combine(d, t))
            year += interval
            safety += 1

    return results, dropped_by_ex

# ── ICS parser ────────────────────────────────────────────────────────────────
def parse_ics(path, window_start, window_end):
    with open(path, encoding="utf-8", errors="replace") as f:
        text = f.read()
    lines = unfold(text)

    raw_events = []
    cur = None
    for line in lines:
        if line == "BEGIN:VEVENT":
            cur = {"exdates": set(), "warnings": []}
        elif line == "END:VEVENT":
            if cur:
                raw_events.append(cur)
            cur = None
        elif cur is not None:
            colon = line.find(":")
            if colon < 0:
                continue
            key = line[:colon]
            value = line[colon+1:]
            key_name = key.split(";")[0].upper()
            if key_name == "UID":
                cur["uid"] = value.strip()
            elif key_name == "SUMMARY":
                cur["summary"] = value.replace("\\n","\n").replace("\\,",",")
            elif key_name == "DTSTART":
                dt, allday = parse_dt(key, value)
                cur["dtstart"] = dt
                cur["allday"] = allday
                cur["dtstart_raw"] = value.strip()
                cur["dtstart_key"] = key
            elif key_name == "DTEND":
                dt, _ = parse_dt(key, value)
                cur["dtend"] = dt
            elif key_name == "DURATION":
                cur["duration"] = parse_duration(value)
            elif key_name == "RRULE":
                cur["rrule"] = value.strip()
            elif key_name == "RECURRENCE-ID":
                dt, _ = parse_dt(key, value)
                cur["recurrence_id"] = dt
            elif key_name == "EXDATE":
                for v in value.split(","):
                    dt, _ = parse_dt(key, v.strip())
                    if dt:
                        cur["exdates"].add(dt)

    masters   = [e for e in raw_events if "recurrence_id" not in e]
    overrides = {}
    for e in raw_events:
        if "recurrence_id" in e:
            overrides.setdefault(e.get("uid",""), []).append(e)

    events_in_window = []
    skipped = []

    for raw in masters:
        uid     = raw.get("uid", "?")
        summary = raw.get("summary", "(No title)")
        dtstart = raw.get("dtstart")
        if dtstart is None:
            skipped.append((summary, uid, "no DTSTART"))
            continue

        dtend = raw.get("dtend")
        if dtend is None and "duration" in raw:
            dtend = dtstart + raw["duration"]
        if dtend is None:
            dtend = dtstart + timedelta(hours=1)

        duration = dtend - dtstart
        allday   = raw.get("allday", False)

        inst_override_times = {e.get("recurrence_id") for e in overrides.get(uid, []) if e.get("recurrence_id")}
        exclude_times = raw["exdates"] | inst_override_times

        rrule = raw.get("rrule")
        if rrule is None:
            if dtstart in inst_override_times:
                skipped.append((summary, uid, f"replaced by RECURRENCE-ID override"))
                continue
            dtend_actual = dtstart + duration
            if dtstart < window_end and dtend_actual > window_start:
                events_in_window.append({
                    "summary": summary, "start": dtstart, "end": dtend_actual,
                    "allday": allday, "uid": uid, "type": "single"
                })
            else:
                skipped.append((summary, uid, f"out of window: {dtstart} – {dtend_actual}"))
        else:
            occurrences, dropped_by_ex = expand_rrule(rrule, dtstart, exclude_times, window_start, window_end)
            if occurrences:
                for occ in occurrences:
                    events_in_window.append({
                        "summary": summary, "start": occ, "end": occ + duration,
                        "allday": allday, "uid": uid, "type": f"recurring ({rrule})"
                    })
            else:
                reason = "expanded to 0 occurrences"
                if dropped_by_ex:
                    reason += f" (dropped by EXDATE/override: {dropped_by_ex})"
                skipped.append((summary, uid, f"rrule={rrule} → {reason}, dtstart={dtstart}"))

        # Modified instances
        for ov in overrides.get(uid, []):
            ov_start = ov.get("dtstart")
            if not ov_start:
                continue
            ov_end = ov.get("dtend") or (ov_start + duration)
            if ov_start < window_end and ov_end > window_start:
                events_in_window.append({
                    "summary": ov.get("summary", summary),
                    "start": ov_start, "end": ov_end,
                    "allday": ov.get("allday", allday),
                    "uid": uid, "type": "override"
                })

    # Orphan overrides: RECURRENCE-ID instances with no master in the file
    master_uids = {e.get("uid") for e in masters}
    for uid, ov_list in overrides.items():
        if uid in master_uids:
            continue
        for ov in ov_list:
            ov_start = ov.get("dtstart")
            if not ov_start:
                continue
            ov_end = ov.get("dtend") or (ov_start + timedelta(hours=1))
            if ov_start < window_end and ov_end > window_start:
                events_in_window.append({
                    "summary": ov.get("summary", "(No title)"),
                    "start": ov_start, "end": ov_end,
                    "allday": ov.get("allday", False),
                    "uid": uid, "type": "orphan override"
                })

    return sorted(events_in_window, key=lambda e: e["start"]), skipped

# ── main ──────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    print(f"Analyzing events for {TARGET_DATE}  (local tz: {LOCAL_TZ})\n")
    events, skipped = parse_ics(ICS_FILE, WINDOW_START, WINDOW_END)

    print(f"{'='*60}")
    print(f"  EVENTS IN WINDOW ({len(events)} found)")
    print(f"{'='*60}")
    for e in events:
        if e["allday"]:
            time_str = "all day"
        else:
            time_str = f"{e['start'].strftime('%H:%M')} – {e['end'].strftime('%H:%M')}"
        print(f"  {time_str:17s}  {e['summary']}")
        if e["type"] != "single":
            print(f"  {'':17s}  [{e['type']}]")

    print(f"\n{'='*60}")
    print(f"  EVENTS NOT IN WINDOW ({len(skipped)} skipped — check for false drops)")
    print(f"{'='*60}")
    for summary, uid, reason in skipped:
        print(f"  {summary!r:40s}  {reason}")
