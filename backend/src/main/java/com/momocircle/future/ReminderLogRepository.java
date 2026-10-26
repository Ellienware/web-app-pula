package com.momocircle.future;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReminderLogRepository extends JpaRepository<ReminderLog, String> {
    boolean existsByUserIdAndReminderKey(String userId, String reminderKey);
}
