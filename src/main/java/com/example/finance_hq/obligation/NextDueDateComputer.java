package com.example.finance_hq.obligation;

import java.time.LocalDate;
import java.time.YearMonth;

final class NextDueDateComputer {

    private NextDueDateComputer() {}

    // RECURRING obligations ignore endDate entirely; the two FIXED_TERM guards below are skipped.
    // Guard 1: obligation already past its end date → no next due date.
    // Guard 2: next candidate would fall after the end date → return the end date as the final payment.
    public static LocalDate compute(int paymentDay, LocalDate today, ObligationPeriod period, LocalDate endDate) {
        if (period == ObligationPeriod.FIXED_TERM && endDate != null && endDate.isBefore(today)) {
            return null;
        }

        LocalDate candidate = clampToMonth(paymentDay, YearMonth.from(today));
        if (candidate.isBefore(today)) {
            candidate = clampToMonth(paymentDay, YearMonth.from(today).plusMonths(1));
        }

        if (period == ObligationPeriod.FIXED_TERM && endDate != null && candidate.isAfter(endDate)) {
            return endDate;
        }

        return candidate;
    }

    private static LocalDate clampToMonth(int paymentDay, YearMonth month) {
        int lastDay = month.lengthOfMonth();
        return month.atDay(Math.min(paymentDay, lastDay));
    }
}
