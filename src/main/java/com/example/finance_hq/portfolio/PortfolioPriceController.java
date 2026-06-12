package com.example.finance_hq.portfolio;

import com.example.finance_hq.portfolio.dto.PriceRefreshResponse;
import com.example.finance_hq.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.example.finance_hq.util.LogMaskingUtils.maskEmail;

@Slf4j
@RestController
@RequestMapping("/api/portfolio")
public class PortfolioPriceController {

    private final PortfolioPriceService service;

    public PortfolioPriceController(PortfolioPriceService service) {
        this.service = service;
    }

    @PostMapping("/refresh-prices")
    public ResponseEntity<PriceRefreshResponse> refreshPrices(@AuthenticationPrincipal User user) {
        log.info("Started refresh prices as {}", maskEmail(user.getEmail()));
        return ResponseEntity.ok(service.refreshIfStale(user));
    }
}
