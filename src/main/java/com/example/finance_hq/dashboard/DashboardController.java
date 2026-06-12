package com.example.finance_hq.dashboard;

import com.example.finance_hq.dashboard.dto.MonthlyTrendItem;
import com.example.finance_hq.dashboard.dto.MonthlySummaryResponse;
import com.example.finance_hq.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;

import static com.example.finance_hq.util.LogMaskingUtils.maskEmail;

@Slf4j
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ResponseEntity<MonthlySummaryResponse> getSummary(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String month) {
        log.info("Started get dashboard summary as {}", maskEmail(user.getEmail()));
        YearMonth ym = (month != null) ? YearMonth.parse(month) : YearMonth.now();
        return ResponseEntity.ok(service.getMonthlySummary(user, ym));
    }

    @GetMapping("/trends")
    public ResponseEntity<List<MonthlyTrendItem>> getTrends(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "6") int months) {
        log.info("Started get dashboard trends as {}", maskEmail(user.getEmail()));
        return ResponseEntity.ok(service.getMonthlyTrend(user, months));
    }
}
