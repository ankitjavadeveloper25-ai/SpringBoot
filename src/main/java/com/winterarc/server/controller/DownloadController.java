package com.winterarc.server.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/downloads")
public class DownloadController {

    @Value("${winterarc.downloads.latest-version:1.02}")
    private String latestVersion;

    @Value("${winterarc.downloads.latest-url:/downloads/Winter-ARC-Pro-1.02.msi}")
    private String latestDownloadUrl;

    @Value("${winterarc.downloads.github-releases-url:https://github.com/ankitjavadeveloper25-ai/ProgressTracker/releases}")
    private String githubReleasesUrl;

    @GetMapping("/latest")
    public ResponseEntity<Map<String, Object>> getLatestRelease() {
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("productName", "Winter ARC Pro Desktop");
        release.put("publisher", "Ankit");
        release.put("version", latestVersion);
        release.put("releaseDate", "2026-11-03");
        release.put("installerFilename", "Winter-ARC-Pro-1.02.msi");
        release.put("fileSizeBytes", 78364672L);
        release.put("fileSizeFormatted", "74.73 MB");
        release.put("sha256", "2d1cdd0476108bfa7fd69b823ffee80dc9add3f66b33aac38ee676e62a0d528e");
        release.put("architecture", "x64");
        release.put("operatingSystem", "Windows 10 / Windows 11 (64-bit)");
        release.put("bundledJre", "Java 21 JRE Embedded (Zero installation required)");
        release.put("downloadUrl", latestDownloadUrl);
        release.put("githubReleaseUrl", githubReleasesUrl + "/tag/v" + latestVersion);
        release.put("status", "STABLE");

        release.put("systemRequirements", Map.of(
                "os", "Windows 10 (1809+) or Windows 11 64-bit",
                "processor", "1.6 GHz or faster 64-bit (x64) processor",
                "memory", "4 GB RAM (8 GB recommended)",
                "storage", "250 MB free disk space for application & local SQLite database",
                "display", "1280 x 720 minimum screen resolution with High-DPI support"
        ));

        release.put("releaseNotes", List.of(
                "Production Razorpay payment verification and server-side HMAC order checkout.",
                "Ed25519 cryptographic offline entitlement signing with 14-day grace period.",
                "High-DPI display rendering enhancements with custom theme engine.",
                "Pomodoro focus timer with distraction-free audio notifications.",
                "Technical interview placement preparation log & DSA streak tracking."
        ));

        return ResponseEntity.ok(release);
    }

    @GetMapping("/releases")
    public ResponseEntity<Map<String, Object>> getAllReleases() {
        List<Map<String, Object>> releases = List.of(
                Map.of(
                        "version", "1.0.1",
                        "releaseDate", "2026-10-29",
                        "filename", "Winter ARC Pro-1.0.1.msi",
                        "size", "72.88 MB",
                        "sha256", "96374a521f746bd238eadb0a94b6934ebe4ae92cd7ebc615dda081400cc770c2",
                        "channel", "STABLE"
                ),
                Map.of(
                        "version", "1.0.0",
                        "releaseDate", "2026-10-28",
                        "filename", "Winter ARC Pro-1.0.0.msi",
                        "size", "72.86 MB",
                        "sha256", "Verified",
                        "channel", "INITIAL_RELEASE"
                )
        );

        return ResponseEntity.ok(Map.of("releases", releases));
    }
}
