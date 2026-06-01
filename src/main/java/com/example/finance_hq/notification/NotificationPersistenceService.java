package com.example.finance_hq.notification;

import com.example.finance_hq.obligation.Obligation;
import com.example.finance_hq.obligation.ObligationPeriod;
import com.example.finance_hq.obligation.ObligationRepository;
import com.example.finance_hq.obligation.ObligationService;
import com.example.finance_hq.obligation.ObligationService.SchedulerTarget;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class NotificationPersistenceService {

    private final NotificationLogRepository notificationLogRepository;
    private final ObligationRepository obligationRepository;

    public NotificationPersistenceService(NotificationLogRepository notificationLogRepository,
                                          ObligationRepository obligationRepository) {
        this.notificationLogRepository = notificationLogRepository;
        this.obligationRepository = obligationRepository;
    }

    @Transactional
    public void recordPending(List<SchedulerTarget> targets) {
        for (SchedulerTarget t : targets) {
            notificationLogRepository.saveAndFlush(
                    new NotificationLog(t.obligation(), t.nextDueDate(), NotificationStatus.PENDING));
        }
    }

    @Transactional
    public void recordSuccess(List<SchedulerTarget> targets) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Europe/Warsaw"));
        for (SchedulerTarget t : targets) {
            Optional<NotificationLog> existing = notificationLogRepository
                    .findByObligationIdAndDueDate(t.obligation().getId(), t.nextDueDate());
            existing.ifPresentOrElse(entry -> {
                entry.setStatus(NotificationStatus.SENT);
                entry.setSentAt(now);
                notificationLogRepository.save(entry);
            }, () -> log.warn("PENDING row not found for obligation {} due {} — log skipped",
                    t.obligation().getId(), t.nextDueDate()));
            decrementIfFixedTerm(t.obligation());
        }
    }

    @Transactional
    public void recordFailure(List<SchedulerTarget> targets) {
        for (SchedulerTarget t : targets) {
            Optional<NotificationLog> existing = notificationLogRepository
                    .findByObligationIdAndDueDate(t.obligation().getId(), t.nextDueDate());
            existing.ifPresentOrElse(entry -> {
                entry.setStatus(NotificationStatus.FAILED);
                notificationLogRepository.save(entry);
            }, () -> log.warn("PENDING row not found for obligation {} due {} — log skipped",
                    t.obligation().getId(), t.nextDueDate()));
        }
    }

    // Only called for FAILED rows; UNIQUE(obligation_id, due_date) prevents a SENT row coexisting,
    // so double-decrement of remainingPayments is impossible.
    @Transactional
    public void markRetrySuccess(List<NotificationLog> logs) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Europe/Warsaw"));
        for (NotificationLog nl : logs) {
            nl.setStatus(NotificationStatus.SENT);
            nl.setSentAt(now);
            notificationLogRepository.save(nl);
            decrementIfFixedTerm(nl.getObligation());
        }
    }

    private void decrementIfFixedTerm(Obligation obligation) {
        if (obligation.getPeriod() == ObligationPeriod.FIXED_TERM
                && obligation.getRemainingPayments() != null
                && obligation.getRemainingPayments() > 0) {
            obligation.setRemainingPayments(obligation.getRemainingPayments() - 1);
            obligationRepository.save(obligation);
        }
    }
}
