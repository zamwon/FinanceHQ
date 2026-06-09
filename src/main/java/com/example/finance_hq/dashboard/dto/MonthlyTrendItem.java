package com.example.finance_hq.dashboard.dto;

import java.math.BigDecimal;

public record MonthlyTrendItem(String month, BigDecimal totalIncome, BigDecimal totalExpenses, BigDecimal netBalance) {}
