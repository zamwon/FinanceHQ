package com.example.finance_hq.notification;

import com.example.finance_hq.obligation.Obligation;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static jakarta.persistence.EnumType.STRING;
import static jakarta.persistence.FetchType.LAZY;

@Entity
@Table(name = "notification_log")
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "obligation_id", nullable = false)
    private Obligation obligation;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    public NotificationLog(Obligation obligation, LocalDate dueDate, NotificationStatus status) {
        this.obligation = obligation;
        this.dueDate = dueDate;
        this.status = status;
    }

    protected NotificationLog() {}

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(ZoneId.of("Europe/Warsaw"));
        }
    }

    public UUID getId() { return id; }
    public Obligation getObligation() { return obligation; }
    public LocalDate getDueDate() { return dueDate; }
    public NotificationStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getSentAt() { return sentAt; }

    public void setStatus(NotificationStatus status) { this.status = status; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
}
