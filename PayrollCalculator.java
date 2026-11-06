package com.petsa.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Payroll rules (taken from the UI's formulas and checked against its sample payslips):
 *   hourly rate    = monthly salary / 22 / 8
 *   overtime pay   = overtime hours x hourly rate x 1.25
 *   overtime hours = hours worked beyond the employee's SCHEDULED hours, per day
 *                    (hours worked = time out - time in - the schedule's break)
 *   basic pay      = half the monthly salary (one semi-monthly period) x paid days / scheduled days in the period
 *   gross          = basic + overtime;   net = gross - other deductions
 * Late arrivals do not reduce pay (the UI removed the late deduction).
 *
 * For a standard Mon-Fri employee with 11 scheduled days this equals daily rate (salary/22) x days paid,
 * e.g. 36,000 -> 18,000.00 for 11 days and 16,363.64 for 10 days (the UI's sample payslips).
 */
public final class PayrollCalculator {
    private PayrollCalculator() {}

    private static final BigDecimal TWO = BigDecimal.valueOf(2), WORKDAYS = BigDecimal.valueOf(22), HOURS = BigDecimal.valueOf(8),
            OT_RATE = new BigDecimal("1.25"), SIXTY = BigDecimal.valueOf(60);

    public record Result(int paidDays, BigDecimal basic, BigDecimal overtime, BigDecimal gross, BigDecimal deductions, BigDecimal net) {}

    public static int minutes(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
    }

    /** Minutes worked on a day: time out - time in - break (never negative). */
    public static int workedMinutes(Schedule s, int timeInMin, int timeOutMin) {
        return Math.max(0, timeOutMin - timeInMin - s.breakMinutes());
    }

    /** Minutes worked beyond the scheduled hours. */
    public static int overtimeMinutes(Schedule s, int timeInMin, int timeOutMin) {
        return Math.max(0, workedMinutes(s, timeInMin, timeOutMin) - s.scheduledMinutes());
    }

    public static Result compute(BigDecimal monthlySalary, int paidDays, int scheduledDays, int overtimeMinutes) {
        BigDecimal basic = scheduledDays <= 0 ? BigDecimal.ZERO.setScale(2)
                : round2(monthlySalary.divide(TWO, 10, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(paidDays))
                .divide(BigDecimal.valueOf(scheduledDays), 10, RoundingMode.HALF_UP));
        BigDecimal hourly = monthlySalary.divide(WORKDAYS, 10, RoundingMode.HALF_UP).divide(HOURS, 10, RoundingMode.HALF_UP);
        BigDecimal overtime = round2(hourly.multiply(OT_RATE).multiply(BigDecimal.valueOf(overtimeMinutes)).divide(SIXTY, 10, RoundingMode.HALF_UP));
        BigDecimal gross = basic.add(overtime);
        BigDecimal deductions = BigDecimal.ZERO.setScale(2);
        return new Result(paidDays, basic, overtime, gross, deductions, gross.subtract(deductions));
    }

    private static BigDecimal round2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
