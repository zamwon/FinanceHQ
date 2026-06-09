package com.example.finance_hq.obligation.dto;

import com.example.finance_hq.obligation.ObligationCategory;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record UpdateObligationRequest(
        @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @Min(1) @Max(31) Integer paymentDay,
        ObligationCategory category
) {}
