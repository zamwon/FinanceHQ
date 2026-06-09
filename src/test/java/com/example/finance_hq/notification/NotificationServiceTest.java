package com.example.finance_hq.notification;

import com.example.finance_hq.obligation.Obligation;
import com.example.finance_hq.obligation.ObligationCategory;
import com.example.finance_hq.obligation.ObligationPeriod;
import com.example.finance_hq.obligation.ObligationService;
import com.example.finance_hq.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock ObligationService obligationService;
    @Mock NotificationLogRepository notificationLogRepository;
    @Mock NotificationPersistenceService persistenceService;
    @Mock EmailSender emailSender;

    NotificationService service;

    // today = Monday 2025-06-02; previousBusinessDay(2025-06-03) = 2025-06-02
    static final LocalDate TODAY = LocalDate.of(2025, 6, 2);
    static final LocalDate DUE_TOMORROW = LocalDate.of(2025, 6, 3);  // Tuesday

    @BeforeEach
    void setUp() {
        service = new NotificationService(obligationService, notificationLogRepository,
                persistenceService, emailSender);
    }

    @Test
    void skipsObligationWhereNotificationDateIsNotToday() {
        // due date three days out — previousBusinessDay is NOT today
        LocalDate dueFarOut = TODAY.plusDays(3);
        Obligation o = obligation(user("a@a.com"), ObligationPeriod.RECURRING, null);
        when(obligationService.findAllSchedulerTargets(TODAY))
                .thenReturn(List.of(new ObligationService.SchedulerTarget(o, dueFarOut)));

        service.runDailyNotifications(TODAY);

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void skipsObligationWhenLogAlreadyExists() {
        Obligation o = obligation(user("a@a.com"), ObligationPeriod.RECURRING, null);
        when(obligationService.findAllSchedulerTargets(TODAY))
                .thenReturn(List.of(new ObligationService.SchedulerTarget(o, DUE_TOMORROW)));
        when(notificationLogRepository.findAlreadyLoggedObligationIds(anyCollection()))
                .thenReturn(Set.of(o.getId()));

        service.runDailyNotifications(TODAY);

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void sendsEmailAndRecordsSuccessWhenNotificationDue() {
        Obligation o = obligation(user("a@a.com"), ObligationPeriod.RECURRING, null);
        ObligationService.SchedulerTarget target = new ObligationService.SchedulerTarget(o, DUE_TOMORROW);
        when(obligationService.findAllSchedulerTargets(TODAY)).thenReturn(List.of(target));
        when(notificationLogRepository.findAlreadyLoggedObligationIds(anyCollection()))
                .thenReturn(Set.of());

        service.runDailyNotifications(TODAY);

        verify(emailSender, times(1)).send(anyString(), anyString(), anyString());
        verify(persistenceService, times(1)).recordSuccess(List.of(target));
        verify(persistenceService, never()).recordFailure(any());
    }

    @Test
    void groupsTwoObligationsForSameUserIntOOneSend() {
        User user = user("a@a.com");
        Obligation o1 = obligation(user, ObligationPeriod.RECURRING, null);
        Obligation o2 = obligation(user, ObligationPeriod.RECURRING, null);
        ObligationService.SchedulerTarget t1 = new ObligationService.SchedulerTarget(o1, DUE_TOMORROW);
        ObligationService.SchedulerTarget t2 = new ObligationService.SchedulerTarget(o2, DUE_TOMORROW);
        when(obligationService.findAllSchedulerTargets(TODAY)).thenReturn(List.of(t1, t2));
        when(notificationLogRepository.findAlreadyLoggedObligationIds(anyCollection()))
                .thenReturn(Set.of());

        service.runDailyNotifications(TODAY);

        verify(emailSender, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void recordsFailureAndDoesNotThrowOnMailException() {
        Obligation o = obligation(user("a@a.com"), ObligationPeriod.RECURRING, null);
        ObligationService.SchedulerTarget target = new ObligationService.SchedulerTarget(o, DUE_TOMORROW);
        when(obligationService.findAllSchedulerTargets(TODAY)).thenReturn(List.of(target));
        when(notificationLogRepository.findAlreadyLoggedObligationIds(anyCollection()))
                .thenReturn(Set.of());
        doThrow(new MailSendException("SMTP down")).when(emailSender).send(anyString(), anyString(), anyString());

        assertThatNoException().isThrownBy(() -> service.runDailyNotifications(TODAY));

        verify(persistenceService, times(1)).recordFailure(List.of(target));
        verify(persistenceService, never()).recordSuccess(any());
    }

    @Test
    void fixedTermObligationTriggersSendAndRecordsSuccess() {
        Obligation o = obligation(user("a@a.com"), ObligationPeriod.FIXED_TERM, 3);
        ObligationService.SchedulerTarget target = new ObligationService.SchedulerTarget(o, DUE_TOMORROW);
        when(obligationService.findAllSchedulerTargets(TODAY)).thenReturn(List.of(target));
        when(notificationLogRepository.findAlreadyLoggedObligationIds(anyCollection()))
                .thenReturn(Set.of());

        service.runDailyNotifications(TODAY);

        verify(emailSender, times(1)).send(anyString(), anyString(), anyString());
        verify(persistenceService, times(1)).recordSuccess(List.of(target));
    }

    @Test
    void emailSubjectContainsObligationCountAndDueDate() {
        Obligation o = obligation(user("a@a.com"), ObligationPeriod.RECURRING, null);
        when(obligationService.findAllSchedulerTargets(TODAY))
                .thenReturn(List.of(new ObligationService.SchedulerTarget(o, DUE_TOMORROW)));
        when(notificationLogRepository.findAlreadyLoggedObligationIds(anyCollection()))
                .thenReturn(Set.of());

        service.runDailyNotifications(TODAY);

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(anyString(), subjectCaptor.capture(), anyString());
        assertThat(subjectCaptor.getValue())
                .contains("1 payment(s) due")
                .contains(DUE_TOMORROW.toString());
    }

    @Test
    void doesNotResendWhenRecordSuccessThrowsAndRunRepeated() {
        Obligation o = obligation(user("a@a.com"), ObligationPeriod.RECURRING, null);
        ObligationService.SchedulerTarget target = new ObligationService.SchedulerTarget(o, DUE_TOMORROW);
        when(obligationService.findAllSchedulerTargets(TODAY)).thenReturn(List.of(target));

        // Mutable set simulating the PENDING rows written to the mock DB
        Set<UUID> loggedIds = new HashSet<>();
        doAnswer(invocation -> {
            List<ObligationService.SchedulerTarget> ts = invocation.getArgument(0);
            ts.forEach(t -> loggedIds.add(t.obligation().getId()));
            return null;
        }).when(persistenceService).recordPending(any());

        // dedup check returns whatever is in the mock DB at call time
        when(notificationLogRepository.findAlreadyLoggedObligationIds(anyCollection()))
                .thenAnswer(invocation -> Set.copyOf(loggedIds));

        // run 1: PENDING written + email sent, but SENT update fails (caught internally, recordFailure called)
        doThrow(new RuntimeException("DB down")).when(persistenceService).recordSuccess(any());
        service.runDailyNotifications(TODAY);

        // run 2: PENDING row detected by dedup check → obligation filtered out → no send
        service.runDailyNotifications(TODAY);

        verify(emailSender, times(1)).send(anyString(), anyString(), anyString());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static User user(String email) {
        User u = new User(email, "hash");
        // inject a stable UUID via reflection so getId() works in grouping keys
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(u, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return u;
    }

    private static Obligation obligation(User user, ObligationPeriod period, Integer remainingPayments) {
        Obligation o = new Obligation(user, "Rent", BigDecimal.valueOf(500),
                ObligationCategory.ESSENTIAL, period, 15,
                period == ObligationPeriod.FIXED_TERM ? LocalDate.now().plusMonths(6) : null,
                remainingPayments);
        try {
            var field = Obligation.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(o, UUID.randomUUID());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return o;
    }
}
