package com.petsa;

import com.petsa.api.PayrollCalculator;
import com.petsa.util.Json;
import com.petsa.util.Passwords;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * End-to-end test against a RUNNING API + MySQL.
 *
 * !! It writes data: creates TST-* employees, inserts attendance and holidays, calculates, APPROVES and RELEASES
 * !! payroll, and deletes all payroll rows at the start. Only run it against a throw-away database whose
 * !! name contains "_test" (the test refuses to run otherwise). See README, "Running the tests".
 *
 * Needs the same PDS_DB_* settings as the API (to seed data) and the API running on PDS_API (default http://127.0.0.1:8080).
 */
public final class IntegrationTest {
    static final String API = System.getenv().getOrDefault("PDS_API", "http://127.0.0.1:8080");
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static int failures = 0, checks = 0;

    record R(int status, String body, String setCookie) {
        @SuppressWarnings("unchecked") Map<String, Object> obj() { return (Map<String, Object>) Json.parse(body); }
        @SuppressWarnings("unchecked") List<Object> list() { return (List<Object>) Json.parse(body); }
    }

    static final class Client {
        String cookie;
        R call(String method, String path, String json) { return call(method, path, json, true, "http://localhost:8443"); }

        R call(String method, String path, String json, boolean csrfHeader, String origin) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path));
                if (csrfHeader) b.header("X-Requested-With", "PetsaDeSweldo");
                if (origin != null) b.header("Origin", origin);
                if (cookie != null) b.header("Cookie", "pds_session=" + cookie);
                if (json != null) b.header("Content-Type", "application/json");
                b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
                HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                String sc = r.headers().firstValue("Set-Cookie").orElse(null);
                return new R(r.statusCode(), r.body(), sc);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        R login(String id, String pw) {
            R r = call("POST", "/api/auth/login", Json.stringify(Json.obj("loginId", id, "password", pw)));
            if (r.status == 200 && r.setCookie != null) cookie = r.setCookie.split(";")[0].substring("pds_session=".length());
            return r;
        }
    }

    static void check(String name, boolean ok) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "ok   " : "FAIL ") + name);
    }

    static void status(String name, R r, int expected) {
        boolean ok = r.status == expected;
        check(name + " -> " + expected + (ok ? "" : " (got " + r.status + ": " + r.body + ")"), ok);
    }

    static String body(Object... kv) { return Json.stringify(Json.obj(kv)); }
    static BigDecimal num(Object o) { return (BigDecimal) o; }

    static void sql(String q, Object... args) throws Exception {
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement(q)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    static LocalDate[] periodFor(LocalDate d) {
        return d.getDayOfMonth() <= 15 ? new LocalDate[]{d.withDayOfMonth(1), d.withDayOfMonth(15)}
                : new LocalDate[]{d.withDayOfMonth(16), d.withDayOfMonth(d.lengthOfMonth())};
    }

    /** Independent re-implementation of the pay rules (not calling PayrollCalculator). */
    static BigDecimal expectedNet(BigDecimal salary, int paid, int scheduled, int overtimeMinutes) {
        BigDecimal basic = scheduled == 0 ? BigDecimal.ZERO : salary.divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(paid)).divide(BigDecimal.valueOf(scheduled), 10, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
        BigDecimal hourly = salary.divide(BigDecimal.valueOf(22), 10, RoundingMode.HALF_UP).divide(BigDecimal.valueOf(8), 10, RoundingMode.HALF_UP);
        BigDecimal ot = hourly.multiply(new BigDecimal("1.25")).multiply(BigDecimal.valueOf(overtimeMinutes)).divide(BigDecimal.valueOf(60), 10, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
        return basic.add(ot);
    }

    static String sched(String start, String end, String days, int brk) {
        List<Object> d = new ArrayList<>(Arrays.asList(days.split(",")));
        return Json.stringify(Json.obj("shiftStart", start, "shiftEnd", end, "workdays", d, "breakMinutes", brk));
    }

    static String emp(String id, String name, Object salary, String type, String schedule, String password) {
        Map<String, Object> m = Json.obj("id", id, "name", name, "department", "Finance", "position", "Clerk", "salary", salary,
                "employmentType", type, "schedule", Json.parse(schedule));
        if (password != null) m.put("password", password);
        return Json.stringify(m);
    }

    static Map<?, ?> find(List<Object> rows, String key, String value) {
        for (Object o : rows) if (value.equals(((Map<?, ?>) o).get(key))) return (Map<?, ?>) o;
        return null;
    }

    static Map<?, ?> payrollRow(Client fin, String employeeId, String periodLabel) {
        for (Object o : fin.call("GET", "/api/finance/payroll", null).obj().get("rows") instanceof List<?> l ? l : List.of()) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.get("employeeId").equals(employeeId) && m.get("period").equals(periodLabel)) return m;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> rows(R r) { return (List<Object>) r.obj().get("rows"); }

    static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("MM/dd/yy");
    static List<LocalDate> weekdays = new ArrayList<>(), saturdays = new ArrayList<>();
    static LocalDate[] prevDates;

    public static void main(String[] args) throws Exception {
        Db.init();
        if (!Config.get().dbUrl.contains("_test")) {
            System.err.println("REFUSING TO RUN: the database URL must contain '_test' (this test writes and approves payroll data).");
            System.exit(2);
        }
        seed();
        String prevLabel = LABEL.format(prevDates[0]) + "\u2013" + LABEL.format(prevDates[1]);
        int n = weekdays.size();

        Client anon = new Client(), admin = new Client(), fin = new Client(), emp1 = new Client(), emp2 = new Client(), emp3 = new Client();

        // ---- authentication & basic protections
        status("unauthenticated employee list is blocked", anon.call("GET", "/api/employees", null), 401);
        R bad = anon.login("TST-001", "wrong-password");
        status("wrong password", bad, 401);
        R unknown = anon.login("NOPE-999", "whatever123");
        status("unknown login", unknown, 401);
        check("unknown user and wrong password give the same message (no account enumeration)", bad.obj().get("error").equals(unknown.obj().get("error")));
        status("SQL-injection style login id is just a failed login", anon.login("' OR '1'='1' -- ", "x"), 401);
        status("POST without CSRF header is rejected", anon.call("POST", "/api/auth/login", body("loginId", "TST-001", "password", "x"), false, "http://localhost:8443"), 403);
        status("POST from foreign Origin is rejected", anon.call("POST", "/api/auth/login", body("loginId", "TST-001", "password", "x"), true, "http://evil.example"), 403);
        status("unknown route", anon.call("GET", "/api/nothing", null), 404);

        R a = admin.login("tst-admin", "Admin-Test-Pass-1");
        status("admin login", a, 200);
        check("admin role is 'admin'", "admin".equals(a.obj().get("role")));
        check("session cookie is HttpOnly + SameSite=Strict", a.setCookie != null && a.setCookie.contains("HttpOnly") && a.setCookie.contains("SameSite=Strict"));
        R f = fin.login("TST-FIN", "Finance-Test-Pass-1");
        status("finance login", f, 200);
        check("finance role is 'finance' with no employee profile", "finance".equals(f.obj().get("role")) && f.obj().get("employee") == null);
        R e1 = emp1.login("TST-001", "Employee-Pass-1");
        status("employee login", e1, 200);
        Map<?, ?> me1 = (Map<?, ?>) e1.obj().get("employee");
        check("employee role + own profile with schedule and employment type", "employee".equals(e1.obj().get("role")) && me1.get("id").equals("TST-001")
                && me1.get("employmentType").equals("Full-Time") && ((Map<?, ?>) me1.get("schedule")).get("shiftStart").equals("08:00"));
        check("response never contains a password hash", !a.body.contains("pbkdf2") && !e1.body.contains("pbkdf2"));

        // ---- role-based access matrix
        status("employee cannot list employees", emp1.call("GET", "/api/employees", null), 403);
        status("employee cannot read audit", emp1.call("GET", "/api/audit", null), 403);
        status("employee cannot reach finance payroll", emp1.call("GET", "/api/finance/payroll", null), 403);
        status("admin cannot reach finance payroll", admin.call("GET", "/api/finance/payroll", null), 403);
        status("admin cannot approve payroll", admin.call("POST", "/api/finance/payroll/PR-000001/approve", null), 403);
        status("finance cannot create employees", fin.call("POST", "/api/employees", emp("TST-X", "Ana Reyes", 1000, "Full-Time", sched("08:00", "17:00", "Mon", 60), "Newhire-Pass-1")), 403);
        status("finance cannot edit employees", fin.call("PUT", "/api/employees/TST-001", body("name", "Ana Reyes")), 403);
        status("finance cannot change account status", fin.call("POST", "/api/employees/TST-001/status", body("status", "Inactive")), 403);
        status("finance cannot use employee time-in", fin.call("POST", "/api/me/attendance/time-in", null), 403);
        status("admin cannot use employee time-in", admin.call("POST", "/api/me/attendance/time-in", null), 403);
        status("employee cannot approve payroll", emp1.call("POST", "/api/finance/payroll/PR-000001/approve", null), 403);
        R list = fin.call("GET", "/api/employees", null);
        status("finance may read the employee list (salary information)", list, 200);
        check("3 seeded test employees listed", list.list().size() == 3);
        status("admin lists employees", admin.call("GET", "/api/employees", null), 200);

        // ---- employee management with schedules
        String sch = sched("07:00", "16:00", "Mon,Tue,Wed,Thu,Fri,Sat", 60);
        R created = admin.call("POST", "/api/employees", emp("tst-100", "Juan D. Dela Cruz", new BigDecimal("25000"), "Agency-Hired", sch, "Newhire-Pass-1"));
        status("create employee with type + schedule", created, 201);
        Map<?, ?> cs = (Map<?, ?>) created.obj().get("schedule");
        check("created employee echoes type, Mon-Sat schedule, Active account", created.obj().get("employmentType").equals("Agency-Hired")
                && ((List<?>) cs.get("workdays")).size() == 6 && cs.get("shiftEnd").equals("16:00") && created.obj().get("accountStatus").equals("Active"));
        status("duplicate employee id", admin.call("POST", "/api/employees", emp("TST-100", "Juan D. Dela Cruz", 25000, "Full-Time", sch, "Newhire-Pass-1")), 409);
        status("missing fields", admin.call("POST", "/api/employees", body("id", "TST-101")), 400);
        status("script tag in name rejected", admin.call("POST", "/api/employees", emp("TST-101", "<script>alert(1)</script>", 1000, "Full-Time", sch, "Newhire-Pass-1")), 400);
        status("negative salary rejected", admin.call("POST", "/api/employees", emp("TST-101", "Ana Reyes", new BigDecimal("-5"), "Full-Time", sch, "Newhire-Pass-1")), 400);
        status("short password rejected", admin.call("POST", "/api/employees", emp("TST-101", "Ana Reyes", 1000, "Full-Time", sch, "short")), 400);
        status("unknown employment type rejected", admin.call("POST", "/api/employees", emp("TST-101", "Ana Reyes", 1000, "Casual", sch, "Newhire-Pass-1")), 400);
        status("shift end before start rejected", admin.call("POST", "/api/employees", emp("TST-101", "Ana Reyes", 1000, "Full-Time", sched("17:00", "08:00", "Mon", 60), "Newhire-Pass-1")), 400);
        status("no workdays rejected", admin.call("POST", "/api/employees", emp("TST-101", "Ana Reyes", 1000, "Full-Time", sched("08:00", "17:00", "", 60).replace("[\"\"]", "[]"), "Newhire-Pass-1")), 400);
        status("invalid workday rejected", admin.call("POST", "/api/employees", emp("TST-101", "Ana Reyes", 1000, "Full-Time", sched("08:00", "17:00", "Mon,Funday", 60), "Newhire-Pass-1")), 400);
        status("break as long as the shift rejected", admin.call("POST", "/api/employees", emp("TST-101", "Ana Reyes", 1000, "Full-Time", sched("08:00", "09:00", "Mon", 60), "Newhire-Pass-1")), 400);
        status("absurd break rejected", admin.call("POST", "/api/employees", emp("TST-101", "Ana Reyes", 1000, "Full-Time", sched("08:00", "17:00", "Mon", 999), "Newhire-Pass-1")), 400);
        status("invalid time rejected", admin.call("POST", "/api/employees", emp("TST-101", "Ana Reyes", 1000, "Full-Time", sched("8am", "17:00", "Mon", 60), "Newhire-Pass-1")), 400);
        status("malformed JSON -> 400", admin.call("POST", "/api/employees", "{not json"), 400);
        status("oversized body -> 413", admin.call("POST", "/api/employees", "{\"x\":\"" + "a".repeat(70_000) + "\"}"), 413);
        Client newHire = new Client();
        status("new employee can log in with the initial password", newHire.login("TST-100", "Newhire-Pass-1"), 200);

        String edited = emp("TST-100", "Juan D. Dela Cruz", new BigDecimal("26000.50"), "Part-Time", sched("09:00", "13:00", "Mon,Wed,Fri", 0), null);
        status("edit employee (type, salary, schedule)", admin.call("PUT", "/api/employees/TST-100", edited), 200);
        Map<?, ?> afterEdit = find(admin.call("GET", "/api/employees", null).list(), "id", "TST-100");
        check("edit persisted in database", afterEdit.get("employmentType").equals("Part-Time") && num(afterEdit.get("salary")).compareTo(new BigDecimal("26000.50")) == 0
                && ((Map<?, ?>) afterEdit.get("schedule")).get("workdays").toString().equals("[Mon, Wed, Fri]") && ((Map<?, ?>) afterEdit.get("schedule")).get("breakMinutes").toString().equals("0"));
        status("edit unknown employee -> 404", admin.call("PUT", "/api/employees/TST-777", edited), 404);
        status("path-traversal style id -> 404", admin.call("GET", "/api/employees/..%2F..%2Fetc", null), 404);

        // ---- password reset
        Map<String, Object> reset = Json.obj("id", "TST-100", "name", "Juan D. Dela Cruz", "department", "Finance", "position", "Clerk", "salary", new BigDecimal("26000.50"),
                "employmentType", "Part-Time", "schedule", Json.parse(sched("09:00", "13:00", "Mon,Wed,Fri", 0)), "password", "Reset-Pass-123");
        status("admin resets TST-100 password", admin.call("PUT", "/api/employees/TST-100", Json.stringify(reset)), 200);
        status("old session of reset user is revoked", newHire.call("GET", "/api/auth/me", null), 401);
        status("old password no longer works", new Client().login("TST-100", "Newhire-Pass-1"), 401);
        status("new password works", new Client().login("TST-100", "Reset-Pass-123"), 200);

        // ---- attendance (employee only; server clock)
        status("TST-002 login", emp2.login("TST-002", "Employee-Pass-2"), 200);
        status("Time Out before Time In is refused", emp2.call("POST", "/api/me/attendance/time-out", null), 409);
        status("TST-002 Time In", emp2.call("POST", "/api/me/attendance/time-in", null), 200);
        R tin = emp1.call("POST", "/api/me/attendance/time-in", null);
        status("Time In", tin, 200);
        check("Time In record has server date + HH:mm", tin.obj().get("timeIn").toString().matches("\\d\\d:\\d\\d") && tin.obj().get("date").toString().equals(LocalDate.now(Config.get().zone).toString()));
        status("second Time In same day refused", emp1.call("POST", "/api/me/attendance/time-in", null), 409);
        status("Time Out", emp1.call("POST", "/api/me/attendance/time-out", null), 200);
        status("second Time Out refused", emp1.call("POST", "/api/me/attendance/time-out", null), 409);
        check("employee sees only own records", emp1.call("GET", "/api/me/attendance", null).list().stream().allMatch(o -> ((Map<?, ?>) o).get("employeeId").equals("TST-001")));
        check("admin sees everyone's records", admin.call("GET", "/api/attendance", null).list().stream().anyMatch(o -> ((Map<?, ?>) o).get("employeeId").equals("TST-002")));
        status("finance cannot read raw attendance", fin.call("GET", "/api/attendance", null), 403);

        // ---- payroll: calculation from attendance + schedules
        status("employee cannot refresh payroll", emp1.call("POST", "/api/finance/payroll/refresh", null), 403);
        R refreshed = fin.call("POST", "/api/finance/payroll/refresh", null);
        status("finance refreshes (calculates) payroll", refreshed, 200);
        check("currentPeriod label uses MM/dd/yy\u2013MM/dd/yy", refreshed.obj().get("currentPeriod").toString().matches("\\d\\d/\\d\\d/\\d\\d\u2013\\d\\d/\\d\\d/\\d\\d"));
        status("refresh twice is idempotent", fin.call("POST", "/api/finance/payroll/refresh", null), 200);
        List<Object> prevRows = new ArrayList<>();
        for (Object o : rows(fin.call("GET", "/api/finance/payroll", null))) if (((Map<?, ?>) o).get("period").equals(prevLabel)) prevRows.add(o);
        check("previous period has one row per active employee (4), no duplicates after 2 refreshes", prevRows.size() == 4);

        int sat = saturdays.size();
        Map<?, ?> p1 = find(prevRows, "employeeId", "TST-001"), p2 = find(prevRows, "employeeId", "TST-002"), p3 = find(prevRows, "employeeId", "TST-003");
        // TST-001: Mon-Fri, attended all but the last 2 weekdays + paid holiday on the last weekday  => n-1 of n days, 20 min overtime x (n-2)
        BigDecimal exp1 = expectedNet(new BigDecimal("36000"), n - 1, n, 20 * (n - 2));
        check("TST-001 paid days = attended + unattended holiday (" + (n - 1) + " of " + n + ")", ((Number) p1.get("days")).intValue() == n - 1);
        check("TST-001 net matches independent calculation (" + exp1 + ")", num(p1.get("net")).compareTo(exp1) == 0);
        check("late arrival (08:10) does not reduce pay: net == gross", num(p1.get("net")).compareTo(num(p1.get("gross"))) == 0 && num(p1.get("deductions")).signum() == 0);
        check("gross = basic + overtime", num(p1.get("gross")).compareTo(num(p1.get("basic")).add(num(p1.get("overtime")))) == 0);
        // TST-002: Mon-Sat, no attendance; weekday holidays + the Saturday holiday are scheduled days -> 3 paid days
        BigDecimal exp2 = expectedNet(new BigDecimal("42000"), 3, n + sat, 0);
        check("TST-002 (Mon-Sat) paid only the 3 holidays out of " + (n + sat) + " scheduled days", ((Number) p2.get("days")).intValue() == 3 && num(p2.get("net")).compareTo(exp2) == 0);
        // TST-003: part-time 09:00-13:00 no break, attended first 3 weekdays 09:00-14:00 (60 min overtime each) + last-weekday holiday
        BigDecimal exp3 = expectedNet(new BigDecimal("30000"), 4, n, 180);
        check("TST-003 (part-time) overtime measured against 4-hour schedule; net " + exp3, ((Number) p3.get("days")).intValue() == 4 && num(p3.get("net")).compareTo(exp3) == 0
                && num(p3.get("overtime")).compareTo(expectedNet(new BigDecimal("30000"), 0, n, 180)) == 0);
        check("finance row has the fields the UI needs", p1.get("id").toString().matches("PR-\\d{6}") && p1.get("name").equals("Test Employee One")
                && p1.get("status").equals("Pending") && p1.get("updated").toString().matches("\\d\\d/\\d\\d/\\d\\d"));
        check("employee sees NO payslips before release", emp1.call("GET", "/api/me/payslips", null).list().isEmpty());

        // ---- approval workflow
        String code1 = p1.get("id").toString(), code2 = p2.get("id").toString();
        status("release before approval refused", fin.call("POST", "/api/finance/payroll/" + code1 + "/release", null), 409);
        R appr = fin.call("POST", "/api/finance/payroll/" + code1 + "/approve", null);
        status("approve Pending payroll", appr, 200);
        check("status is now Approved", appr.obj().get("status").equals("Approved"));
        status("approve twice refused", fin.call("POST", "/api/finance/payroll/" + code1 + "/approve", null), 409);
        check("approved but not released: employee still sees nothing", emp1.call("GET", "/api/me/payslips", null).list().isEmpty());
        R rel = fin.call("POST", "/api/finance/payroll/" + code1 + "/release", null);
        status("release Approved payroll", rel, 200);
        check("status is now Released", rel.obj().get("status").equals("Released"));
        status("release twice refused", fin.call("POST", "/api/finance/payroll/" + code1 + "/release", null), 409);
        status("unknown payroll id -> 404", fin.call("POST", "/api/finance/payroll/PR-999999/approve", null), 404);
        status("malformed payroll id -> 404", fin.call("POST", "/api/finance/payroll/abc/approve", null), 404);
        List<Object> slips = emp1.call("GET", "/api/me/payslips", null).list();
        check("employee sees own Released payslip with matching net", slips.size() == 1 && ((Map<?, ?>) slips.get(0)).get("employeeId").equals("TST-001")
                && num(((Map<?, ?>) slips.get(0)).get("net")).compareTo(exp1) == 0 && Boolean.TRUE.equals(((Map<?, ?>) slips.get(0)).get("processed")) && ((Map<?, ?>) slips.get(0)).get("id").toString().startsWith("PAY-"));
        check("other employee cannot see TST-001's payslip", emp2.call("GET", "/api/me/payslips", null).list().isEmpty());

        // approved/released rows are frozen; pending rows follow new attendance
        sql("INSERT INTO attendance (employee_id, work_date, time_in, time_out) VALUES ('TST-001', ?, '08:00:00', '19:00:00')", weekdays.get(n - 2).toString()); // would add pay if recalculated
        sql("INSERT INTO attendance (employee_id, work_date, time_in, time_out) VALUES ('TST-003', ?, '09:00:00', '13:00:00')", weekdays.get(4).toString());
        fin.call("POST", "/api/finance/payroll/refresh", null);
        Map<?, ?> frozen = payrollRow(fin, "TST-001", prevLabel), moved = payrollRow(fin, "TST-003", prevLabel);
        check("Released row did not change when attendance changed", num(frozen.get("net")).compareTo(exp1) == 0 && frozen.get("status").equals("Released"));
        check("Pending row picked up the new attendance day (4 -> 5 paid days)", ((Number) moved.get("days")).intValue() == 5);
        status("approve TST-002 (Pending)", fin.call("POST", "/api/finance/payroll/" + code2 + "/approve", null), 200);

        // ---- audit visibility
        List<Object> sys = admin.call("GET", "/api/audit", null).list(), pay = fin.call("GET", "/api/audit", null).list();
        check("System History has 'Account Created' for TST-100 by the admin", sys.stream().anyMatch(o -> ((Map<?, ?>) o).get("action").equals("Account Created") && ((Map<?, ?>) o).get("target").equals("TST-100") && ((Map<?, ?>) o).get("actor").equals("TST-ADMIN")));
        check("System History has 'Employee Updated' and 'Password Reset'", sys.stream().anyMatch(o -> ((Map<?, ?>) o).get("action").equals("Employee Updated")) && sys.stream().anyMatch(o -> ((Map<?, ?>) o).get("action").equals("Password Reset")));
        check("Finance activity has approve + release entries by the finance user", pay.stream().anyMatch(o -> ((Map<?, ?>) o).get("action").equals("Payroll Released") && ((Map<?, ?>) o).get("actor").equals("TST-FIN"))
                && pay.stream().anyMatch(o -> ((Map<?, ?>) o).get("action").equals("Payroll Approved")));
        check("admin never sees payroll audit entries", sys.stream().noneMatch(o -> ((Map<?, ?>) o).get("action").toString().startsWith("Payroll")));
        check("finance never sees system audit entries", pay.stream().noneMatch(o -> ((Map<?, ?>) o).get("action").equals("Account Created")));
        check("history date looks like 'MM/dd/yy hh:mm AM'", sys.get(0) instanceof Map<?, ?> m0 && m0.get("date").toString().matches("\\d\\d/\\d\\d/\\d\\d \\d\\d:\\d\\d [AP]M"));

        // ---- account status: deactivate / reactivate / lock / unlock
        status("deactivate TST-003 via status endpoint", admin.call("POST", "/api/employees/TST-003/status", body("status", "Inactive")), 200);
        Map<?, ?> inactiveEmp = find(admin.call("GET", "/api/employees", null).list(), "id", "TST-003");
        check("accountStatus is Inactive", inactiveEmp.get("accountStatus").equals("Inactive") && inactiveEmp.get("status").equals("Inactive"));
        R inactive = new Client().login("TST-003", "Employee-Pass-3");
        status("inactive employee cannot log in", inactive, 403);
        check("inactive message shown (only after correct password)", inactive.obj().get("error").toString().contains("inactive") && new Client().login("TST-003", "wrong-password").status == 401);
        status("invalid status value -> 400", admin.call("POST", "/api/employees/TST-003/status", body("status", "Gone")), 400);
        Map<String, Object> viaEdit = Json.obj("id", "TST-003", "name", "Test Employee Three", "department", "Finance", "position", "Clerk", "salary", new BigDecimal("30000"),
                "employmentType", "Part-Time", "schedule", Json.parse(sched("09:00", "13:00", "Mon,Tue,Wed,Thu,Fri", 0)), "accountStatus", "Active");
        status("reactivate via the Edit Employee form (accountStatus=Active)", admin.call("PUT", "/api/employees/TST-003", Json.stringify(viaEdit)), 200);
        status("reactivated employee can log in again", emp3.login("TST-003", "Employee-Pass-3"), 200);
        status("deactivate TST-002 (has a session)", admin.call("POST", "/api/employees/TST-002/status", body("status", "Inactive")), 200);
        status("deactivated employee's existing session is revoked", emp2.call("GET", "/api/me/attendance", null), 401);
        check("history of deactivated employee's payroll is preserved", payrollRow(fin, "TST-002", prevLabel) != null);

        // lockout -> 'Locked' -> admin unlocks
        for (int i = 0; i < 5; i++) new Client().login("TST-100", "wrong-password-" + i);
        status("account locked after 5 failures (even with correct password)", new Client().login("TST-100", "Reset-Pass-123"), 429);
        check("accountStatus shows Locked on the admin list", find(admin.call("GET", "/api/employees", null).list(), "id", "TST-100").get("accountStatus").equals("Locked"));
        check("System History records the lock as 'Attention' by System", admin.call("GET", "/api/audit", null).list().stream().anyMatch(o -> ((Map<?, ?>) o).get("action").equals("Account Locked")
                && ((Map<?, ?>) o).get("actor").equals("System") && ((Map<?, ?>) o).get("result").equals("Attention")));
        Map<String, Object> unlock = new LinkedHashMap<>(reset); unlock.remove("password"); unlock.put("accountStatus", "Active");
        status("admin unlocks via Edit Employee", admin.call("PUT", "/api/employees/TST-100", Json.stringify(unlock)), 200);
        status("unlocked employee can log in", new Client().login("TST-100", "Reset-Pass-123"), 200);

        // Pending rows of people who stop being eligible (inactive and no attendance) are removed on the next refresh
        check("TST-100 (active, no attendance) has a Pending row for the previous period", payrollRow(fin, "TST-100", prevLabel) != null);
        status("deactivate TST-100 (no attendance)", admin.call("POST", "/api/employees/TST-100/status", body("status", "Inactive")), 200);
        fin.call("POST", "/api/finance/payroll/refresh", null);
        check("Pending row of an inactive employee with no attendance is removed", payrollRow(fin, "TST-100", prevLabel) == null);
        check("Approved row of an inactive employee is kept (history preserved)", payrollRow(fin, "TST-002", prevLabel) != null && payrollRow(fin, "TST-002", prevLabel).get("status").equals("Approved"));
        status("reactivate TST-100", admin.call("POST", "/api/employees/TST-100/status", body("status", "Active")), 200);

        status("logout", admin.call("POST", "/api/auth/logout", null), 204);
        status("session is dead after logout", admin.call("GET", "/api/auth/me", null), 401);

        cleanup();
        System.out.println("\n" + checks + " checks, " + failures + " failed");
        System.out.println(failures == 0 ? "INTEGRATION TESTS PASSED" : "INTEGRATION TESTS FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void seed() throws Exception {
        cleanup();
        sql("DELETE FROM payroll");
        sql("DELETE FROM holidays");
        sql("DELETE FROM audit_log");
        Object[][] emps = {
                {"TST-001", "Test Employee One", "36000", "Full-Time", "08:00", "17:00", "Mon,Tue,Wed,Thu,Fri", 60, "Employee-Pass-1"},
                {"TST-002", "Test Employee Two", "42000", "Agency-Hired", "07:00", "16:00", "Mon,Tue,Wed,Thu,Fri,Sat", 60, "Employee-Pass-2"},
                {"TST-003", "Test Employee Three", "30000", "Part-Time", "09:00", "13:00", "Mon,Tue,Wed,Thu,Fri", 0, "Employee-Pass-3"}};
        for (Object[] e : emps) {
            sql("INSERT INTO employees (id, full_name, department, position_title, monthly_salary, employment_type, shift_start, shift_end, workdays, break_minutes) VALUES (?, ?, 'Testing', 'Tester', ?, ?, ?, ?, ?, ?)",
                    e[0], e[1], new BigDecimal((String) e[2]), e[3], e[4], e[5], e[6], e[7]);
            sql("INSERT INTO users (login_id, role, employee_id, display_name, password_hash) VALUES (?, 'EMPLOYEE', ?, ?, ?)", e[0], e[0], e[1], Passwords.hash((String) e[8]));
        }
        sql("INSERT INTO users (login_id, role, employee_id, display_name, password_hash) VALUES ('TST-ADMIN', 'ADMIN', NULL, 'Test Admin', ?)", Passwords.hash("Admin-Test-Pass-1"));
        sql("INSERT INTO users (login_id, role, employee_id, display_name, password_hash) VALUES ('TST-FIN', 'FINANCE', NULL, 'Test Finance', ?)", Passwords.hash("Finance-Test-Pass-1"));

        LocalDate[] cur = periodFor(LocalDate.now(Config.get().zone));
        prevDates = periodFor(cur[0].minusDays(1));
        for (LocalDate d = prevDates[0]; !d.isAfter(prevDates[1]); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY) saturdays.add(d);
            else if (d.getDayOfWeek() != DayOfWeek.SUNDAY) weekdays.add(d);
        }
        int n = weekdays.size();
        // TST-001: every weekday except the last two, 08:10-17:30 (10 min late, 20 min over the 8 scheduled hours)
        for (int i = 0; i < n - 2; i++)
            sql("INSERT INTO attendance (employee_id, work_date, time_in, time_out) VALUES ('TST-001', ?, '08:10:00', '17:30:00')", weekdays.get(i).toString());
        // TST-003 (part-time 09:00-13:00): first 3 weekdays 09:00-14:00 = 60 min overtime each
        for (int i = 0; i < 3; i++)
            sql("INSERT INTO attendance (employee_id, work_date, time_in, time_out) VALUES ('TST-003', ?, '09:00:00', '14:00:00')", weekdays.get(i).toString());
        // holidays: an attended weekday (no double counting), the last weekday, and a Saturday (only TST-002 is scheduled on Saturdays)
        sql("INSERT INTO holidays (holiday_date, name) VALUES (?, 'Test holiday A')", weekdays.get(2).toString());
        sql("INSERT INTO holidays (holiday_date, name) VALUES (?, 'Test holiday B')", weekdays.get(n - 1).toString());
        sql("INSERT INTO holidays (holiday_date, name) VALUES (?, 'Test holiday C')", saturdays.get(0).toString());
        try (Connection c = Db.get(); PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM employees WHERE status = 'Active' AND id NOT LIKE 'TST-%'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            if (rs.getInt(1) != 0) {
                System.err.println("The test database contains non-test active employees; use an empty _test database.");
                System.exit(2);
            }
        }
    }

    static void cleanup() throws Exception {
        sql("DELETE FROM sessions");
        sql("DELETE FROM payroll WHERE employee_id LIKE 'TST-%'");
        sql("DELETE FROM attendance WHERE employee_id LIKE 'TST-%'");
        sql("DELETE FROM users WHERE login_id LIKE 'TST-%'");
        sql("DELETE FROM employees WHERE id LIKE 'TST-%'");
        sql("DELETE FROM holidays WHERE name LIKE 'Test holiday%'");
    }
}
