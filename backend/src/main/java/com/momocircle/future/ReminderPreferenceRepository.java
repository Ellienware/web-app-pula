package com.momocircle.future;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ReminderPreferenceRepository extends JpaRepository<ReminderPreference, String> {
    List<ReminderPreference> findByUserId(String userId);
    Optional<ReminderPreference> findByUserIdAndKind(String userId, ReminderKind kind);

    @Query("select distinct p.userId from ReminderPreference p where p.enabled = true")
    List<String> findUserIdsWithAnyEnabled();
}
