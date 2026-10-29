package com.winterarc.server.controller;

import com.winterarc.server.model.Announcement;
import com.winterarc.server.service.AdminAnnouncementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class HomeController {

    private final AdminAnnouncementService announcementService;

    public HomeController(AdminAnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> home() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "Winter ARC Pro — Subscription & Licensing Server");
        response.put("version", "1.0.0");
        response.put("status", "ONLINE");
        response.put("timestamp", Instant.now().toString());

        Map<String, String> publicEndpoints = new LinkedHashMap<>();
        publicEndpoints.put("plans", "/api/plans");
        publicEndpoints.put("register", "/api/auth/register");
        publicEndpoints.put("login", "/api/auth/login");
        publicEndpoints.put("announcements", "/api/announcements/active");
        publicEndpoints.put("health", "/health");
        response.put("publicEndpoints", publicEndpoints);

        Map<String, String> protectedEndpoints = new LinkedHashMap<>();
        protectedEndpoints.put("checkout", "/api/checkout/create-session");
        protectedEndpoints.put("entitlements", "/api/entitlements/license");
        response.put("authenticatedEndpoints", protectedEndpoints);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/announcements/active")
    public ResponseEntity<List<Announcement>> getActiveAnnouncements() {
        return ResponseEntity.ok(announcementService.getActiveAnnouncements());
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "timestamp", Instant.now().toString()));
    }
}
