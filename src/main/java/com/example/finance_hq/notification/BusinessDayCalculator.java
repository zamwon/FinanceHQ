package com.example.finance_hq.notification;

import java.time.DayOfWeek;
import java.time.LocalDate;

public final class BusinessDayCalculator {

    private BusinessDayCalculator() {}

    public static LocalDate previousBusinessDay(LocalDate date) {
        LocalDate result = date.minusDays(1);
        while (result.getDayOfWeek() == DayOfWeek.SATURDAY || result.getDayOfWeek() == DayOfWeek.SUNDAY) {
            result = result.minusDays(1);
        }
        return result;
    }
}
