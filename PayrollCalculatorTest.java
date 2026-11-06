package com.petsa.api;

import com.petsa.util.Json;
import com.petsa.util.Passwords;

import java.math.BigDecimal;
import java.util.List;

/** Dependency-free checks. Run with scripts/test (exit code 1 on failure). */
public final class PayrollCalculatorTest {
    static int failures = 0;

    static void eq(String name, Object expected, Object actual) {
        if (!expected.toString().equals(actual.toString())) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + " but got " + actual);
        } else System.out.println("ok   " + name);
    }

    static Schedule sched(String start, String end, int brk) {
        return new Schedule(start, end, List.of("Mon", "Tue", "Wed", "Thu", "Fri"), brk);
    }

    public static void main(String[] args) {
        BigDecimal salary = new BigDecimal("36000");

        // The UI's sample payslips for a 36,000/month employee on a standard Mon-Fri schedule (11 scheduled days)
        eq("11 of 11 days -> basic 18,000.00", "18000.00", PayrollCalculator.compute(salary, 11, 11, 0).basic());
        eq("10 of 11 days -> basic 16,363.64", "16363.64", PayrollCalculator.compute(salary, 10, 11, 0).basic());
        eq("150 min overtime -> 639.20", "639.20", PayrollCalculator.compute(salary, 11, 11, 150).overtime());
        eq("100 min overtime -> 426.14", "426.14", PayrollCalculator.compute(salary, 11, 11, 100).overtime());
        var full = PayrollCalculator.compute(salary, 11, 11, 150);
        eq("gross = basic + overtime", "18639.20", full.gross());
        eq("net = gross (no late deduction)", "18639.20", full.net());
        eq("deductions default to 0", "0.00", full.deductions());
        eq("10 days + 100 min overtime gross", "16789.78", PayrollCalculator.compute(salary, 10, 11, 100).gross());

        // Other schedules: pay is half the monthly salary for full attendance, whatever the number of scheduled days
        eq("Mon-Sat employee, 13 of 13 days -> half salary", "17500.00", PayrollCalculator.compute(new BigDecimal("35000"), 13, 13, 0).basic());
        eq("Mon-Sat employee, 12 of 13 days", "16153.85", PayrollCalculator.compute(new BigDecimal("35000"), 12, 13, 0).basic());
        eq("no scheduled days -> 0", "0.00", PayrollCalculator.compute(salary, 0, 0, 0).basic());
        eq("absent all period -> 0 basic", "0.00", PayrollCalculator.compute(salary, 0, 11, 0).basic());

        // Overtime is measured against the employee's own schedule (as the UI's metrics() does)
        Schedule office = sched("08:00", "17:00", 60);                 // 8 scheduled hours
        eq("scheduled minutes 8-17 with 60 break", 480, office.scheduledMinutes());
        eq("no overtime when leaving on time", 0, PayrollCalculator.overtimeMinutes(office, 8 * 60, 17 * 60));
        eq("overtime 8:12-17:35 = 23 min", 23, PayrollCalculator.overtimeMinutes(office, 8 * 60 + 12, 17 * 60 + 35));
        Schedule partTime = sched("09:00", "13:00", 0);               // 4 scheduled hours, no break
        eq("part-timer scheduled minutes", 240, partTime.scheduledMinutes());
        eq("part-timer working 9-14 = 60 min overtime", 60, PayrollCalculator.overtimeMinutes(partTime, 9 * 60, 14 * 60));
        Schedule early = sched("06:00", "15:00", 60);
        eq("early shift 5:55-16:00 = 65 min overtime", 65, PayrollCalculator.overtimeMinutes(early, 5 * 60 + 55, 16 * 60));
        eq("negative time never counts", 0, PayrollCalculator.workedMinutes(office, 17 * 60, 8 * 60));

        // Schedule validation
        eq("workdays map to Java days", "[MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]", new java.util.TreeSet<>(office.days()));

        // Passwords
        String h = Passwords.hash("correct horse battery");
        eq("hash format", true, h.startsWith("pbkdf2_sha256$600000$"));
        eq("hash verifies", true, Passwords.verify("correct horse battery", h));
        eq("wrong password rejected", false, Passwords.verify("wrong password", h));
        eq("hash is salted", false, h.equals(Passwords.hash("correct horse battery")));
        eq("garbage hash rejected", false, Passwords.verify("x", "plaintext"));

        // JSON
        Object o = Json.parse("{\"a\":[1,2.50,\"x\\u00e9\"],\"b\":null,\"c\":true}");
        eq("json roundtrip", "{\"a\":[1,2.50,\"x\u00e9\"],\"b\":null,\"c\":true}", Json.stringify(o));
        eq("json escapes <", "\"\\u003cb\\u003e\"", Json.stringify("<b>"));
        boolean threw = false;
        try { Json.parse("{\"a\":"); } catch (Json.JsonException e) { threw = true; }
        eq("json rejects truncated input", true, threw);

        System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " TEST(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
