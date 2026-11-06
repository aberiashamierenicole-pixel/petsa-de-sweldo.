package com.petsa.api;

import com.petsa.http.ApiException;
import com.petsa.util.Json;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.*;
import java.util.regex.Pattern;

/** An employee's work schedule: shift start/end, assigned workdays, unpaid break. */
public record Schedule(String shiftStart, String shiftEnd, List<String> workdays, int breakMinutes) {
    public static final List<String> DAYS = List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");
    private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    public static final Schedule DEFAULT = new Schedule("08:00", "17:00", List.of("Mon", "Tue", "Wed", "Thu", "Fri"), 60);

    public int startMin() { return PayrollCalculator.minutes(shiftStart); }
    public int endMin() { return PayrollCalculator.minutes(shiftEnd); }

    /** Regular paid minutes in one day (shift length minus the break). */
    public int scheduledMinutes() { return Math.max(0, endMin() - startMin() - breakMinutes); }

    public Set<DayOfWeek> days() {
        Set<DayOfWeek> s = EnumSet.noneOf(DayOfWeek.class);
        for (String d : workdays) s.add(DayOfWeek.of(DAYS.indexOf(d) + 1)); // Mon=1 .. Sun=7
        return s;
    }

    public Map<String, Object> toJson() {
        return Json.obj("shiftStart", shiftStart, "shiftEnd", shiftEnd, "workdays", workdays, "breakMinutes", breakMinutes);
    }

    public String workdaysCsv() { return String.join(",", workdays); }

    public static Schedule fromRow(String start, String end, String csv, int breakMin) {
        List<String> days = new ArrayList<>();
        for (String d : csv.split(",")) if (DAYS.contains(d.trim())) days.add(d.trim());
        return new Schedule(start, end, days, breakMin);
    }

    /** Validates the schedule object sent by the UI. */
    public static Schedule parse(Object o) {
        if (!(o instanceof Map<?, ?> m)) throw ApiException.bad("Complete the assigned work schedule.");
        Object s = m.get("shiftStart"), e = m.get("shiftEnd"), w = m.get("workdays"), b = m.get("breakMinutes");
        if (!(s instanceof String start) || !HHMM.matcher(start).matches()) throw ApiException.bad("Shift Start must be a valid time.");
        if (!(e instanceof String end) || !HHMM.matcher(end).matches()) throw ApiException.bad("Shift End must be a valid time.");
        if (!(w instanceof List<?> list) || list.isEmpty()) throw ApiException.bad("Select at least one assigned workday.");
        Set<String> chosen = new HashSet<>();
        for (Object d : list) {
            if (!(d instanceof String ds) || !DAYS.contains(ds)) throw ApiException.bad("Invalid workday.");
            chosen.add(ds);
        }
        if (!(b instanceof BigDecimal bd) || bd.stripTrailingZeros().scale() > 0 || bd.intValue() < 0 || bd.intValue() > 480)
            throw ApiException.bad("Break duration must be a whole number of minutes from 0 to 480.");
        List<String> ordered = new ArrayList<>();
        for (String d : DAYS) if (chosen.contains(d)) ordered.add(d);
        Schedule sc = new Schedule(start, end, ordered, bd.intValue());
        if (sc.endMin() <= sc.startMin()) throw ApiException.bad("Shift End must be later than Shift Start.");
        if (sc.scheduledMinutes() <= 0) throw ApiException.bad("The break cannot be as long as the whole shift.");
        return sc;
    }
}
