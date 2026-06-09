package com.example.finance_hq.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

public record MonthlySummaryResponse(
        String month,
        BigDecimal totalIncome,
        BigDecimal totalExpenses,
        BigDecimal netBalance,
        List<CategoryBreakdownItem> expensesByCategory,
        List<CategoryBreakdownItem> incomeByCategory
) {}
