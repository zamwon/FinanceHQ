package com.example.finance_hq.obligation;

import java.time.LocalDate;
import java.time.YearMonth;

final class NextDueDateComputer {

    private NextDueDateComputer() {}

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
