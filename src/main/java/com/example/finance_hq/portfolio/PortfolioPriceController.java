package com.example.finance_hq.portfolio;

import com.example.finance_hq.portfolio.dto.PriceRefreshResponse;
import com.example.finance_hq.user.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioPriceController {

    private final PortfolioPriceService service;

    public PortfolioPriceController(PortfolioPriceService service) {
        this.service = service;
    }

    @PostMapping("/refresh-prices")
    public ResponseEntity<PriceRefreshResponse> refreshPrices(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(service.refreshIfStale(user));
    }
}
