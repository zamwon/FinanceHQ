package com.example.finance_hq.notification;

import com.example.finance_hq.obligation.Obligation;
import com.example.finance_hq.obligation.ObligationCategory;
import com.example.finance_hq.obligation.ObligationPeriod;
import com.example.finance_hq.obligation.ObligationRepository;
import com.example.finance_hq.obligation.ObligationService;
import com.example.finance_hq.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationPersistenceServiceTest {

    @Mock NotificationLogRepository notificationLogRepository;
    @Mock ObligationRepository obligationRepository;

    NotificationPersistenceService service;

    static final LocalDate DUE = LocalDate.of(2025, 6, 3);

    @BeforeEach
    void setUp() {
        service = new NotificationPersistenceService(notificationLogRepository, obligationRepository);
    }

    @Test
    void recordSuccess_decrementsRemainingPaymentsForFixedTerm() {
        User user = new User("a@a.com", "hash");
        Obligation obligation = obligation(user, ObligationPeriod.FIXED_TERM, 3);
        ObligationService.SchedulerTarget target = new ObligationService.SchedulerTarget(obligation, DUE);

        service.recordSuccess(List.of(target));

        ArgumentCaptor<Obligation> captor = ArgumentCaptor.forClass(Obligation.class);
        verify(obligationRepository).save(captor.capture());
        assertThat(captor.getValue().getRemainingPayments()).isEqualTo(2);
    }

    @Test
    void recordSuccess_doesNotDecrementForRecurring() {
        User user = new User("a@a.com", "hash");
        Obligation obligation = obligation(user, ObligationPeriod.RECURRING, null);
        ObligationService.SchedulerTarget target = new ObligationService.SchedulerTarget(obligation, DUE);

        service.recordSuccess(List.of(target));

        verify(obligationRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
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
