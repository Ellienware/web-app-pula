package com.momocircle.future;

import com.momocircle.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Daily (by default): creates due reminders for users who opted in. SENDS NOTIFICATIONS ONLY. It must never create a
 * payment, contribution, transfer or purchase. One failing user never blocks the others.
 */
@Component
public class FutureReminderTask {
    private static final Logger log = LoggerFactory.getLogger(FutureReminderTask.class);

    private final FutureReminderService reminders;
    private final UserRepository users;

    public FutureReminderTask(FutureReminderService reminders, UserRepository users) {
        this.reminders = reminders; this.users = users;
    }

    @Scheduled(fixedDelayString = "${app.future.reminders-ms:86400000}", initialDelayString = "${app.future.reminders-initial-delay-ms:300000}")
    public void run() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        int total = 0;
        for (String userId : reminders.usersWithReminders()) {
            try {
                var user = users.findById(userId).orElse(null);
                if (user != null) total += reminders.runForUser(user, today);
            } catch (RuntimeException e) {
                log.warn("Future reminders failed for a user", e);
            }
        }
        if (total > 0) log.info("Sent {} PULA Future reminder(s)", total);
    }
}
