package com.example.finance_hq.portfolio;

import com.example.finance_hq.portfolio.dto.CreatePortfolioAssetRequest;
import com.example.finance_hq.portfolio.dto.PortfolioAssetResponse;
import com.example.finance_hq.portfolio.dto.UpdatePortfolioAssetRequest;
import com.example.finance_hq.user.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(PortfolioAssetController.BASE_PATH)
public class PortfolioAssetController {

    static final String BASE_PATH = "/api/portfolio";

    private final PortfolioAssetService service;

    public PortfolioAssetController(PortfolioAssetService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<PortfolioAssetResponse>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.findAll(user));
    }

    @PostMapping
    public ResponseEntity<PortfolioAssetResponse> create(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CreatePortfolioAssetRequest req) {
        return ResponseEntity.status(201).body(service.create(user, req));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<PortfolioAssetResponse> update(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePortfolioAssetRequest req) {
        return ResponseEntity.ok(service.update(user, id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id) {
        service.delete(user, id);
        return ResponseEntity.noContent().build();
    }
}
