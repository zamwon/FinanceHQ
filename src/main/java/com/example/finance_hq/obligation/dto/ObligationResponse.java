package com.example.finance_hq.obligation.dto;

import com.example.finance_hq.obligation.Obligation;
import com.example.finance_hq.obligation.ObligationCategory;
import com.example.finance_hq.obligation.ObligationPeriod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ObligationResponse(
        UUID id,
        String name,
        BigDecimal amount,
        ObligationCategory category,
        ObligationPeriod period,
        Integer paymentDay,
        LocalDate endDate,
        Integer remainingPayments,
        LocalDate nextDueDate,
        LocalDateTime createdAt
) {
    public static ObligationResponse from(Obligation o, LocalDate nextDueDate) {
        return new ObligationResponse(
                o.getId(),
                o.getName(),
                o.getAmount(),
                o.getCategory(),
                o.getPeriod(),
                o.getPaymentDay(),
                o.getEndDate(),
                o.getRemainingPayments(),
                nextDueDate,
                o.getCreatedAt()
        );
    }
}
