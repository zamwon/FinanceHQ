package com.example.finance_hq.obligation;

import com.example.finance_hq.obligation.dto.CreateObligationRequest;
import com.example.finance_hq.obligation.dto.MarkObligationPaidRequest;
import com.example.finance_hq.obligation.dto.ObligationResponse;
import com.example.finance_hq.obligation.dto.UpdateObligationRequest;
import com.example.finance_hq.transaction.dto.TransactionResponse;
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
@RequestMapping("/api/obligations")
public class ObligationController {

    public static final String PAY_PATH = "/pay";

    private final ObligationService service;

    public ObligationController(ObligationService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<ObligationResponse>> list(@AuthenticationPrincipal User user) {
        log.info("Started list obligations as {}", maskEmail(user.getEmail()));
        return ResponseEntity.ok(service.findAll(user));
    }

    @PostMapping
    public ResponseEntity<ObligationResponse> create(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreateObligationRequest req) {
        log.info("Started create obligation as {}", maskEmail(user.getEmail()));
        return ResponseEntity.status(201).body(service.create(user, req));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ObligationResponse> update(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateObligationRequest req) {
        log.info("Started update obligation {} as {}", id, maskEmail(user.getEmail()));
        return ResponseEntity.ok(service.update(user, id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        log.info("Started delete obligation {} as {}", id, maskEmail(user.getEmail()));
        service.delete(user, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}" + PAY_PATH)
    public ResponseEntity<TransactionResponse> pay(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id,
            @Valid @RequestBody MarkObligationPaidRequest req) {
        log.info("Started pay obligation {} as {}", id, maskEmail(user.getEmail()));
        return ResponseEntity.status(201).body(service.markPaid(user, id, req));
    }
}
