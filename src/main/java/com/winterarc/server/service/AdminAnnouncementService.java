package com.winterarc.server.service;

import com.winterarc.server.model.Announcement;
import com.winterarc.server.repository.AnnouncementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AdminAnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final AdminAuditService auditService;

    public AdminAnnouncementService(AnnouncementRepository announcementRepository,
                                    AdminAuditService auditService) {
        this.announcementRepository = announcementRepository;
        this.auditService = auditService;
    }

    public record CreateAnnouncementRequest(
            String title,
            String message,
            String type, // INFO, MOTIVATION, MAINTENANCE, ALERT
            Integer durationDays
    ) {}

    @Transactional(readOnly = true)
    public List<Announcement> getActiveAnnouncements() {
        return announcementRepository.findByActiveTrueAndExpiresAtAfterOrderByCreatedAtDesc(Instant.now());
    }

    @Transactional(readOnly = true)
    public Page<Announcement> getAllAnnouncements(Pageable pageable) {
        return announcementRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional
    public Announcement createAnnouncement(String adminId, String adminEmail,
                                           CreateAnnouncementRequest req, String ipAddress) {
        if (req == null || req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("Announcement title is required.");
        }
        if (req.message() == null || req.message().isBlank()) {
            throw new IllegalArgumentException("Announcement message is required.");
        }

        int days = (req.durationDays() != null && req.durationDays() > 0) ? req.durationDays() : 14;
        Instant expiresAt = Instant.now().plusSeconds(days * 86400L);

        Announcement announcement = new Announcement(
                req.title().trim(),
                req.message().trim(),
                req.type() != null ? req.type() : "INFO",
                expiresAt
        );

        Announcement saved = announcementRepository.save(announcement);

        auditService.record(
                adminId, adminEmail, "BROADCAST_ANNOUNCEMENT",
                null, "GLOBAL_BROADCAST",
                String.format("Created broadcast: '%s' (expires in %d days)", saved.getTitle(), days),
                "SUCCESS", String.format("Type=%s, ID=%s", saved.getType(), saved.getId()), ipAddress
        );

        return saved;
    }

    @Transactional
    public Announcement toggleStatus(String adminId, String adminEmail, String id, String ipAddress) {
        Announcement announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Announcement not found: " + id));

        boolean newActive = !announcement.isActive();
        announcement.setActive(newActive);
        Announcement saved = announcementRepository.save(announcement);

        auditService.record(
                adminId, adminEmail, "TOGGLE_ANNOUNCEMENT",
                null, "GLOBAL_BROADCAST",
                String.format("Toggled broadcast '%s' to active=%b", saved.getTitle(), newActive),
                "SUCCESS", "ID=" + id, ipAddress
        );

        return saved;
    }

    @Transactional
    public void deleteAnnouncement(String adminId, String adminEmail, String id, String ipAddress) {
        Announcement announcement = announcementRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Announcement not found: " + id));

        announcementRepository.delete(announcement);

        auditService.record(
                adminId, adminEmail, "DELETE_ANNOUNCEMENT",
                null, "GLOBAL_BROADCAST",
                String.format("Deleted broadcast '%s'", announcement.getTitle()),
                "SUCCESS", "ID=" + id, ipAddress
        );
    }
}
