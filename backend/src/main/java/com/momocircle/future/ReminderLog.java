package com.momocircle.future;

import com.momocircle.domain.BaseEntity;
import jakarta.persistence.*;

/** Record that a reminder was sent; (user, key) is unique so the same reminder is never sent twice. */
@Entity
@Table(name = "reminder_log")
public class ReminderLog extends BaseEntity {
    @Column(name = "user_id", nullable = false, length = 36) private String userId;
    @Column(name = "reminder_key", nullable = false, length = 160) private String reminderKey;

    public String getUserId() { return userId; } public void setUserId(String v) { userId = v; }
    public String getReminderKey() { return reminderKey; } public void setReminderKey(String v) { reminderKey = v; }
}
