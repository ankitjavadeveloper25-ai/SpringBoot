package com.winterarc.server.service;

import com.winterarc.server.model.Subscription;
import com.winterarc.server.model.SubscriptionTier;
import com.winterarc.server.model.User;
import com.winterarc.server.repository.SubscriptionRepository;
import com.winterarc.server.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Controlled Bootstrap Service for Primary System Administrator.
 * Bootstraps the initial administrator if no administrator exists.
 */
@Service
public class AdminBootstrapService implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(AdminBootstrapService.class);

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PasswordEncoder passwordEncoder;

    private final boolean bootstrapEnabled;
    private final String bootstrapEmail;
    private final String bootstrapPassword;
    private final String bootstrapFullName;
    private final boolean productionMode;

    public AdminBootstrapService(
            UserRepository userRepository,
            SubscriptionRepository subscriptionRepository,
            PasswordEncoder passwordEncoder,
            @Value("${winterarc.admin.bootstrap.enabled:true}") boolean bootstrapEnabled,
            @Value("${winterarc.admin.bootstrap.email:admin@winterarc.pro}") String bootstrapEmail,
            @Value("${winterarc.admin.bootstrap.password:Admin@WinterArc2026!}") String bootstrapPassword,
            @Value("${winterarc.admin.bootstrap.full-name:System Administrator}") String bootstrapFullName,
            @Value("${winterarc.entitlement.production-mode:false}") boolean productionMode) {
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEnabled = bootstrapEnabled;
        this.bootstrapEmail = bootstrapEmail != null ? bootstrapEmail.trim().toLowerCase() : "admin@winterarc.pro";
        this.bootstrapPassword = bootstrapPassword;
        this.bootstrapFullName = bootstrapFullName;
        this.productionMode = productionMode;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!bootstrapEnabled) {
            logger.info("Admin bootstrap is disabled by configuration.");
            return;
        }

        if (productionMode && "Admin@WinterArc2026!".equals(bootstrapPassword)) {
            String error = "CRITICAL SECURITY FAILURE: Production mode is enabled, but the insecure default "
                + "bootstrap password 'Admin@WinterArc2026!' is configured! Set 'WINTERARC_ADMIN_BOOTSTRAP_PASSWORD' "
                + "environment variable before starting in production.";
            logger.error(error);
            throw new IllegalStateException(error);
        }

        long adminCount = userRepository.countByRole("ROLE_ADMIN");
        if (adminCount > 0) {
            logger.info("Administrative accounts already exist (count={}). Skipping bootstrap.", adminCount);
            return;
        }

        logger.info("No system administrator found. Initializing bootstrap administrator: {}", bootstrapEmail);

        Optional<User> existingUserOpt = userRepository.findByEmail(bootstrapEmail);
        User admin;
        if (existingUserOpt.isPresent()) {
            admin = existingUserOpt.get();
            admin.setRole("ROLE_ADMIN");
            admin.setStatus("ACTIVE");
            admin.setPasswordHash(passwordEncoder.encode(bootstrapPassword));
            admin = userRepository.save(admin);
            logger.info("Elevated existing user '{}' to ROLE_ADMIN.", bootstrapEmail);
        } else {
            admin = new User(bootstrapEmail, passwordEncoder.encode(bootstrapPassword), bootstrapFullName);
            admin.setRole("ROLE_ADMIN");
            admin.setStatus("ACTIVE");
            admin = userRepository.save(admin);

            // Grant Lifetime Pro subscription to root system admin
            Subscription sub = new Subscription(
                admin, SubscriptionTier.LIFETIME, "ACTIVE", "COMPLIMENTARY",
                Instant.now(), Instant.now().plusSeconds(100L * 365L * 86400L)
            );
            subscriptionRepository.save(sub);
            logger.info("Created new root system administrator '{}' with LIFETIME subscription.", bootstrapEmail);
        }
    }
}
