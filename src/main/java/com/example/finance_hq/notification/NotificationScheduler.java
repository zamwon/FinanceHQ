package com.example.finance_hq.notification;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class NotificationScheduler {

    private final NotificationService notificationService;

    public NotificationScheduler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "Europe/Warsaw")
    @Scheduled(cron = "0 40 12 * * *", zone = "Europe/Warsaw") // TEMP: manual verification run, remove after confirming
    public void runDailyNotifications() {
        notificationService.runDailyNotifications(LocalDate.now(ZoneId.of("Europe/Warsaw")));
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void retryFailedNotifications() {
        notificationService.retryFailedNotifications();
    }
}
