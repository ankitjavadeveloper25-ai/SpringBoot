package com.winterarc.server.controller;

import com.winterarc.server.model.User;
import com.winterarc.server.repository.UserRepository;
import com.winterarc.server.service.AdminUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService userService;
    private final UserRepository userRepository;

    public AdminUserController(AdminUserService userService, UserRepository userRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
    }

    private User getAdmin(Authentication auth) {
        String adminId = (String) auth.getPrincipal();
        return userRepository.findById(adminId)
                .orElseThrow(() -> new IllegalStateException("Authenticated administrator not found"));
    }

    @GetMapping
    public ResponseEntity<?> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC") String direction) {

        Sort.Direction dir = "ASC".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(dir, sortBy));
        Page<User> users = userService.searchUsers(query, role, status, pageable);
        return ResponseEntity.ok(users);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<?> getUserDetails(@PathVariable String userId) {
        try {
            return ResponseEntity.ok(userService.getUserDetails(userId));
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{userId}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable String userId,
            @RequestBody AdminUserService.StatusUpdateRequest request,
            Authentication auth,
            HttpServletRequest servletRequest) {
        try {
            User admin = getAdmin(auth);
            User updated = userService.updateUserStatus(
                    admin.getId(), admin.getEmail(), userId, request, servletRequest.getRemoteAddr()
            );
            return ResponseEntity.ok(Map.of(
                    "message", "User status updated successfully",
                    "userId", updated.getId(),
                    "status", updated.getStatus()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{userId}/role")
    public ResponseEntity<?> updateRole(
            @PathVariable String userId,
            @RequestBody AdminUserService.RoleUpdateRequest request,
            Authentication auth,
            HttpServletRequest servletRequest) {
        try {
            User admin = getAdmin(auth);
            User updated = userService.updateUserRole(
                    admin.getId(), admin.getEmail(), userId, request, servletRequest.getRemoteAddr()
            );
            return ResponseEntity.ok(Map.of(
                    "message", "User role updated successfully",
                    "userId", updated.getId(),
                    "role", updated.getRole()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/profile/change-password")
    public ResponseEntity<?> changePassword(
            @RequestBody AdminUserService.ChangePasswordRequest request,
            Authentication auth,
            HttpServletRequest servletRequest) {
        try {
            User admin = getAdmin(auth);
            userService.changeAdminPassword(admin.getId(), admin.getEmail(), request, servletRequest.getRemoteAddr());
            return ResponseEntity.ok(Map.of("message", "Administrator password successfully updated."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportUsersCsv() {
        List<User> allUsers = userRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        StringBuilder sb = new StringBuilder();
        sb.append("User ID,Email,Full Name,Role,Status,Created At\n");
        for (User u : allUsers) {
            sb.append(escapeCsv(u.getId())).append(",")
              .append(escapeCsv(u.getEmail())).append(",")
              .append(escapeCsv(u.getFullName())).append(",")
              .append(escapeCsv(u.getRole())).append(",")
              .append(escapeCsv(u.getStatus())).append(",")
              .append(escapeCsv(u.getCreatedAt().toString())).append("\n");
        }
        byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"winterarc-users.csv\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    private String escapeCsv(String input) {
        if (input == null) return "\"\"";
        String val = input;
        // Spreadsheet formula injection mitigation
        if (val.startsWith("=") || val.startsWith("+") || val.startsWith("-") || val.startsWith("@") || val.startsWith("\t") || val.startsWith("\r")) {
            val = "'" + val;
        }
        return "\"" + val.replace("\"", "\"\"") + "\"";
    }
}
