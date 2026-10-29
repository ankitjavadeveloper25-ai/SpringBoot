package com.winterarc.server.controller;

import com.winterarc.server.model.Announcement;
import com.winterarc.server.model.User;
import com.winterarc.server.repository.UserRepository;
import com.winterarc.server.service.AdminAnnouncementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/announcements")
public class AdminAnnouncementController {

    private final AdminAnnouncementService announcementService;
    private final UserRepository userRepository;

    public AdminAnnouncementController(AdminAnnouncementService announcementService, UserRepository userRepository) {
        this.announcementService = announcementService;
        this.userRepository = userRepository;
    }

    private User getAdmin(Authentication auth) {
        String adminId = (String) auth.getPrincipal();
        return userRepository.findById(adminId)
                .orElseThrow(() -> new IllegalStateException("Authenticated administrator not found"));
    }

    @GetMapping
    public ResponseEntity<Page<Announcement>> getAllAnnouncements(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(announcementService.getAllAnnouncements(pageable));
    }

    @PostMapping
    public ResponseEntity<?> createAnnouncement(
            @RequestBody AdminAnnouncementService.CreateAnnouncementRequest request,
            Authentication auth,
            HttpServletRequest servletRequest) {
        try {
            User admin = getAdmin(auth);
            Announcement announcement = announcementService.createAnnouncement(
                    admin.getId(), admin.getEmail(), request, servletRequest.getRemoteAddr()
            );
            return ResponseEntity.ok(announcement);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<?> toggleAnnouncement(
            @PathVariable String id,
            Authentication auth,
            HttpServletRequest servletRequest) {
        try {
            User admin = getAdmin(auth);
            Announcement updated = announcementService.toggleStatus(
                    admin.getId(), admin.getEmail(), id, servletRequest.getRemoteAddr()
            );
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteAnnouncement(
            @PathVariable String id,
            Authentication auth,
            HttpServletRequest servletRequest) {
        try {
            User admin = getAdmin(auth);
            announcementService.deleteAnnouncement(admin.getId(), admin.getEmail(), id, servletRequest.getRemoteAddr());
            return ResponseEntity.ok(Map.of("message", "Announcement deleted successfully"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
