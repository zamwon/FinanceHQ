package com.example.finance_hq.dashboard;

import com.example.finance_hq.dashboard.dto.CategoryBreakdownItem;
import com.example.finance_hq.dashboard.dto.MonthlyTrendItem;
import com.example.finance_hq.dashboard.dto.MonthlySummaryResponse;
import com.example.finance_hq.transaction.TransactionType;
import com.example.finance_hq.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private final DashboardRepository repository;

    public DashboardService(DashboardRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public MonthlySummaryResponse getMonthlySummary(User user, YearMonth month) {
        int year = month.getYear();
        int monthNum = month.getMonthValue();

        BigDecimal totalIncome = nullToZero(repository.sumByTypeAndMonth(user, TransactionType.INCOME, year, monthNum));
        BigDecimal totalExpenses = nullToZero(repository.sumByTypeAndMonth(user, TransactionType.EXPENSE, year, monthNum));
        BigDecimal netBalance = totalIncome.subtract(totalExpenses);

        List<CategoryBreakdownItem> expensesByCategory = toBreakdownItems(
                repository.categoryBreakdown(user, TransactionType.EXPENSE, year, monthNum));
        List<CategoryBreakdownItem> incomeByCategory = toBreakdownItems(
                repository.categoryBreakdown(user, TransactionType.INCOME, year, monthNum));

        return new MonthlySummaryResponse(month.toString(), totalIncome, totalExpenses, netBalance,
                expensesByCategory, incomeByCategory);
    }

    @Transactional(readOnly = true)
    public List<MonthlyTrendItem> getMonthlyTrend(User user, int months) {
        YearMonth end = YearMonth.now();
        YearMonth start = end.minusMonths(months - 1);

        LocalDate startDate = start.atDay(1);
        LocalDate endDate = end.atEndOfMonth().plusDays(1);

        List<Object[]> raw = repository.trendData(user, startDate, endDate);

        Map<YearMonth, BigDecimal[]> resultMap = new LinkedHashMap<>();
        for (int i = 0; i < months; i++) {
            resultMap.put(start.plusMonths(i), new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        }

        for (Object[] row : raw) {
            int year = ((Number) row[0]).intValue();
            int month = ((Number) row[1]).intValue();
            TransactionType type = (TransactionType) row[2];
            BigDecimal amount = (BigDecimal) row[3];
            YearMonth ym = YearMonth.of(year, month);
            if (resultMap.containsKey(ym)) {
                BigDecimal[] arr = resultMap.get(ym);
                if (type == TransactionType.INCOME) {
                    arr[0] = amount;
                } else {
                    arr[1] = amount;
                }
            }
        }

        return resultMap.entrySet().stream()
                .map(e -> {
                    BigDecimal income = e.getValue()[0];
                    BigDecimal expense = e.getValue()[1];
                    return new MonthlyTrendItem(e.getKey().toString(), income, expense, income.subtract(expense));
                })
                .toList();
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private List<CategoryBreakdownItem> toBreakdownItems(List<Object[]> rows) {
        return rows.stream()
                .map(row -> new CategoryBreakdownItem((String) row[0], (BigDecimal) row[1], ((Number) row[2]).longValue()))
                .toList();
    }
}
