package com.example.finance_hq.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    boolean existsByObligationIdAndDueDate(UUID obligationId, LocalDate dueDate);

    @Query("SELECT nl FROM NotificationLog nl JOIN FETCH nl.obligation o JOIN FETCH o.user WHERE nl.status = :status")
    List<NotificationLog> findByStatusWithObligationAndUser(@Param("status") NotificationStatus status);
}
