package com.petsa.api;

import com.petsa.Config;
import com.petsa.Db;
import com.petsa.http.*;
import com.petsa.util.Json;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Payroll for semi-monthly periods (1st-15th, 16th-end of month).
 *  - refresh   : recalculates the CURRENT and PREVIOUS period from attendance, but only rows that are still Pending.
 *                Approved/Released rows are frozen. Finance's pages call this when they open.
 *  - approve   : Pending  -> Approved   (Finance only)
 *  - release   : Approved -> Released   (Finance only); only then can the employee see the payslip.
 * Paid days = scheduled workdays (per the employee's own schedule) with a completed Time In/Out, plus scheduled workdays
 * that are holidays (table `holidays`).
 */
public final class PayrollApi {
    private PayrollApi() {}

    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final Pattern PR_CODE = Pattern.compile("^PR-(\\d{1,12})$");

    private record Period(int id, LocalDate start, LocalDate end) {
        String label() { return LABEL.format(start) + "\u2013" + LABEL.format(end); }
    }

    static LocalDate[] periodFor(LocalDate d) {
        return d.getDayOfMonth() <= 15
                ? new LocalDate[]{d.withDayOfMonth(1), d.withDayOfMonth(15)}
                : new LocalDate[]{d.withDayOfMonth(16), d.withDayOfMonth(d.lengthOfMonth())};
    }

    private static String label(LocalDate[] p) {
        return LABEL.format(p[0]) + "\u2013" + LABEL.format(p[1]);
    }

    // ------------------------------------------------------------------ reading

    /** Finance: {currentPeriod, rows} for the last 12 months of periods (rows newest period first). */
    public static Object list(Ctx c) throws Exception {
        return Db.read(PayrollApi::snapshot);
    }

    private static Map<String, Object> snapshot(Connection conn) throws Exception {
        LocalDate[] cur = periodFor(LocalDate.now(Config.get().zone));
        List<Object> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ROWS + "WHERE p.start_date >= ? ORDER BY p.start_date DESC, r.employee_id")) {
            ps.setString(1, cur[0].minusMonths(12).toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(financeRow(rs));
            }
        }
        return Json.obj("currentPeriod", label(cur), "rows", rows);
    }

    /** Employee: only their own Released payslips. */
    public static Object myPayslips(Ctx c) throws Exception {
        String emp = c.session.employeeId();
        return Db.read(conn -> {
            List<Object> out = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(SELECT_ROWS + "WHERE r.employee_id = ? AND r.approval_status = 'Released' ORDER BY p.start_date DESC")) {
                ps.setString(1, emp);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(Json.obj("id", String.format("PAY-%06d", rs.getLong("id")), "employeeId", rs.getString("employee_id"),
                                "period", period(rs), "days", rs.getInt("days_paid"), "basic", rs.getBigDecimal("basic"),
                                "overtime", rs.getBigDecimal("overtime"), "gross", rs.getBigDecimal("gross"),
                                "net", rs.getBigDecimal("net"), "processed", true));
                    }
                }
            }
            return out;
        });
    }

    private static final String SELECT_ROWS =
            "SELECT r.id, r.period_id, r.employee_id, e.full_name, r.days_paid, r.basic, r.overtime, r.gross, r.deductions, r.net, "
                    + "r.approval_status, r.calculated_at, r.status_updated_at, p.start_date, p.end_date "
                    + "FROM payroll r JOIN payroll_periods p ON p.id = r.period_id JOIN employees e ON e.id = r.employee_id ";

    private static String period(ResultSet rs) throws Exception {
        return LABEL.format(LocalDate.parse(rs.getString("start_date"))) + "\u2013" + LABEL.format(LocalDate.parse(rs.getString("end_date")));
    }

    private static Map<String, Object> financeRow(ResultSet rs) throws Exception {
        String changed = rs.getString("status_updated_at") != null ? rs.getString("status_updated_at") : rs.getString("calculated_at");
        return Json.obj("id", String.format("PR-%06d", rs.getLong("id")), "employeeId", rs.getString("employee_id"),
                "name", rs.getString("full_name"), "period", period(rs), "days", rs.getInt("days_paid"),
                "basic", rs.getBigDecimal("basic"), "overtime", rs.getBigDecimal("overtime"), "gross", rs.getBigDecimal("gross"),
                "deductions", rs.getBigDecimal("deductions"), "net", rs.getBigDecimal("net"),
                "status", rs.getString("approval_status"), "updated", LABEL.format(LocalDate.parse(changed.substring(0, 10))));
    }

    // ------------------------------------------------------------------ recalculation

    public static Object refresh(Ctx c) throws Exception {
        LocalDate[] cur = periodFor(LocalDate.now(Config.get().zone));
        LocalDate[] prev = periodFor(cur[0].minusDays(1));
        return Db.tx(conn -> {
            recalculate(conn, ensure(conn, prev[0], prev[1]));
            recalculate(conn, ensure(conn, cur[0], cur[1]));
            return snapshot(conn);
        });
    }

    private static Period ensure(Connection c, LocalDate s, LocalDate e) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO payroll_periods (start_date, end_date) VALUES (?, ?)")) {
            ps.setString(1, s.toString());
            ps.setString(2, e.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM payroll_periods WHERE start_date = ? AND end_date = ?")) {
            ps.setString(1, s.toString());
            ps.setString(2, e.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Period(rs.getInt(1), s, e);
            }
        }
    }

    private record Worker(String id, BigDecimal salary, Schedule schedule) {}

    private static final class Att {
        final Set<LocalDate> completedDays = new HashSet<>();
        int overtimeMinutes;
    }

    private static void recalculate(Connection conn, Period p) throws Exception {
        String from = p.start().toString(), to = p.end().toString();

        // Active employees, plus anyone (now inactive) who actually worked in this period
        List<Worker> workers = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, monthly_salary, TIME_FORMAT(shift_start, '%H:%i') AS ss, TIME_FORMAT(shift_end, '%H:%i') AS se, workdays, break_minutes "
                        + "FROM employees WHERE status = 'Active' OR id IN (SELECT DISTINCT employee_id FROM attendance WHERE work_date BETWEEN ? AND ?) ORDER BY id")) {
            ps.setString(1, from);
            ps.setString(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    workers.add(new Worker(rs.getString("id"), rs.getBigDecimal("monthly_salary"),
                            Schedule.fromRow(rs.getString("ss"), rs.getString("se"), rs.getString("workdays"), rs.getInt("break_minutes"))));
            }
        }
        Map<String, Schedule> schedules = new HashMap<>();
        for (Worker w : workers) schedules.put(w.id(), w.schedule());

        Set<LocalDate> holidays = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT holiday_date FROM holidays WHERE holiday_date BETWEEN ? AND ?")) {
            ps.setString(1, from);
            ps.setString(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) holidays.add(LocalDate.parse(rs.getString(1)));
            }
        }

        Map<String, Att> att = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT employee_id, work_date, time_in, time_out FROM attendance WHERE work_date BETWEEN ? AND ? AND time_out IS NOT NULL")) {
            ps.setString(1, from);
            ps.setString(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Schedule sch = schedules.get(rs.getString("employee_id"));
                    if (sch == null) continue;
                    Att a = att.computeIfAbsent(rs.getString("employee_id"), k -> new Att());
                    a.completedDays.add(LocalDate.parse(rs.getString("work_date")));
                    a.overtimeMinutes += PayrollCalculator.overtimeMinutes(sch,
                            PayrollCalculator.minutes(rs.getString("time_in")), PayrollCalculator.minutes(rs.getString("time_out")));
                }
            }
        }

        // existing rows: only Pending ones may change
        Map<String, String> existing = new HashMap<>();
        Map<String, Long> existingId = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, employee_id, approval_status FROM payroll WHERE period_id = ? FOR UPDATE")) {
            ps.setInt(1, p.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    existing.put(rs.getString("employee_id"), rs.getString("approval_status"));
                    existingId.put(rs.getString("employee_id"), rs.getLong("id"));
                }
            }
        }

        String stamp = Audit.now();
        Set<String> eligible = new HashSet<>();
        for (Worker w : workers) {
            eligible.add(w.id());
            String status = existing.get(w.id());
            if (status != null && !status.equals("Pending")) continue; // frozen

            int scheduled = 0, paid = 0;
            Set<java.time.DayOfWeek> days = w.schedule().days();
            Att a = att.getOrDefault(w.id(), new Att());
            for (LocalDate d = p.start(); !d.isAfter(p.end()); d = d.plusDays(1)) {
                if (!days.contains(d.getDayOfWeek())) continue;
                scheduled++;
                if (a.completedDays.contains(d) || holidays.contains(d)) paid++;
            }
            PayrollCalculator.Result r = PayrollCalculator.compute(w.salary(), paid, scheduled, a.overtimeMinutes);
            if (status == null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO payroll (period_id, employee_id, days_paid, basic, overtime, late_deduction, gross, deductions, net, calculated_at, approval_status) "
                                + "VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, 'Pending')")) {
                    ps.setInt(1, p.id());
                    ps.setString(2, w.id());
                    ps.setInt(3, r.paidDays());
                    ps.setBigDecimal(4, r.basic());
                    ps.setBigDecimal(5, r.overtime());
                    ps.setBigDecimal(6, r.gross());
                    ps.setBigDecimal(7, r.deductions());
                    ps.setBigDecimal(8, r.net());
                    ps.setString(9, stamp);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE payroll SET days_paid = ?, basic = ?, overtime = ?, gross = ?, deductions = ?, net = ?, calculated_at = ? WHERE id = ?")) {
                    ps.setInt(1, r.paidDays());
                    ps.setBigDecimal(2, r.basic());
                    ps.setBigDecimal(3, r.overtime());
                    ps.setBigDecimal(4, r.gross());
                    ps.setBigDecimal(5, r.deductions());
                    ps.setBigDecimal(6, r.net());
                    ps.setString(7, stamp);
                    ps.setLong(8, existingId.get(w.id()));
                    ps.executeUpdate();
                }
            }
        }
        // Pending rows of people who are no longer eligible (deactivated without attendance) are removed
        for (var e : existing.entrySet()) {
            if (e.getValue().equals("Pending") && !eligible.contains(e.getKey())) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM payroll WHERE id = ?")) {
                    ps.setLong(1, existingId.get(e.getKey()));
                    ps.executeUpdate();
                }
            }
        }
    }

    // ------------------------------------------------------------------ approval workflow

    public static Object approve(Ctx c) throws Exception {
        return transition(c, "Pending", "Approved", "Payroll Approved");
    }

    public static Object release(Ctx c) throws Exception {
        return transition(c, "Approved", "Released", "Payroll Released");
    }

    private static Object transition(Ctx c, String from, String to, String action) throws Exception {
        Matcher m = PR_CODE.matcher(c.param("id"));
        if (!m.matches()) throw ApiException.notFound("Payroll record not found.");
        long id = Long.parseLong(m.group(1));
        String actor = c.session.loginId(), now = Audit.now();
        return Db.tx(conn -> {
            String current;
            try (PreparedStatement ps = conn.prepareStatement("SELECT approval_status FROM payroll WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw ApiException.notFound("Payroll record not found.");
                    current = rs.getString(1);
                }
            }
            if (!current.equals(from))
                throw ApiException.conflict(from.equals("Pending") ? "Only Pending payroll can be approved (this record is " + current + ")."
                        : "Only Approved payroll can be released (this record is " + current + ").");
            String by = to.equals("Approved") ? "approved" : "released";
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE payroll SET approval_status = ?, status_updated_at = ?, " + by + "_by = ?, " + by + "_at = ? WHERE id = ?")) {
                ps.setString(1, to);
                ps.setString(2, now);
                ps.setString(3, actor);
                ps.setString(4, now);
                ps.setLong(5, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(SELECT_ROWS + "WHERE r.id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    Map<String, Object> row = financeRow(rs);
                    Audit.log(conn, "PAYROLL", actor, action, (String) row.get("id"), row.get("id") + " \u00b7 " + row.get("name") + " \u00b7 " + row.get("period"), "Success");
                    return row;
                }
            }
        });
    }
}
