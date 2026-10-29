package com.winterarc.server.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_audit_logs")
public class AdminAuditLog {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "admin_id", nullable = false, length = 36)
    private String adminId;

    @Column(name = "admin_email", nullable = false)
    private String adminEmail;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "target_user_id", length = 36)
    private String targetUserId;

    @Column(name = "target_user_email")
    private String targetUserEmail;

    @Column(length = 1000)
    private String reason;

    @Column(nullable = false, length = 20)
    private String outcome; // "SUCCESS", "FAILURE"

    @Column(length = 4000)
    private String details;

    @Column(length = 100)
    private String ipAddress;

    @Column(nullable = false)
    private Instant createdAt;

    public AdminAuditLog() {
        this.id = UUID.randomUUID().toString();
        this.outcome = "SUCCESS";
        this.createdAt = Instant.now();
    }

    public AdminAuditLog(String adminId, String adminEmail, String action,
                         String targetUserId, String targetUserEmail,
                         String reason, String outcome, String details, String ipAddress) {
        this();
        this.adminId = adminId;
        this.adminEmail = adminEmail;
        this.action = action;
        this.targetUserId = targetUserId;
        this.targetUserEmail = targetUserEmail;
        this.reason = reason;
        this.outcome = outcome != null ? outcome : "SUCCESS";
        this.details = details;
        this.ipAddress = ipAddress;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAdminId() { return adminId; }
    public void setAdminId(String adminId) { this.adminId = adminId; }

    public String getAdminEmail() { return adminEmail; }
    public void setAdminEmail(String adminEmail) { this.adminEmail = adminEmail; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getTargetUserId() { return targetUserId; }
    public void setTargetUserId(String targetUserId) { this.targetUserId = targetUserId; }

    public String getTargetUserEmail() { return targetUserEmail; }
    public void setTargetUserEmail(String targetUserEmail) { this.targetUserEmail = targetUserEmail; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
