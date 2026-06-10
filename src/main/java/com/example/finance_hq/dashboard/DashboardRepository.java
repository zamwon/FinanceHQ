package com.example.finance_hq.dashboard;

import com.example.finance_hq.transaction.Transaction;
import com.example.finance_hq.transaction.TransactionType;
import com.example.finance_hq.user.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DashboardRepository extends Repository<Transaction, UUID> {

    @Query("SELECT SUM(t.amount) FROM Transaction t WHERE t.user = :user AND t.period IS NULL AND t.type = :type AND t.date >= :startDate AND t.date < :endDate")
    BigDecimal sumByTypeAndMonth(@Param("user") User user, @Param("type") TransactionType type, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT t.category, SUM(t.amount), COUNT(t) FROM Transaction t WHERE t.user = :user AND t.period IS NULL AND t.type = :type AND t.date >= :startDate AND t.date < :endDate GROUP BY t.category")
    List<Object[]> categoryBreakdown(@Param("user") User user, @Param("type") TransactionType type, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT YEAR(t.date), MONTH(t.date), t.type, SUM(t.amount) FROM Transaction t WHERE t.user = :user AND t.period IS NULL AND t.date >= :startDate AND t.date < :endDate GROUP BY YEAR(t.date), MONTH(t.date), t.type ORDER BY YEAR(t.date), MONTH(t.date)")
    List<Object[]> trendData(@Param("user") User user, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}
