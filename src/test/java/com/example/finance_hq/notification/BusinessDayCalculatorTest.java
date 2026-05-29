package com.example.finance_hq.notification;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDayCalculatorTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "2025-06-02, 2025-05-30",  // Monday -> Friday (skips Sat+Sun)
            "2025-06-03, 2025-06-02",  // Tuesday -> Monday
            "2025-06-04, 2025-06-03",  // Wednesday -> Tuesday
            "2025-06-05, 2025-06-04",  // Thursday -> Wednesday
            "2025-06-06, 2025-06-05",  // Friday -> Thursday
            "2025-06-07, 2025-06-06",  // Saturday -> Friday
            "2025-06-08, 2025-06-06",  // Sunday -> Friday
    })
    void previousBusinessDay(LocalDate input, LocalDate expected) {
        assertThat(BusinessDayCalculator.previousBusinessDay(input)).isEqualTo(expected);
    }
}
