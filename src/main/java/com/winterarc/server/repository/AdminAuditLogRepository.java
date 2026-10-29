package com.winterarc.server.repository;

import com.winterarc.server.model.AdminAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, String> {
    Page<AdminAuditLog> findByAction(String action, Pageable pageable);
    Page<AdminAuditLog> findByTargetUserIdOrderByCreatedAtDesc(String targetUserId, Pageable pageable);
    List<AdminAuditLog> findTop10ByOrderByCreatedAtDesc();
    List<AdminAuditLog> findByTargetUserIdOrderByCreatedAtDesc(String targetUserId);
}
