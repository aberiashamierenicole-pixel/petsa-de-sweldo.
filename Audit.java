package com.petsa.api;

import com.petsa.Config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Writes the audit trail (System History for admins, activity list for Finance). */
public final class Audit {
    private Audit() {}

    static final DateTimeFormatter SQL_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Current date-time in the application time zone, formatted for MySQL. */
    public static String now() {
        return LocalDateTime.now(Config.get().zone).format(SQL_DATETIME);
    }

    public static void log(Connection c, String category, String actor, String action, String target, String details, String result) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO audit_log (occurred_at, category, actor, action, target, details, result) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, now());
            ps.setString(2, category);
            ps.setString(3, cut(actor, 30));
            ps.setString(4, cut(action, 40));
            ps.setString(5, cut(target, 40));
            ps.setString(6, cut(details, 255));
            ps.setString(7, result);
            ps.executeUpdate();
        }
    }

    public static void system(Connection c, String actor, String action, String target, String details) throws Exception {
        log(c, "SYSTEM", actor, action, target, details, "Success");
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
