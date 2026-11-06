package com.petsa.api;

import com.petsa.Db;
import com.petsa.http.Ctx;
import com.petsa.util.Json;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Admins read SYSTEM history; Finance reads PAYROLL history. A role never sees the other's entries. */
public final class AuditApi {
    private AuditApi() {}

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("MM/dd/yy hh:mm a", Locale.US);

    public static Object list(Ctx c) throws Exception {
        String category = c.session.isAdmin() ? "SYSTEM" : "PAYROLL";
        int limit = 200;
        if (c.query.containsKey("limit")) {
            try {
                limit = Math.max(1, Math.min(500, Integer.parseInt(c.query.get("limit"))));
            } catch (NumberFormatException e) {
                throw com.petsa.http.ApiException.bad("limit must be a number.");
            }
        }
        final int max = limit;
        return Db.read(conn -> {
            List<Object> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT occurred_at, actor, action, target, details, result FROM audit_log WHERE category = ? ORDER BY id DESC LIMIT " + max)) {
                ps.setString(1, category);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        LocalDateTime at = LocalDateTime.parse(rs.getString("occurred_at").substring(0, 19).replace(' ', 'T'));
                        out.add(Json.obj("date", DISPLAY.format(at), "at", at.toString(), "actor", rs.getString("actor"),
                                "action", rs.getString("action"), "target", rs.getString("target"),
                                "details", rs.getString("details"), "result", rs.getString("result")));
                    }
                }
            }
            return out;
        });
    }
}
