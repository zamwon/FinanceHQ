package com.example.finance_hq.dashboard.dto;

import java.math.BigDecimal;

public record CategoryBreakdownItem(String category, BigDecimal total, long count) {}
