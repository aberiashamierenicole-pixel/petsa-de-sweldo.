package com.petsa.api;

import com.petsa.Db;
import com.petsa.http.*;
import com.petsa.util.Json;
import com.petsa.util.Passwords;
import com.petsa.util.Validate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EmployeeApi {
    private EmployeeApi() {}

    private static final Set<String> TYPES = Set.of("Full-Time", "Part-Time", "Agency-Hired");
    private static final String SELECT =
            "SELECT e.id, e.full_name, e.department, e.position_title, e.monthly_salary, e.status, e.employment_type, "
                    + "TIME_FORMAT(e.shift_start, '%H:%i') AS shift_start, TIME_FORMAT(e.shift_end, '%H:%i') AS shift_end, "
                    + "e.workdays, e.break_minutes, (u.locked_until IS NOT NULL AND u.locked_until > UTC_TIMESTAMP()) AS locked "
                    + "FROM employees e LEFT JOIN users u ON u.employee_id = e.id ";

    static Map<String, Object> json(ResultSet rs) throws Exception {
        String status = rs.getString("status");
        // accountStatus is what the System Admin dashboard counts: Inactive (disabled) / Locked (too many wrong passwords) / Active
        String account = status.equals("Inactive") ? "Inactive" : rs.getBoolean("locked") ? "Locked" : "Active";
        Schedule schedule = Schedule.fromRow(rs.getString("shift_start"), rs.getString("shift_end"), rs.getString("workdays"), rs.getInt("break_minutes"));
        return Json.obj("id", rs.getString("id"), "name", rs.getString("full_name"),
                "department", rs.getString("department"), "position", rs.getString("position_title"),
                "salary", rs.getBigDecimal("monthly_salary"), "status", status, "accountStatus", account,
                "employmentType", rs.getString("employment_type"), "schedule", schedule.toJson());
    }

    public static Map<String, Object> find(Connection c, String id) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(SELECT + "WHERE e.id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw ApiException.notFound("Employee not found.");
                return json(rs);
            }
        }
    }

    public static Object list(Ctx c) throws Exception {
        return Db.read(conn -> {
            List<Object> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(SELECT + "ORDER BY e.id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(json(rs));
            }
            return out;
        });
    }

    private static String employmentType(Map<String, Object> b) {
        Object t = b.get("employmentType");
        if (t == null) return "Full-Time";
        if (!(t instanceof String s) || !TYPES.contains(s)) throw ApiException.bad("Employment type must be Full-Time, Part-Time or Agency-Hired.");
        return s;
    }

    private static Schedule schedule(Map<String, Object> b) {
        return b.get("schedule") == null ? Schedule.DEFAULT : Schedule.parse(b.get("schedule"));
    }

    public static Object create(Ctx c) throws Exception {
        Map<String, Object> b = c.body();
        String id = Validate.employeeId(b.get("id") instanceof String s ? s : null);
        String name = Validate.personName(b);
        String dept = Validate.label(b, "department", "Department");
        String pos = Validate.label(b, "position", "Position");
        BigDecimal salary = Validate.salary(b);
        String type = employmentType(b);
        Schedule sch = schedule(b);
        String pw = b.get("password") instanceof String s ? s : null;
        Validate.password(pw);
        String hash = Passwords.hash(pw);
        String actor = c.session.loginId();
        try {
            return Res.created(Db.tx(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO employees (id, full_name, department, position_title, monthly_salary, status, employment_type, shift_start, shift_end, workdays, break_minutes) "
                                + "VALUES (?, ?, ?, ?, ?, 'Active', ?, ?, ?, ?, ?)")) {
                    ps.setString(1, id);
                    ps.setString(2, name);
                    ps.setString(3, dept);
                    ps.setString(4, pos);
                    ps.setBigDecimal(5, salary);
                    ps.setString(6, type);
                    ps.setString(7, sch.shiftStart());
                    ps.setString(8, sch.shiftEnd());
                    ps.setString(9, sch.workdaysCsv());
                    ps.setInt(10, sch.breakMinutes());
                    ps.executeUpdate();
                }
                upsertLogin(conn, id, name, hash);
                Audit.system(conn, actor, "Account Created", id, "Employee account created for " + name);
                return find(conn, id);
            }));
        } catch (Exception e) {
            if (Db.isDuplicateKey(e)) throw ApiException.conflict("Employee ID already exists.");
            throw e;
        }
    }

    public static Object update(Ctx c) throws Exception {
        String id = Validate.employeeId(c.param("id"));
        Map<String, Object> b = c.body();
        String name = Validate.personName(b);
        String dept = Validate.label(b, "department", "Department");
        String pos = Validate.label(b, "position", "Position");
        BigDecimal salary = Validate.salary(b);
        String type = employmentType(b);
        Schedule sch = Schedule.parse(b.get("schedule"));
        String pw = b.get("password") instanceof String s && !s.isEmpty() ? s : null; // blank = keep current password
        String newHash = null;
        if (pw != null) {
            Validate.password(pw);
            newHash = Passwords.hash(pw);
        }
        String account = b.get("accountStatus") instanceof String s ? s : null;
        if (account != null && !account.equals("Active") && !account.equals("Inactive") && !account.equals("Locked"))
            throw ApiException.bad("Account status must be Active or Inactive.");
        final String hash = newHash;
        String actor = c.session.loginId();
        return Db.tx(conn -> {
            Map<String, Object> before = find(conn, id); // 404 if missing
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE employees SET full_name = ?, department = ?, position_title = ?, monthly_salary = ?, employment_type = ?, "
                            + "shift_start = ?, shift_end = ?, workdays = ?, break_minutes = ? WHERE id = ?")) {
                ps.setString(1, name);
                ps.setString(2, dept);
                ps.setString(3, pos);
                ps.setBigDecimal(4, salary);
                ps.setString(5, type);
                ps.setString(6, sch.shiftStart());
                ps.setString(7, sch.shiftEnd());
                ps.setString(8, sch.workdaysCsv());
                ps.setInt(9, sch.breakMinutes());
                ps.setString(10, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET display_name = ? WHERE employee_id = ?")) {
                ps.setString(1, name);
                ps.setString(2, id);
                ps.executeUpdate();
            }
            Audit.system(conn, actor, "Employee Updated", id, "Employment information was updated");
            if (hash != null) {
                upsertLogin(conn, id, name, hash);
                Long uid = userIdFor(conn, id);
                if (uid != null) Sessions.destroyAllForUser(conn, uid); // password changed -> sign out everywhere
                Audit.system(conn, actor, "Password Reset", id, "Password was reset by an administrator");
            }
            if (account != null) applyAccountStatus(conn, actor, id, name, before, account);
            return find(conn, id);
        });
    }

    /** Dedicated endpoint for the Active/Inactive switch. */
    public static Object setStatus(Ctx c) throws Exception {
        String id = Validate.employeeId(c.param("id"));
        String status = c.body().get("status") instanceof String s ? s : "";
        if (!status.equals("Active") && !status.equals("Inactive")) throw ApiException.bad("Status must be Active or Inactive.");
        String actor = c.session.loginId();
        return Db.tx(conn -> {
            Map<String, Object> before = find(conn, id);
            applyAccountStatus(conn, actor, id, (String) before.get("name"), before, status);
            return find(conn, id);
        });
    }

    /**
     * Active   : re-enables the account and clears any lockout.
     * Inactive : disables login immediately and signs the person out (attendance and payslips are kept).
     * Locked   : cannot be set by hand (it is a consequence of wrong passwords); ignored.
     */
    private static void applyAccountStatus(Connection conn, String actor, String id, String name, Map<String, Object> before, String wanted) throws Exception {
        String current = (String) before.get("accountStatus");
        if (wanted.equals("Locked") || wanted.equals(current)) return;
        if (wanted.equals("Inactive")) {
            setEmployeeStatus(conn, id, "Inactive");
            Long uid = userIdFor(conn, id);
            if (uid != null) Sessions.destroyAllForUser(conn, uid);
            Audit.system(conn, actor, "Account Deactivated", id, "Employee system access was disabled");
        } else {
            boolean wasInactive = "Inactive".equals(before.get("status"));
            if (wasInactive) setEmployeeStatus(conn, id, "Active");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET failed_attempts = 0, locked_until = NULL WHERE employee_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            Audit.system(conn, actor, wasInactive ? "Account Reactivated" : "Account Unlocked", id,
                    wasInactive ? "Employee system access was restored" : "Account unlocked by an administrator");
        }
    }

    private static void setEmployeeStatus(Connection conn, String id, String status) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE employees SET status = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    private static Long userIdFor(Connection c, String employeeId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE employee_id = ?")) {
            ps.setString(1, employeeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    /** Creates the login for an employee, or replaces its password hash (and clears any lockout). */
    static void upsertLogin(Connection c, String employeeId, String name, String hash) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (login_id, role, employee_id, display_name, password_hash) VALUES (?, 'EMPLOYEE', ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), failed_attempts = 0, locked_until = NULL, display_name = VALUES(display_name)")) {
            ps.setString(1, employeeId);
            ps.setString(2, employeeId);
            ps.setString(3, name);
            ps.setString(4, hash);
            ps.executeUpdate();
        }
    }
}
