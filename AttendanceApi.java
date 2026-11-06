package com.petsa.api;

import com.petsa.Config;
import com.petsa.Db;
import com.petsa.http.*;
import com.petsa.util.Json;
import com.petsa.util.Validate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AttendanceApi {
    private AttendanceApi() {}

    private static final int ADMIN_LIMIT = 5000, EMPLOYEE_LIMIT = 1000;
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    static String hhmm(String t) {
        return t == null ? "" : t.substring(0, 5);
    }

    private static Map<String, Object> json(ResultSet rs) throws Exception {
        return Json.obj("id", "ATT-" + rs.getLong("id"), "employeeId", rs.getString("employee_id"),
                "date", rs.getString("work_date"), "timeIn", hhmm(rs.getString("time_in")),
                "timeOut", hhmm(rs.getString("time_out")));
    }

    /** Admin: all employees' records, optional ?from=YYYY-MM-DD&to=YYYY-MM-DD. */
    public static Object listAll(Ctx c) throws Exception {
        return query(c, null, ADMIN_LIMIT);
    }

    /** Employee: only the logged-in employee's own records (ID comes from the session, never from the request). */
    public static Object listMine(Ctx c) throws Exception {
        return query(c, c.session.employeeId(), EMPLOYEE_LIMIT);
    }

    private static Object query(Ctx c, String employeeId, int limit) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT id, employee_id, work_date, time_in, time_out FROM attendance WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (employeeId != null) { sql.append(" AND employee_id = ?"); args.add(employeeId); }
        if (c.query.containsKey("from")) { sql.append(" AND work_date >= ?"); args.add(Validate.date(c.query.get("from"), "From date").toString()); }
        if (c.query.containsKey("to")) { sql.append(" AND work_date <= ?"); args.add(Validate.date(c.query.get("to"), "To date").toString()); }
        sql.append(" ORDER BY work_date DESC, time_in DESC, id DESC LIMIT ").append(limit);
        return Db.read(conn -> {
            List<Object> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) ps.setString(i + 1, args.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(json(rs));
                }
            }
            return out;
        });
    }

    /** Time In uses the SERVER clock (Asia/Manila by default), so the browser clock cannot be spoofed. */
    public static Object timeIn(Ctx c) throws Exception {
        ZonedDateTime now = ZonedDateTime.now(Config.get().zone);
        String empId = c.session.employeeId();
        try {
            return Db.tx(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO attendance (employee_id, work_date, time_in) VALUES (?, ?, ?)")) {
                    ps.setString(1, empId);
                    ps.setString(2, now.toLocalDate().toString());
                    ps.setString(3, now.toLocalTime().format(SQL_TIME));
                    ps.executeUpdate();
                }
                return today(conn, empId, now);
            });
        } catch (Exception e) {
            if (Db.isDuplicateKey(e)) throw ApiException.conflict("You have already timed in today.");
            throw e;
        }
    }

    public static Object timeOut(Ctx c) throws Exception {
        ZonedDateTime now = ZonedDateTime.now(Config.get().zone);
        String empId = c.session.employeeId();
        return Db.tx(conn -> {
            long id;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, time_in, time_out FROM attendance WHERE employee_id = ? AND work_date = ? FOR UPDATE")) {
                ps.setString(1, empId);
                ps.setString(2, now.toLocalDate().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw ApiException.conflict("Please record your Time In first.");
                    if (rs.getString("time_out") != null) throw ApiException.conflict("You have already timed out today.");
                    if (now.toLocalTime().isBefore(LocalTime.parse(rs.getString("time_in"))))
                        throw ApiException.conflict("Time Out cannot be earlier than Time In.");
                    id = rs.getLong("id");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("UPDATE attendance SET time_out = ? WHERE id = ?")) {
                ps.setString(1, now.toLocalTime().format(SQL_TIME));
                ps.setLong(2, id);
                ps.executeUpdate();
            }
            return today(conn, empId, now);
        });
    }

    private static Map<String, Object> today(Connection conn, String empId, ZonedDateTime now) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, employee_id, work_date, time_in, time_out FROM attendance WHERE employee_id = ? AND work_date = ?")) {
            ps.setString(1, empId);
            ps.setString(2, now.toLocalDate().toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return json(rs);
            }
        }
    }
}
