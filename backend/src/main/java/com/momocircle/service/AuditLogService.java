package com.momocircle.service;

import com.momocircle.domain.AuditLog;
import com.momocircle.domain.User;
import com.momocircle.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single write path for audit entries. Deliberately swallows nothing silently
 * on the read side — callers that need audit history should go through
 * AdminService, which is authorization-gated; this service itself has no
 * built-in access control since only trusted server code calls record(...).
 */
@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public void record(User actor, String action, String targetType, String targetId, String details) {
        AuditLog log = new AuditLog();
        log.setActor(actor);
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetails(details);
        auditLogRepository.save(log);
    }
}
