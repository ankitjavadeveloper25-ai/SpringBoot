package com.winterarc.server.controller;

import com.winterarc.server.model.PromoCode;
import com.winterarc.server.model.User;
import com.winterarc.server.repository.UserRepository;
import com.winterarc.server.service.AdminPromoCodeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/promo-codes")
public class AdminPromoCodeController {

    private final AdminPromoCodeService promoCodeService;
    private final UserRepository userRepository;

    public AdminPromoCodeController(AdminPromoCodeService promoCodeService, UserRepository userRepository) {
        this.promoCodeService = promoCodeService;
        this.userRepository = userRepository;
    }

    private User getAdmin(Authentication auth) {
        String adminId = (String) auth.getPrincipal();
        return userRepository.findById(adminId)
                .orElseThrow(() -> new IllegalStateException("Authenticated administrator not found"));
    }

    @GetMapping
    public ResponseEntity<Page<PromoCode>> getAllPromoCodes(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(promoCodeService.getAllPromoCodes(pageable));
    }

    @PostMapping
    public ResponseEntity<?> createPromoCode(
            @RequestBody AdminPromoCodeService.CreatePromoCodeRequest request,
            Authentication auth,
            HttpServletRequest servletRequest) {
        try {
            User admin = getAdmin(auth);
            PromoCode promo = promoCodeService.createPromoCode(
                    admin.getId(), admin.getEmail(), request, servletRequest.getRemoteAddr()
            );
            return ResponseEntity.ok(promo);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<?> togglePromoCode(
            @PathVariable String id,
            Authentication auth,
            HttpServletRequest servletRequest) {
        try {
            User admin = getAdmin(auth);
            PromoCode updated = promoCodeService.toggleStatus(
                    admin.getId(), admin.getEmail(), id, servletRequest.getRemoteAddr()
            );
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
