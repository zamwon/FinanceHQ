package com.example.finance_hq.transaction.dto;

import com.example.finance_hq.obligation.ObligationPeriod;
import com.example.finance_hq.transaction.Transaction;
import com.example.finance_hq.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        TransactionType type,
        String category,
        BigDecimal amount,
        String description,
        ObligationPeriod period,
        LocalDate date,
        Integer paymentDay,
        LocalDate endDate,
        Integer remainingPayments,
        LocalDate nextExpectedDate,
        UUID obligationId,
        LocalDateTime createdAt
) {
    public static TransactionResponse from(Transaction t, LocalDate nextExpectedDate) {
        return new TransactionResponse(
                t.getId(),
                t.getType(),
                t.getCategory(),
                t.getAmount(),
                t.getDescription(),
                t.getPeriod(),
                t.getDate(),
                t.getPaymentDay(),
                t.getEndDate(),
                t.getRemainingPayments(),
                nextExpectedDate,
                t.getObligation() != null ? t.getObligation().getId() : null,
                t.getCreatedAt()
        );
    }
}
