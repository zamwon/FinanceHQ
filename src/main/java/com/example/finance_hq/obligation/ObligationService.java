package com.example.finance_hq.obligation;

import com.example.finance_hq.obligation.dto.CreateObligationRequest;
import com.example.finance_hq.obligation.dto.MarkObligationPaidRequest;
import com.example.finance_hq.obligation.dto.ObligationResponse;
import com.example.finance_hq.obligation.dto.UpdateObligationRequest;
import com.example.finance_hq.transaction.TransactionService;
import com.example.finance_hq.transaction.TransactionType;
import com.example.finance_hq.transaction.dto.CreateTransactionRequest;
import com.example.finance_hq.transaction.dto.TransactionResponse;
import com.example.finance_hq.user.User;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class ObligationService {

    private final ObligationRepository repository;
    private final TransactionService transactionService;

    public ObligationService(ObligationRepository repository, @Lazy TransactionService transactionService) {
        this.repository = repository;
        this.transactionService = transactionService;
    }

    @Transactional(readOnly = true)
    public List<ObligationResponse> findAll(User user) {
        LocalDate today = LocalDate.now();
        PageRequest page = PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "createdAt"));
        return repository.findAllByUser(user, page).getContent().stream()
                .map(o -> ObligationResponse.from(o, NextDueDateComputer.compute(o.getPaymentDay(), today, o.getPeriod(), o.getEndDate())))
                .toList();
    }

    @Transactional
    public ObligationResponse create(User user, CreateObligationRequest req) {
        if (req.period() == ObligationPeriod.FIXED_TERM) {
            if (req.endDate() == null || req.remainingPayments() == null) {
                throw new InvalidObligationException("FIXED_TERM obligations require both endDate and remainingPayments");
            }
            if (!req.endDate().isAfter(LocalDate.now())) {
                throw new InvalidObligationException("FIXED_TERM endDate must be in the future");
            }
        }
        Obligation obligation = new Obligation(
                user, req.name(), req.amount(), req.category(),
                req.period(), req.paymentDay(), req.endDate(), req.remainingPayments()
        );
        Obligation saved = repository.save(obligation);
        return ObligationResponse.from(saved, nextDueDate(saved));
    }

    @Transactional
    public ObligationResponse update(User user, UUID id, UpdateObligationRequest req) {
        if (req.amount() == null && req.paymentDay() == null && req.category() == null) {
            throw new InvalidObligationException("At least one field must be provided for update");
        }
        Obligation obligation = repository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ObligationNotFoundException("Obligation not found"));
        if (req.amount() != null) obligation.setAmount(req.amount());
        if (req.paymentDay() != null) obligation.setPaymentDay(req.paymentDay());
        if (req.category() != null) obligation.setCategory(req.category());
        Obligation saved = repository.save(obligation);
        return ObligationResponse.from(saved, nextDueDate(saved));
    }

    @Transactional
    public void delete(User user, UUID id) {
        Obligation obligation = repository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ObligationNotFoundException("Obligation not found"));
        repository.delete(obligation);
    }

    @Transactional
    public TransactionResponse markPaid(User user, UUID obligationId, MarkObligationPaidRequest req) {
        Obligation obligation = repository.findByIdAndUser(obligationId, user)
                .orElseThrow(() -> new ObligationNotFoundException("Obligation not found"));
        CreateTransactionRequest txnReq = new CreateTransactionRequest(
                TransactionType.EXPENSE, req.category(), req.amount(), req.description(),
                null, req.date(), null, null, null, obligation.getId()
        );
        TransactionResponse response = transactionService.create(user, txnReq);
        obligation.setLastPaidDate(req.date());
        repository.save(obligation);
        return response;
    }

    public record SchedulerTarget(Obligation obligation, LocalDate nextDueDate) {}

    @Transactional(readOnly = true)
    public List<SchedulerTarget> findAllSchedulerTargets(LocalDate today) {
        return repository.findAllWithUser().stream()
                .map(o -> new SchedulerTarget(o, NextDueDateComputer.compute(
                        o.getPaymentDay(), today, o.getPeriod(), o.getEndDate())))
                .filter(t -> t.nextDueDate() != null)
                .toList();
    }

    private LocalDate nextDueDate(Obligation o) {
        return NextDueDateComputer.compute(o.getPaymentDay(), LocalDate.now(), o.getPeriod(), o.getEndDate());
    }
}
