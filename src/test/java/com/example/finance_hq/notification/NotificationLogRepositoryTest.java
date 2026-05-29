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
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class NotificationLogRepositoryTest {

    @Autowired NotificationLogRepository notificationLogRepository;
    @Autowired UserRepository userRepository;
    @Autowired ObligationRepository obligationRepository;

    static final LocalDate DUE = LocalDate.of(2025, 7, 15);

    User savedUser;
    Obligation savedObligation;

    @BeforeEach
    void setUp() {
        savedUser = userRepository.save(new User("test-notif@example.com", "hash"));
        savedObligation = obligationRepository.save(new Obligation(
                savedUser, "Rent", BigDecimal.valueOf(1000),
                ObligationCategory.ESSENTIAL, ObligationPeriod.RECURRING, 15, null, null));
    }

    @Test
    void existsByObligationIdAndDueDate_returnsFalseWhenNoRow() {
        assertThat(notificationLogRepository
                .existsByObligationIdAndDueDate(savedObligation.getId(), DUE))
                .isFalse();
    }

    @Test
    void existsByObligationIdAndDueDate_returnsTrueAfterInsert() {
        notificationLogRepository.saveAndFlush(
                new NotificationLog(savedObligation, DUE, NotificationStatus.SENT));

        assertThat(notificationLogRepository
                .existsByObligationIdAndDueDate(savedObligation.getId(), DUE))
                .isTrue();
    }

    @Test
    void uniqueConstraint_preventsInsertOfDuplicateObligationAndDueDate() {
        notificationLogRepository.saveAndFlush(
                new NotificationLog(savedObligation, DUE, NotificationStatus.SENT));

        assertThatThrownBy(() -> notificationLogRepository.saveAndFlush(
                new NotificationLog(savedObligation, DUE, NotificationStatus.FAILED)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByStatusWithObligationAndUser_returnsOnlyFailedRows() {
        notificationLogRepository.saveAndFlush(
                new NotificationLog(savedObligation, DUE, NotificationStatus.FAILED));
        notificationLogRepository.saveAndFlush(
                new NotificationLog(savedObligation, DUE.plusMonths(1), NotificationStatus.SENT));

        List<NotificationLog> failed = notificationLogRepository
                .findByStatusWithObligationAndUser(NotificationStatus.FAILED);

        assertThat(failed).hasSize(1);
        assertThat(failed.getFirst().getDueDate()).isEqualTo(DUE);
    }

    @Test
    void findByStatusWithObligationAndUser_associationsAccessibleOutsideSession() {
        notificationLogRepository.saveAndFlush(
                new NotificationLog(savedObligation, DUE, NotificationStatus.FAILED));

        List<NotificationLog> failed = notificationLogRepository
                .findByStatusWithObligationAndUser(NotificationStatus.FAILED);

        // JOIN FETCH means no LazyInitializationException when accessing nested associations
        assertThat(failed.getFirst().getObligation().getUser().getEmail())
                .isEqualTo("test-notif@example.com");
    }
}
