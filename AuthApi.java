package com.petsa.api;

import com.petsa.Config;
import com.petsa.Db;
import com.petsa.http.*;
import com.petsa.util.Json;
import com.petsa.util.Passwords;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthApi {
    private AuthApi() {}

    private static final int MAX_FAILED = 5, LOCK_MINUTES = 15;
    private static final int IP_FAILURE_LIMIT = 30; // failed logins per IP per window (successful logins are not counted)
    private static final long IP_WINDOW_MS = 10 * 60 * 1000L;
    private static final Map<String, Deque<Long>> IP_ATTEMPTS = new ConcurrentHashMap<>();
    private static final String BAD_CREDENTIALS = "Incorrect employee ID or password. Please try again.";

    public static Object login(Ctx c) throws Exception {
        Map<String, Object> b = c.body();
        Object idRaw = b.get("loginId"), pwRaw = b.get("password");
        if (!(idRaw instanceof String id) || !(pwRaw instanceof String pw) || id.isBlank() || pw.isEmpty())
            throw ApiException.bad("Enter your employee ID and password.");
        if (id.length() > 30 || pw.length() > 200) throw ApiException.unauthorized(BAD_CREDENTIALS);
        ipBlockedCheck(c.clientIp());
        String loginId = id.trim().toUpperCase(Locale.ROOT);

        return Db.read(conn -> {
            long userId;
            String hash, empStatus;
            int failed;
            boolean locked;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT u.id, u.password_hash, u.failed_attempts, e.status AS emp_status, u.employee_id, "
                            + "(u.locked_until IS NOT NULL AND u.locked_until > UTC_TIMESTAMP()) AS locked "
                            + "FROM users u LEFT JOIN employees e ON e.id = u.employee_id WHERE u.login_id = ?")) {
                ps.setString(1, loginId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        Passwords.verifyDummy(pw); // same timing as a real check
                        recordIpFailure(c.clientIp());
                        throw ApiException.unauthorized(BAD_CREDENTIALS);
                    }
                    userId = rs.getLong("id");
                    hash = rs.getString("password_hash");
                    failed = rs.getInt("failed_attempts");
                    locked = rs.getBoolean("locked");
                    empStatus = rs.getString("employee_id") == null ? "Active" : rs.getString("emp_status");
                }
            }
            if (locked) throw new ApiException(429, "Too many failed attempts. Please try again in " + LOCK_MINUTES + " minutes.");

            if (!Passwords.verify(pw, hash)) {
                int n = failed + 1;
                if (n >= MAX_FAILED)
                    Audit.log(conn, "SYSTEM", "System", "Account Locked", loginId, "Account locked after repeated failed login attempts", "Attention");
                try (PreparedStatement ps = conn.prepareStatement(
                        n >= MAX_FAILED
                                ? "UPDATE users SET failed_attempts = 0, locked_until = DATE_ADD(UTC_TIMESTAMP(), INTERVAL " + LOCK_MINUTES + " MINUTE) WHERE id = ?"
                                : "UPDATE users SET failed_attempts = failed_attempts + 1 WHERE id = ?")) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
                recordIpFailure(c.clientIp());
                throw ApiException.unauthorized(BAD_CREDENTIALS);
            }
            if (failed > 0) {
                try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE id = ?")) {
                    ps.setLong(1, userId);
                    ps.executeUpdate();
                }
            }
            // Only revealed AFTER a correct password, so it cannot be used to discover accounts.
            if (!"Active".equals(empStatus))
                throw ApiException.forbidden("This employee account is inactive. Please contact your administrator.");

            String token = Sessions.create(conn, userId);
            c.setSessionCookie(token, Config.get().sessionHours * 3600);
            Session s = Sessions.find(token);
            return describe(s);
        });
    }

    public static Object logout(Ctx c) throws Exception {
        String token = c.cookie(Sessions.COOKIE);
        Sessions.destroy(token);
        c.setSessionCookie("", 0);
        return null;
    }

    public static Object me(Ctx c) {
        return describe(c.session);
    }

    private static Map<String, Object> describe(Session s) {
        return Json.obj("role", s.roleName(), "loginId", s.loginId(),
                "displayName", s.displayName(), "employee", s.employee());
    }

    private static void ipBlockedCheck(String ip) {
        Deque<Long> q = IP_ATTEMPTS.get(ip);
        if (q == null) return;
        long now = System.currentTimeMillis();
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > IP_WINDOW_MS) q.pollFirst();
            if (q.size() >= IP_FAILURE_LIMIT)
                throw new ApiException(429, "Too many failed login attempts. Please wait a few minutes and try again.");
        }
    }

    private static void recordIpFailure(String ip) {
        Deque<Long> q = IP_ATTEMPTS.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(System.currentTimeMillis());
        }
        if (IP_ATTEMPTS.size() > 10_000) IP_ATTEMPTS.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
}
