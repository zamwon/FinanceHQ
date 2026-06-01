package com.example.finance_hq.notification;

import com.example.finance_hq.TestcontainersConfiguration;
import com.example.finance_hq.obligation.Obligation;
import com.example.finance_hq.obligation.ObligationCategory;
import com.example.finance_hq.obligation.ObligationPeriod;
import com.example.finance_hq.obligation.ObligationRepository;
import com.example.finance_hq.user.User;
import com.example.finance_hq.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class NotificationServiceIntegrationTest {

    @Autowired NotificationService notificationService;
    @Autowired NotificationLogRepository notificationLogRepository;
    @Autowired UserRepository userRepository;
    @Autowired ObligationRepository obligationRepository;
    @MockitoBean JavaMailSender mailSender;

    // TODAY = Monday 2026-06-02; previousBusinessDay(June 3) = June 2
    static final LocalDate TODAY = LocalDate.of(2026, 6, 2);

    User savedUser;

    @BeforeEach
    void setUp() {
        savedUser = userRepository.save(new User("notif-test@example.com", "hash"));
    }

    @Test
    void obligationDueTomorrow_sendsEmailAndWritesSentLog() {
        // paymentDay=3 (June 3); previousBusinessDay(June 3) = June 2 = TODAY
        obligationRepository.save(new Obligation(savedUser, "Rent", BigDecimal.valueOf(500),
                ObligationCategory.ESSENTIAL, ObligationPeriod.RECURRING, 3, null, null));

        notificationService.runDailyNotifications(TODAY);

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        List<NotificationLog> logs = notificationLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(logs.getFirst().getSentAt()).isNotNull();
    }

    @Test
    void obligationDueFurtherOut_doesNotSendEmail() {
        // paymentDay=5 (June 5); previousBusinessDay(June 5) = June 4 ≠ TODAY
        obligationRepository.save(new Obligation(savedUser, "Insurance", BigDecimal.valueOf(200),
                ObligationCategory.ESSENTIAL, ObligationPeriod.RECURRING, 5, null, null));

        notificationService.runDailyNotifications(TODAY);

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertThat(notificationLogRepository.count()).isZero();
    }

    @Test
    void failedNotificationLog_retriedAndUpdatedToSent() {
        Obligation obligation = obligationRepository.save(new Obligation(savedUser, "Rent",
                BigDecimal.valueOf(500), ObligationCategory.ESSENTIAL, ObligationPeriod.RECURRING,
                3, null, null));
        NotificationLog failedLog = notificationLogRepository.saveAndFlush(
                new NotificationLog(obligation, TODAY.plusDays(1), NotificationStatus.FAILED));

        notificationService.retryFailedNotifications();

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        NotificationLog updated = notificationLogRepository.findById(failedLog.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(updated.getSentAt()).isNotNull();
    }
}
