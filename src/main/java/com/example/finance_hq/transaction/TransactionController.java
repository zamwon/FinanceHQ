package com.example.finance_hq.transaction;

import com.example.finance_hq.transaction.dto.CreateTransactionRequest;
import com.example.finance_hq.transaction.dto.TransactionResponse;
import com.example.finance_hq.transaction.dto.UpdateTransactionRequest;
import com.example.finance_hq.user.User;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.example.finance_hq.util.LogMaskingUtils.maskEmail;

@Slf4j
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<TransactionResponse>> list(@AuthenticationPrincipal User user) {
        log.info("Started list transactions as {}", maskEmail(user.getEmail()));
        return ResponseEntity.ok(service.findAll(user));
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateTransactionRequest req) {
        log.info("Started create transaction as {}", maskEmail(user.getEmail()));
        return ResponseEntity.status(201).body(service.create(user, req));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<TransactionResponse> update(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTransactionRequest req) {
        log.info("Started update transaction {} as {}", id, maskEmail(user.getEmail()));
        return ResponseEntity.ok(service.update(user, id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        log.info("Started delete transaction {} as {}", id, maskEmail(user.getEmail()));
        service.delete(user, id);
        return ResponseEntity.noContent().build();
    }
}
