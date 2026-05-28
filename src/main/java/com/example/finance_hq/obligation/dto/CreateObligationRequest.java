package com.example.finance_hq.obligation.dto;

import com.example.finance_hq.obligation.ObligationCategory;
import com.example.finance_hq.obligation.ObligationPeriod;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateObligationRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotNull ObligationCategory category,
        @NotNull ObligationPeriod period,
        @NotNull @Min(1) @Max(31) Integer paymentDay,
        LocalDate endDate,
        Integer remainingPayments
) {}
