package com.example.finance_hq.dashboard;

import com.example.finance_hq.dashboard.dto.MonthlyTrendItem;
import com.example.finance_hq.dashboard.dto.MonthlySummaryResponse;
import com.example.finance_hq.user.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;

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
        YearMonth ym = (month != null) ? YearMonth.parse(month) : YearMonth.now();
        return ResponseEntity.ok(service.getMonthlySummary(user, ym));
    }

    @GetMapping("/trends")
    public ResponseEntity<List<MonthlyTrendItem>> getTrends(
            @AuthenticationPrincipal User user,
            @RequestParam(defaultValue = "6") int months) {
        return ResponseEntity.ok(service.getMonthlyTrend(user, months));
    }
}
