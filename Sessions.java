package com.petsa.http;

import com.petsa.Config;
import com.petsa.Db;
import com.petsa.api.EmployeeApi;
import com.petsa.util.Passwords;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

/** Server-side sessions. The cookie carries a random token; the database keeps only its SHA-256 hash. */
public final class Sessions {
    private Sessions() {}

    public static final String COOKIE = "pds_session";

    public static String create(Connection c, long userId) throws Exception {
        String token = Passwords.newToken();
        int hours = Config.get().sessionHours;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sessions (token_hash, user_id, created_at, expires_at) VALUES (?, ?, UTC_TIMESTAMP(), DATE_ADD(UTC_TIMESTAMP(), INTERVAL ? HOUR))")) {
            ps.setString(1, Passwords.sha256Hex(token));
            ps.setLong(2, userId);
            ps.setInt(3, hours);
            ps.executeUpdate();
        }
        return token;
    }

    /** Returns the session for a cookie token, or null if missing/expired/deactivated. */
    public static Session find(String token) throws Exception {
        if (token == null || token.length() < 20 || token.length() > 100) return null;
        String hash = Passwords.sha256Hex(token);
        return Db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT u.id, u.login_id, u.role, u.display_name, u.employee_id, e.status "
                            + "FROM sessions s JOIN users u ON u.id = s.user_id LEFT JOIN employees e ON e.id = u.employee_id "
                            + "WHERE s.token_hash = ? AND s.expires_at > UTC_TIMESTAMP()")) {
                ps.setString(1, hash);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    String empId = rs.getString("employee_id");
                    Map<String, Object> emp = null;
                    if (empId != null) {
                        if (!"Active".equals(rs.getString("status"))) return null; // deactivated -> access revoked immediately
                        emp = EmployeeApi.find(c, empId);
                    }
                    return new Session(rs.getLong("id"), rs.getString("login_id"), rs.getString("role"), rs.getString("display_name"), empId, emp);
                }
            }
        });
    }

    public static void destroy(String token) throws Exception {
        if (token == null) return;
        Db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE token_hash = ?")) {
                ps.setString(1, Passwords.sha256Hex(token));
                ps.executeUpdate();
            }
            return null;
        });
    }

    public static void destroyAllForUser(Connection c, long userId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE user_id = ?")) {
            ps.setLong(1, userId);
            ps.executeUpdate();
        }
    }

    public static void purgeExpired() {
        try {
            Db.read(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE expires_at <= UTC_TIMESTAMP()")) {
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Exception e) {
            System.err.println("[warn] could not purge expired sessions: " + e.getMessage());
        }
    }
}
