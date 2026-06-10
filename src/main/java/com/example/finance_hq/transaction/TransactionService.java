package com.example.finance_hq.transaction;

import com.example.finance_hq.obligation.Obligation;
import com.example.finance_hq.obligation.NextDueDateComputer;
import com.example.finance_hq.obligation.ObligationNotFoundException;
import com.example.finance_hq.obligation.ObligationPeriod;
import com.example.finance_hq.obligation.ObligationRepository;
import com.example.finance_hq.transaction.dto.CreateTransactionRequest;
import com.example.finance_hq.transaction.dto.TransactionResponse;
import com.example.finance_hq.transaction.dto.UpdateTransactionRequest;
import com.example.finance_hq.user.User;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class TransactionService {

    private final TransactionRepository repository;
    private final ObligationRepository obligationRepository;

    public TransactionService(TransactionRepository repository, ObligationRepository obligationRepository) {
        this.repository = repository;
        this.obligationRepository = obligationRepository;
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> findAll(User user) {
        PageRequest page = PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "createdAt"));
        LocalDate today = LocalDate.now();
        return repository.findAllByUser(user, page).getContent().stream()
                .map(t -> TransactionResponse.from(t, computeNextExpectedDate(t, today)))
                .toList();
    }

    @Transactional
    public TransactionResponse create(User user, CreateTransactionRequest req) {
        validateCategory(req.type(), req.category());
        if (req.period() == null && req.date() == null) {
            throw new InvalidTransactionException("ONE_OFF transactions require a date");
        }
        if (req.period() != null && req.paymentDay() == null) {
            throw new InvalidTransactionException("Recurring/fixed-term transactions require a paymentDay");
        }
        if (req.period() == ObligationPeriod.FIXED_TERM) {
            if (req.endDate() == null || req.remainingPayments() == null) {
                throw new InvalidTransactionException("FIXED_TERM transactions require both endDate and remainingPayments");
            }
            if (!req.endDate().isAfter(LocalDate.now())) {
                throw new InvalidTransactionException("FIXED_TERM endDate must be in the future");
            }
        }

        Obligation obligation = null;
        if (req.obligationId() != null) {
            obligation = obligationRepository.findByIdAndUser(req.obligationId(), user)
                    .orElseThrow(() -> new ObligationNotFoundException("Obligation not found"));
        }

        Transaction transaction = new Transaction(
                user, obligation, req.type(), req.category(),
                req.amount(), req.description(), req.period(),
                req.date(), req.paymentDay(), req.endDate(), req.remainingPayments()
        );
        Transaction saved = repository.save(transaction);
        return TransactionResponse.from(saved, computeNextExpectedDate(saved, LocalDate.now()));
    }

    @Transactional
    public TransactionResponse update(User user, UUID id, UpdateTransactionRequest req) {
        if (req.type() == null && req.category() == null && req.amount() == null
                && req.description() == null && req.period() == null && req.date() == null
                && req.paymentDay() == null && req.endDate() == null && req.remainingPayments() == null) {
            throw new InvalidTransactionException("At least one field must be provided for update");
        }
        Transaction transaction = repository.findByIdAndUser(id, user)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found"));

        if (req.type() != null) transaction.setType(req.type());
        if (req.category() != null) transaction.setCategory(req.category());
        if (req.amount() != null) transaction.setAmount(req.amount());
        if (req.description() != null) transaction.setDescription(req.description());
        if (req.period() != null) transaction.setPeriod(req.period());
        if (req.date() != null) transaction.setDate(req.date());
        if (req.paymentDay() != null) transaction.setPaymentDay(req.paymentDay());
        if (req.endDate() != null) transaction.setEndDate(req.endDate());
        if (req.remainingPayments() != null) transaction.setRemainingPayments(req.remainingPayments());

        validateCategory(transaction.getType(), transaction.getCategory());

        Transaction saved = repository.save(transaction);
        return TransactionResponse.from(saved, computeNextExpectedDate(saved, LocalDate.now()));
    }

    @Transactional
    public void delete(User user, UUID id) {
        Transaction transaction = repository.findByIdAndUser(id, user)
                .orElseThrow(() -> new TransactionNotFoundException("Transaction not found"));
        repository.delete(transaction);
    }

    private LocalDate computeNextExpectedDate(Transaction t, LocalDate today) {
        if (t.getPeriod() == null || t.getPaymentDay() == null) return null;
        return NextDueDateComputer.compute(t.getPaymentDay(), today, t.getPeriod(), t.getEndDate());
    }

    private void validateCategory(TransactionType type, String category) {
        try {
            if (type == TransactionType.EXPENSE) {
                ExpenseCategory.valueOf(category);
            } else {
                IncomeCategory.valueOf(category);
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidTransactionException("Invalid category '" + category + "' for type " + type);
        }
    }
}
