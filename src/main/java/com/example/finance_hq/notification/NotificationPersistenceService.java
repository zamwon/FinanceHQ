package com.example.finance_hq.notification;

import com.example.finance_hq.obligation.Obligation;
import com.example.finance_hq.obligation.ObligationPeriod;
import com.example.finance_hq.obligation.ObligationRepository;
import com.example.finance_hq.obligation.ObligationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
    public void recordSuccess(List<ObligationService.SchedulerTarget> targets) {
        LocalDateTime now = LocalDateTime.now();
        for (ObligationService.SchedulerTarget t : targets) {
            NotificationLog entry = new NotificationLog(t.obligation(), t.nextDueDate(), NotificationStatus.SENT);
            entry.setSentAt(now);
            notificationLogRepository.save(entry);
            decrementIfFixedTerm(t.obligation());
        }
    }

    @Transactional
    public void recordFailure(List<ObligationService.SchedulerTarget> targets) {
        for (ObligationService.SchedulerTarget t : targets) {
            notificationLogRepository.save(
                    new NotificationLog(t.obligation(), t.nextDueDate(), NotificationStatus.FAILED));
        }
    }

    @Transactional
    public void markRetrySuccess(List<NotificationLog> logs) {
        LocalDateTime now = LocalDateTime.now();
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
