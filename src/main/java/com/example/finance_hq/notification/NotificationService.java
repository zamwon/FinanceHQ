package com.example.finance_hq.notification;

import com.example.finance_hq.obligation.Obligation;
import com.example.finance_hq.obligation.ObligationPeriod;
import com.example.finance_hq.obligation.ObligationService;
import com.example.finance_hq.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ObligationService obligationService;
    private final NotificationLogRepository notificationLogRepository;
    private final JavaMailSender mailSender;
    private final String fromAddress;

    public NotificationService(ObligationService obligationService,
                               NotificationLogRepository notificationLogRepository,
                               JavaMailSender mailSender,
                               @Value("${spring.mail.username}") String fromAddress) {
        this.obligationService = obligationService;
        this.notificationLogRepository = notificationLogRepository;
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    public void runDailyNotifications(LocalDate today) {
        List<ObligationService.SchedulerTarget> targets = obligationService.findAllSchedulerTargets(today);

        List<ObligationService.SchedulerTarget> due = targets.stream()
                .filter(t -> BusinessDayCalculator.previousBusinessDay(t.nextDueDate()).equals(today))
                .filter(t -> !notificationLogRepository.existsByObligationIdAndDueDate(
                        t.obligation().getId(), t.nextDueDate()))
                .toList();

        Map<User, List<ObligationService.SchedulerTarget>> byUser = due.stream()
                .collect(Collectors.groupingBy(t -> t.obligation().getUser()));

        byUser.forEach((user, userTargets) -> {
            LocalDate dueDate = userTargets.get(0).nextDueDate();
            try {
                sendGroupedEmail(user, userTargets, dueDate);
                recordSuccess(userTargets);
            } catch (MailException e) {
                log.error("Failed to send notification to {}: {}", user.getEmail(), e.getMessage());
                recordFailure(userTargets);
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
            User user = logs.get(0).getObligation().getUser();
            LocalDate dueDate = logs.get(0).getDueDate();
            List<Obligation> obligations = logs.stream().map(NotificationLog::getObligation).toList();
            try {
                sendGroupedEmailForObligations(user, obligations, dueDate);
                markRetrySuccess(logs);
            } catch (MailException e) {
                log.error("Retry failed for {}: {}", user.getEmail(), e.getMessage());
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

    @Transactional
    void recordSuccess(List<ObligationService.SchedulerTarget> targets) {
        LocalDateTime now = LocalDateTime.now();
        for (ObligationService.SchedulerTarget t : targets) {
            NotificationLog entry = new NotificationLog(t.obligation(), t.nextDueDate(), NotificationStatus.SENT);
            entry.setSentAt(now);
            notificationLogRepository.save(entry);
            decrementIfFixedTerm(t.obligation());
        }
    }

    @Transactional
    void recordFailure(List<ObligationService.SchedulerTarget> targets) {
        for (ObligationService.SchedulerTarget t : targets) {
            notificationLogRepository.save(
                    new NotificationLog(t.obligation(), t.nextDueDate(), NotificationStatus.FAILED));
        }
    }

    @Transactional
    void markRetrySuccess(List<NotificationLog> logs) {
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
        }
    }
}
