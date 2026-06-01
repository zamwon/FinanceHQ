package com.example.finance_hq.obligation;

import com.example.finance_hq.notification.BusinessDayCalculator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class NextDueDateComputerTest {

    @Test
    void recurring_paymentDayIsToday_returnsToday() {
        LocalDate today = LocalDate.of(2026, 5, 15);
        assertThat(NextDueDateComputer.compute(15, today, ObligationPeriod.RECURRING, null))
                .isEqualTo(today);
    }

    @Test
    void recurring_paymentDayInFuture_returnsThisMonth() {
        LocalDate today = LocalDate.of(2026, 5, 10);
        assertThat(NextDueDateComputer.compute(20, today, ObligationPeriod.RECURRING, null))
                .isEqualTo(LocalDate.of(2026, 5, 20));
    }

    @Test
    void recurring_paymentDayPast_returnsNextMonth() {
        LocalDate today = LocalDate.of(2026, 5, 20);
        assertThat(NextDueDateComputer.compute(10, today, ObligationPeriod.RECURRING, null))
                .isEqualTo(LocalDate.of(2026, 6, 10));
    }

    @Test
    void recurring_paymentDay31_clampedInFebruary() {
        LocalDate today = LocalDate.of(2026, 2, 1);
        // February 2026 has 28 days
        assertThat(NextDueDateComputer.compute(31, today, ObligationPeriod.RECURRING, null))
                .isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void recurring_paymentDay31_inFebruary_notifyDateIsFebruary27() {
        // Feb 28, 2026 is a Saturday; previousBusinessDay must skip it and land on Friday Feb 27
        LocalDate today = LocalDate.of(2026, 2, 1);
        LocalDate dueDate = NextDueDateComputer.compute(31, today, ObligationPeriod.RECURRING, null);
        assertThat(dueDate).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(BusinessDayCalculator.previousBusinessDay(dueDate)).isEqualTo(LocalDate.of(2026, 2, 27));
    }

    @Test
    void fixedTerm_candidateBeforeEndDate_returnsCandidate() {
        LocalDate today = LocalDate.of(2026, 5, 1);
        LocalDate endDate = LocalDate.of(2026, 12, 31);
        assertThat(NextDueDateComputer.compute(15, today, ObligationPeriod.FIXED_TERM, endDate))
                .isEqualTo(LocalDate.of(2026, 5, 15));
    }

    @Test
    void fixedTerm_endDatePast_returnsNull() {
        LocalDate today = LocalDate.of(2026, 5, 20);
        LocalDate endDate = LocalDate.of(2026, 5, 10);
        assertThat(NextDueDateComputer.compute(15, today, ObligationPeriod.FIXED_TERM, endDate))
                .isNull();
    }

    @Test
    void fixedTerm_candidateAfterEndDate_returnsEndDate() {
        LocalDate today = LocalDate.of(2026, 5, 20);
        LocalDate endDate = LocalDate.of(2026, 5, 25);
        // candidate would be May 28, endDate is May 25
        assertThat(NextDueDateComputer.compute(28, today, ObligationPeriod.FIXED_TERM, endDate))
                .isEqualTo(endDate);
    }
}
