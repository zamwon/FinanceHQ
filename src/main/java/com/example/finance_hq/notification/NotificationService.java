package com.example.finance_hq.notification;

import com.example.finance_hq.obligation.Obligation;
import com.example.finance_hq.obligation.ObligationService;
import com.example.finance_hq.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ObligationService obligationService;
    private final NotificationLogRepository notificationLogRepository;
    private final NotificationPersistenceService persistenceService;
    private final JavaMailSender mailSender;
    private final String fromAddress;

    public NotificationService(ObligationService obligationService,
                               NotificationLogRepository notificationLogRepository,
                               NotificationPersistenceService persistenceService,
                               JavaMailSender mailSender,
                               @Value("${spring.mail.username}") String fromAddress) {
        this.obligationService = obligationService;
        this.notificationLogRepository = notificationLogRepository;
        this.persistenceService = persistenceService;
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    public void runDailyNotifications(LocalDate today) {
        List<ObligationService.SchedulerTarget> targets = obligationService.findAllSchedulerTargets(today);

        List<ObligationService.SchedulerTarget> dateDue = targets.stream()
                .filter(t -> BusinessDayCalculator.previousBusinessDay(t.nextDueDate()).equals(today))
                .toList();

        Set<LocalDate> dueDates = dateDue.stream().map(ObligationService.SchedulerTarget::nextDueDate)
                .collect(Collectors.toSet());
        Set<UUID> alreadyLogged = dueDates.isEmpty()
                ? Set.of()
                : notificationLogRepository.findAlreadyLoggedObligationIds(dueDates);

        List<ObligationService.SchedulerTarget> due = dateDue.stream()
                .filter(t -> !alreadyLogged.contains(t.obligation().getId()))
                .toList();

        Map<String, List<ObligationService.SchedulerTarget>> byUserAndDate = due.stream()
                .collect(Collectors.groupingBy(t ->
                        t.obligation().getUser().getId() + ":" + t.nextDueDate()));

        byUserAndDate.forEach((key, userTargets) -> {
            User user = userTargets.getFirst().obligation().getUser();
            LocalDate dueDate = userTargets.getFirst().nextDueDate();
            try {
                persistenceService.recordPending(userTargets);
            } catch (DataIntegrityViolationException e) {
                log.info("Skipping already-processed group for user {} due {}", user.getEmail(), dueDate);
                return;
            }
            try {
                sendGroupedEmail(user, userTargets, dueDate);
                persistenceService.recordSuccess(userTargets);
            } catch (MailException e) {
                log.error("Failed to send notification to {}: {}", user.getEmail(), e.getMessage());
                persistenceService.recordFailure(userTargets);
            } catch (RuntimeException e) {
                log.error("recordSuccess failed for {} due {} — marking FAILED for retry: {}", user.getEmail(), dueDate, e.getMessage());
                persistenceService.recordFailure(userTargets);
            }
        });
    }

    public void retryFailedNotifications() {
        List<NotificationLog> failed = notificationLogRepository
                .findByStatusWithObligationAndUser(NotificationStatus.FAILED);

        Map<String, List<NotificationLog>> byGroup = failed.stream()
                .collect(Collectors.groupingBy(nl ->
                        nl.getObligation().getUser().getId() + ":" + nl.getDueDate()));

        byGroup.forEach((key, logs) -> {
            User user = logs.getFirst().getObligation().getUser();
            LocalDate dueDate = logs.getFirst().getDueDate();
            List<Obligation> obligations = logs.stream().map(NotificationLog::getObligation).toList();
            try {
                sendGroupedEmailForObligations(user, obligations, dueDate);
                persistenceService.markRetrySuccess(logs);
            } catch (MailException e) {
                log.error("Retry failed for {}: {}", user.getEmail(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("markRetrySuccess failed for {} due {} — group skipped: {}", user.getEmail(), dueDate, e.getMessage());
            }
        });
    }

    private void sendGroupedEmail(User user, List<ObligationService.SchedulerTarget> targets, LocalDate dueDate) {
        List<Obligation> obligations = targets.stream().map(ObligationService.SchedulerTarget::obligation).toList();
        sendGroupedEmailForObligations(user, obligations, dueDate);
    }

    private void sendGroupedEmailForObligations(User user, List<Obligation> obligations, LocalDate dueDate) {
        int n = obligations.size();
        StringBuilder body = new StringBuilder();
        body.append("You have ").append(n).append(" payment(s) due on ").append(dueDate).append(":\n\n");
        for (Obligation o : obligations) {
            body.append(o.getName()).append(" — ").append(o.getAmount()).append("\n");
        }
        body.append("\nThis is an automated reminder from FinanceHQ.");

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(user.getEmail());
        message.setSubject("FinanceHQ: " + n + " payment(s) due " + dueDate);
        message.setText(body.toString());
        mailSender.send(message);
    }
}
