package com.petsa.util;

import com.petsa.http.ApiException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Pattern;

/** Input validation helpers. All failures become HTTP 400 with a readable message. */
public final class Validate {
    private Validate() {}

    public static final Pattern EMPLOYEE_ID = Pattern.compile("^[A-Z0-9][A-Z0-9-]{2,19}$");
    private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{M}][\\p{L}\\p{M} .,'\u2019-]{1,99}$");
    private static final Pattern LABEL = Pattern.compile("^[\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N} &.,'\u2019/()-]{1,59}$");

    public static String text(Map<String, Object> m, String key, String label, int min, int max) {
        Object v = m.get(key);
        if (!(v instanceof String s)) throw ApiException.bad(label + " is required.");
        String t = s.trim();
        if (t.length() < min || t.length() > max)
            throw ApiException.bad(label + " must be " + min + "-" + max + " characters.");
        for (int i = 0; i < t.length(); i++)
            if (Character.isISOControl(t.charAt(i))) throw ApiException.bad(label + " contains invalid characters.");
        return t;
    }

    public static String personName(Map<String, Object> m) {
        String n = text(m, "name", "Full name", 2, 100);
        if (!NAME.matcher(n).matches()) throw ApiException.bad("Full name may contain only letters, spaces and . , ' -");
        return n;
    }

    public static String label(Map<String, Object> m, String key, String label) {
        String n = text(m, key, label, 2, 60);
        if (!LABEL.matcher(n).matches()) throw ApiException.bad(label + " contains characters that are not allowed.");
        return n;
    }

    public static String employeeId(String raw) {
        String id = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (!EMPLOYEE_ID.matcher(id).matches())
            throw ApiException.bad("Employee ID must be 3-20 characters: letters, numbers and hyphens (e.g. EMP-011).");
        return id;
    }

    public static BigDecimal salary(Map<String, Object> m) {
        Object v = m.get("salary");
        if (!(v instanceof BigDecimal d)) throw ApiException.bad("Enter a valid monthly salary.");
        if (d.signum() <= 0 || d.stripTrailingZeros().scale() > 2)
            throw ApiException.bad("Enter a valid monthly salary (more than 0, up to 2 decimal places).");
        if (d.compareTo(new BigDecimal("10000000")) > 0) throw ApiException.bad("Monthly salary is too large.");
        return d.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public static void password(String pw) {
        if (pw == null || pw.length() < 8) throw ApiException.bad("Password must be at least 8 characters.");
        if (pw.length() > 128) throw ApiException.bad("Password must be at most 128 characters.");
        if (pw.isBlank()) throw ApiException.bad("Password cannot be only spaces.");
    }

    public static LocalDate date(String s, String label) {
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.bad(label + " must be a date in YYYY-MM-DD format.");
        }
    }

    public static int intParam(String s, String label) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException | NullPointerException e) {
            throw ApiException.bad(label + " is required.");
        }
    }
}
