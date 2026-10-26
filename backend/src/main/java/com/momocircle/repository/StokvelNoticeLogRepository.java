package com.momocircle.repository;

import com.momocircle.domain.StokvelNoticeLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StokvelNoticeLogRepository extends JpaRepository<StokvelNoticeLog, String> {
    boolean existsByCircleIdAndUserIdAndPeriodIndexAndKind(String circleId, String userId, int periodIndex, String kind);
}
