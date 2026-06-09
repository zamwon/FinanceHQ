package com.example.finance_hq.transaction;

import com.example.finance_hq.obligation.Obligation;
import com.example.finance_hq.obligation.ObligationPeriod;
import com.example.finance_hq.user.User;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "obligation_id")
    private Obligation obligation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column
    private String description;

    @Enumerated(EnumType.STRING)
    @Column
    private ObligationPeriod period;

    @Column
    private LocalDate date;

    @Column(name = "payment_day")
    private Integer paymentDay;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "remaining_payments")
    private Integer remainingPayments;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Transaction() {}

    public Transaction(User user, Obligation obligation, TransactionType type, String category,
                       BigDecimal amount, String description, ObligationPeriod period,
                       LocalDate date, Integer paymentDay, LocalDate endDate, Integer remainingPayments) {
        this.user = user;
        this.obligation = obligation;
        this.type = type;
        this.category = category;
        this.amount = amount;
        this.description = description;
        this.period = period;
        this.date = date;
        this.paymentDay = paymentDay;
        this.endDate = endDate;
        this.remainingPayments = remainingPayments;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public Obligation getObligation() { return obligation; }
    public TransactionType getType() { return type; }
    public String getCategory() { return category; }
    public BigDecimal getAmount() { return amount; }
    public String getDescription() { return description; }
    public ObligationPeriod getPeriod() { return period; }
    public LocalDate getDate() { return date; }
    public Integer getPaymentDay() { return paymentDay; }
    public LocalDate getEndDate() { return endDate; }
    public Integer getRemainingPayments() { return remainingPayments; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setType(TransactionType type) { this.type = type; }
    public void setCategory(String category) { this.category = category; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public void setDescription(String description) { this.description = description; }
    public void setPeriod(ObligationPeriod period) { this.period = period; }
    public void setDate(LocalDate date) { this.date = date; }
    public void setPaymentDay(Integer paymentDay) { this.paymentDay = paymentDay; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public void setRemainingPayments(Integer remainingPayments) { this.remainingPayments = remainingPayments; }
}
