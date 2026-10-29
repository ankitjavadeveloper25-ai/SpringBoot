package com.winterarc.server.controller;

import com.winterarc.server.model.AdminAuditLog;
import com.winterarc.server.service.AdminAuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/audit-logs")
public class AdminAuditController {

    private final AdminAuditService auditService;

    public AdminAuditController(AdminAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<?> getAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String action) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AdminAuditLog> logs;
        if (action != null && !action.isBlank() && !"ALL".equalsIgnoreCase(action)) {
            logs = auditService.getAuditLogsByAction(action.toUpperCase(), pageable);
        } else {
            logs = auditService.getAuditLogs(pageable);
        }
        return ResponseEntity.ok(logs);
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<?> getUserAuditLogs(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(auditService.getAuditLogsForUser(userId, pageable));
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportAuditLogsCsv() {
        Page<AdminAuditLog> logs = auditService.getAuditLogs(PageRequest.of(0, 10000, Sort.by(Sort.Direction.DESC, "createdAt")));
        StringBuilder sb = new StringBuilder();
        sb.append("Log ID,Action,Admin Email,Target Account,Reason,Outcome,Details,IP Address,Timestamp\n");
        for (AdminAuditLog l : logs.getContent()) {
            sb.append(escapeCsv(l.getId())).append(",")
              .append(escapeCsv(l.getAction())).append(",")
              .append(escapeCsv(l.getAdminEmail())).append(",")
              .append(escapeCsv(l.getTargetUserEmail())).append(",")
              .append(escapeCsv(l.getReason())).append(",")
              .append(escapeCsv(l.getOutcome())).append(",")
              .append(escapeCsv(l.getDetails())).append(",")
              .append(escapeCsv(l.getIpAddress())).append(",")
              .append(escapeCsv(l.getCreatedAt().toString())).append("\n");
        }
        byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"winterarc-admin-audit-logs.csv\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    private String escapeCsv(String input) {
        if (input == null) return "\"\"";
        String val = input;
        if (val.startsWith("=") || val.startsWith("+") || val.startsWith("-") || val.startsWith("@") || val.startsWith("\t") || val.startsWith("\r")) {
            val = "'" + val;
        }
        return "\"" + val.replace("\"", "\"\"") + "\"";
    }
}
