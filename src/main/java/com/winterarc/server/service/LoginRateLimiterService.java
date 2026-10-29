package com.winterarc.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe In-Memory Rate Limiter to protect authentication endpoints
 * against brute-force, dictionary, and credential-stuffing attacks.
 */
@Service
public class LoginRateLimiterService {
    private static final Logger logger = LoggerFactory.getLogger(LoginRateLimiterService.class);

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_SECONDS = 300L; // 5 minutes

    private static class AttemptRecord {
        int attempts;
        Instant lastAttemptTime;
        Instant lockedUntil;

        AttemptRecord() {
            this.attempts = 1;
            this.lastAttemptTime = Instant.now();
            this.lockedUntil = null;
        }
    }

    private final ConcurrentHashMap<String, AttemptRecord> ipAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AttemptRecord> emailAttempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String ipAddress, String email) {
        Instant now = Instant.now();

        if (ipAddress != null) {
            AttemptRecord ipRec = ipAttempts.get(ipAddress);
            if (ipRec != null && ipRec.lockedUntil != null && now.isBefore(ipRec.lockedUntil)) {
                return true;
            }
        }

        if (email != null) {
            String normEmail = email.trim().toLowerCase();
            AttemptRecord emailRec = emailAttempts.get(normEmail);
            if (emailRec != null && emailRec.lockedUntil != null && now.isBefore(emailRec.lockedUntil)) {
                return true;
            }
        }

        return false;
    }

    public void recordFailedAttempt(String ipAddress, String email) {
        Instant now = Instant.now();

        if (ipAddress != null && !ipAddress.isBlank()) {
            ipAttempts.compute(ipAddress, (k, v) -> {
                if (v == null || now.isAfter(v.lastAttemptTime.plusSeconds(LOCKOUT_DURATION_SECONDS))) {
                    return new AttemptRecord();
                }
                v.attempts++;
                v.lastAttemptTime = now;
                if (v.attempts >= MAX_ATTEMPTS) {
                    v.lockedUntil = now.plusSeconds(LOCKOUT_DURATION_SECONDS);
                    logger.warn("IP {} has been temporarily locked out due to {} consecutive failed logins.", ipAddress, v.attempts);
                }
                return v;
            });
        }

        if (email != null && !email.isBlank()) {
            String normEmail = email.trim().toLowerCase();
            emailAttempts.compute(normEmail, (k, v) -> {
                if (v == null || now.isAfter(v.lastAttemptTime.plusSeconds(LOCKOUT_DURATION_SECONDS))) {
                    return new AttemptRecord();
                }
                v.attempts++;
                v.lastAttemptTime = now;
                if (v.attempts >= MAX_ATTEMPTS) {
                    v.lockedUntil = now.plusSeconds(LOCKOUT_DURATION_SECONDS);
                    logger.warn("Account {} has been temporarily locked out due to {} consecutive failed logins.", normEmail, v.attempts);
                }
                return v;
            });
        }
    }

    public void recordSuccessfulAttempt(String ipAddress, String email) {
        if (ipAddress != null) {
            ipAttempts.remove(ipAddress);
        }
        if (email != null) {
            emailAttempts.remove(email.trim().toLowerCase());
        }
    }
}
