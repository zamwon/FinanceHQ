package com.example.finance_hq.transaction.dto;

import com.example.finance_hq.obligation.ObligationPeriod;
import com.example.finance_hq.transaction.TransactionType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateTransactionRequest(
        TransactionType type,
        @Size(max = 50) String category,
        @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @Size(max = 255) String description,
        ObligationPeriod period,
        LocalDate date,
        @Min(1) @Max(31) Integer paymentDay,
        LocalDate endDate,
        @Min(1) Integer remainingPayments
) {}
