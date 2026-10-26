package com.momocircle.future;

import com.momocircle.domain.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "reminder_preferences")
public class ReminderPreference extends BaseEntity {
    @Column(name = "user_id", nullable = false, length = 36) private String userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private ReminderKind kind;
    @Column(nullable = false) private boolean enabled;

    public String getUserId() { return userId; } public void setUserId(String v) { userId = v; }
    public ReminderKind getKind() { return kind; } public void setKind(ReminderKind v) { kind = v; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { enabled = v; }
}
