package com.winterarc.server.service;

import com.winterarc.server.model.AdminAuditLog;
import com.winterarc.server.repository.AdminAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminAuditService {
    private static final Logger logger = LoggerFactory.getLogger(AdminAuditService.class);

    private final AdminAuditLogRepository auditLogRepository;

    public AdminAuditService(AdminAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AdminAuditLog record(String adminId, String adminEmail, String action,
                                String targetUserId, String targetUserEmail,
                                String reason, String outcome, String details, String ipAddress) {
        AdminAuditLog log = new AdminAuditLog(
            adminId, adminEmail, action, targetUserId, targetUserEmail, reason, outcome, details, ipAddress
        );
        AdminAuditLog saved = auditLogRepository.save(log);
        logger.info("[AUDIT] admin='{}' action='{}' target='{}' outcome='{}' reason='{}'",
            adminEmail, action, targetUserEmail, outcome, reason);
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditLog> getAuditLogs(Pageable pageable) {
        return auditLogRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditLog> getAuditLogsByAction(String action, Pageable pageable) {
        return auditLogRepository.findByAction(action, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditLog> getAuditLogsForUser(String targetUserId, Pageable pageable) {
        return auditLogRepository.findByTargetUserIdOrderByCreatedAtDesc(targetUserId, pageable);
    }

    @Transactional(readOnly = true)
    public List<AdminAuditLog> getRecentAuditLogs() {
        return auditLogRepository.findTop10ByOrderByCreatedAtDesc();
    }
}
