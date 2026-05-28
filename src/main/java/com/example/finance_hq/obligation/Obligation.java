package com.example.finance_hq.obligation;

import com.example.finance_hq.user.User;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "obligations")
public class Obligation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ObligationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ObligationPeriod period;

    @Column(name = "payment_day", nullable = false)
    private Integer paymentDay;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "remaining_payments")
    private Integer remainingPayments;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Obligation() {}

    public Obligation(User user, String name, BigDecimal amount, ObligationCategory category,
                      ObligationPeriod period, Integer paymentDay, LocalDate endDate,
                      Integer remainingPayments) {
        this.user = user;
        this.name = name;
        this.amount = amount;
        this.category = category;
        this.period = period;
        this.paymentDay = paymentDay;
        this.endDate = endDate;
        this.remainingPayments = remainingPayments;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getName() { return name; }
    public BigDecimal getAmount() { return amount; }
    public ObligationCategory getCategory() { return category; }
    public ObligationPeriod getPeriod() { return period; }
    public Integer getPaymentDay() { return paymentDay; }
    public LocalDate getEndDate() { return endDate; }
    public Integer getRemainingPayments() { return remainingPayments; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setPaymentDay(Integer paymentDay) { this.paymentDay = paymentDay; }
}
