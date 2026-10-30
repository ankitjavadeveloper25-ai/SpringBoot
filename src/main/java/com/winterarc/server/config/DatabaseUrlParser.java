package com.winterarc.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Robust database URL parser specifically designed for cloud environments
 * (Render, Railway, Supabase, Neon, AWS RDS, and local PostgreSQL / H2).
 *
 * Solves:
 * 1. "Driver org.postgresql.Driver claims to not accept jdbcUrl":
 *    Extracts user:password@ credentials from URI/JDBC strings and supplies them
 *    separately via DataSource properties, while providing HikariCP with a clean,
 *    standards-compliant JDBC URL.
 * 2. "Connection to localhost:5432 refused":
 *    Detects cloud PostgreSQL configurations across Render and Railway environment
 *    variables, failing fast in production if unconfigured instead of falling back
 *    to localhost.
 */
public final class DatabaseUrlParser {

    private static final Logger log = LoggerFactory.getLogger(DatabaseUrlParser.class);
    public static final String POSTGRES_DRIVER = "org.postgresql.Driver";
    public static final String H2_DRIVER = "org.h2.Driver";

    private DatabaseUrlParser() {}

    /**
     * Parses raw database connection inputs into a sanitized DatabaseConnectionInfo.
     *
     * @param rawUrl Candidate connection string (e.g. from DATABASE_URL, DB_URL, or spring.datasource.url)
     * @param fallbackUser Candidate username from environment properties
     * @param fallbackPassword Candidate password from environment properties
     * @param pgHost Individual host variable (e.g. from PGHOST or DB_HOST)
     * @param pgPort Individual port variable (e.g. from PGPORT or DB_PORT)
     * @param pgDatabase Individual database name variable (e.g. from PGDATABASE or DB_NAME)
     * @param isProd Whether the active profile is production
     * @return Sanitized DatabaseConnectionInfo, or null if production without configuration
     */
    public static DatabaseConnectionInfo parse(
            String rawUrl,
            String fallbackUser,
            String fallbackPassword,
            String pgHost,
            String pgPort,
            String pgDatabase,
            boolean isProd
    ) {
        if (rawUrl != null && !rawUrl.isBlank()) {
            return parseRawUrl(rawUrl.trim(), fallbackUser, fallbackPassword);
        }

        // If no URL candidate, check individual host parameters (Railway or standard PG variables)
        if (pgHost != null && !pgHost.isBlank()) {
            int port = 5432;
            if (pgPort != null && !pgPort.isBlank()) {
                try {
                    port = Integer.parseInt(pgPort.trim());
                } catch (NumberFormatException e) {
                    log.warn("Invalid database port '{}', defaulting to 5432", pgPort);
                }
            }
            String db = (pgDatabase != null && !pgDatabase.isBlank()) ? pgDatabase.trim() : "winterarcdb";
            String cleanJdbcUrl = "jdbc:postgresql://" + pgHost.trim() + ":" + port + "/" + db;
            return new DatabaseConnectionInfo(
                    cleanJdbcUrl,
                    fallbackUser,
                    fallbackPassword,
                    POSTGRES_DRIVER,
                    pgHost.trim(),
                    port,
                    db
            );
        }

        // In production, refuse unconfigured fallback
        if (isProd) {
            return null;
        }

        // In non-production (development/test), fallback to in-memory H2 database
        return new DatabaseConnectionInfo(
                "jdbc:h2:mem:winterarcdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                (fallbackUser != null && !fallbackUser.isBlank()) ? fallbackUser : "sa",
                (fallbackPassword != null) ? fallbackPassword : "",
                H2_DRIVER,
                "localhost",
                0,
                "winterarcdb"
        );
    }

    private static DatabaseConnectionInfo parseRawUrl(String rawUrl, String fallbackUser, String fallbackPassword) {
        String sanitized = rawUrl.trim().replaceAll("^\"|\"$", "");

        if (sanitized.startsWith("jdbc:h2:")) {
            return new DatabaseConnectionInfo(
                    sanitized,
                    (fallbackUser != null && !fallbackUser.isBlank()) ? fallbackUser : "sa",
                    (fallbackPassword != null) ? fallbackPassword : "",
                    H2_DRIVER,
                    "localhost",
                    0,
                    "winterarcdb"
            );
        }

        // Clean out jdbc: prefix if present for uniform parsing
        boolean hadJdbcPrefix = sanitized.startsWith("jdbc:");
        String withoutJdbc = hadJdbcPrefix ? sanitized.substring(5) : sanitized;

        // Check if PostgreSQL scheme or contains embedded credentials
        boolean isPostgresScheme = withoutJdbc.startsWith("postgres://") ||
                                   withoutJdbc.startsWith("postgresql://") ||
                                   withoutJdbc.startsWith("//");

        if (isPostgresScheme || withoutJdbc.contains("@")) {
            return parsePostgresAuthority(withoutJdbc, fallbackUser, fallbackPassword);
        }

        // If it's already a clean JDBC URL like jdbc:postgresql://host:port/db
        if (hadJdbcPrefix && sanitized.startsWith("jdbc:postgresql://")) {
            return parseCleanPostgresJdbcUrl(sanitized, fallbackUser, fallbackPassword);
        }

        // Handle case where user provided "host:port/db" without any scheme
        if (!sanitized.contains("://")) {
            return parsePostgresAuthority("postgresql://" + sanitized, fallbackUser, fallbackPassword);
        }

        // Generic fallback JDBC URL
        return new DatabaseConnectionInfo(
                sanitized,
                fallbackUser,
                fallbackPassword,
                POSTGRES_DRIVER,
                "remote-host",
                5432,
                "winterarcdb"
        );
    }

    private static DatabaseConnectionInfo parsePostgresAuthority(String uriString, String fallbackUser, String fallbackPassword) {
        String remaining = uriString;
        if (remaining.startsWith("postgres://")) {
            remaining = remaining.substring("postgres://".length());
        } else if (remaining.startsWith("postgresql://")) {
            remaining = remaining.substring("postgresql://".length());
        } else if (remaining.startsWith("//")) {
            remaining = remaining.substring(2);
        }

        String authorityPart;
        String pathAndQuery = "";
        int slashIdx = remaining.indexOf('/');
        if (slashIdx >= 0) {
            authorityPart = remaining.substring(0, slashIdx);
            pathAndQuery = remaining.substring(slashIdx + 1);
        } else {
            int queryIdx = remaining.indexOf('?');
            if (queryIdx >= 0) {
                authorityPart = remaining.substring(0, queryIdx);
                pathAndQuery = remaining.substring(queryIdx);
            } else {
                authorityPart = remaining;
            }
        }

        String extractedUser = null;
        String extractedPass = null;
        String hostPortPart;

        int atIdx = authorityPart.lastIndexOf('@');
        if (atIdx >= 0) {
            String userInfo = authorityPart.substring(0, atIdx);
            hostPortPart = authorityPart.substring(atIdx + 1);

            int colonIdx = userInfo.indexOf(':');
            if (colonIdx >= 0) {
                extractedUser = decode(userInfo.substring(0, colonIdx));
                extractedPass = decode(userInfo.substring(colonIdx + 1));
            } else {
                extractedUser = decode(userInfo);
            }
        } else {
            hostPortPart = authorityPart;
        }

        String host;
        int port = 5432;
        int colonIdx = hostPortPart.lastIndexOf(':');
        if (colonIdx >= 0) {
            host = hostPortPart.substring(0, colonIdx).trim();
            try {
                port = Integer.parseInt(hostPortPart.substring(colonIdx + 1).trim());
            } catch (NumberFormatException e) {
                log.warn("Could not parse port from '{}', defaulting to 5432", hostPortPart);
                port = 5432;
            }
        } else {
            host = hostPortPart.trim();
        }

        if (pathAndQuery.startsWith("/")) {
            pathAndQuery = pathAndQuery.substring(1);
        }
        if (pathAndQuery.isBlank()) {
            pathAndQuery = "winterarcdb";
        }

        String dbName;
        int qIdx = pathAndQuery.indexOf('?');
        if (qIdx >= 0) {
            dbName = pathAndQuery.substring(0, qIdx);
        } else {
            dbName = pathAndQuery;
        }

        String cleanJdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + pathAndQuery;
        String finalUser = (extractedUser != null && !extractedUser.isBlank()) ? extractedUser : fallbackUser;
        String finalPass = (extractedPass != null && !extractedPass.isBlank()) ? extractedPass : fallbackPassword;

        return new DatabaseConnectionInfo(
                cleanJdbcUrl,
                finalUser,
                finalPass,
                POSTGRES_DRIVER,
                host,
                port,
                dbName
        );
    }

    private static DatabaseConnectionInfo parseCleanPostgresJdbcUrl(String jdbcUrl, String fallbackUser, String fallbackPassword) {
        String stripped = jdbcUrl.substring("jdbc:postgresql://".length());
        int slashIdx = stripped.indexOf('/');
        String hostPort = slashIdx >= 0 ? stripped.substring(0, slashIdx) : stripped;
        String pathAndQuery = slashIdx >= 0 ? stripped.substring(slashIdx + 1) : "winterarcdb";

        String host = hostPort;
        int port = 5432;
        int colonIdx = hostPort.lastIndexOf(':');
        if (colonIdx >= 0) {
            host = hostPort.substring(0, colonIdx).trim();
            try {
                port = Integer.parseInt(hostPort.substring(colonIdx + 1).trim());
            } catch (NumberFormatException ignored) {}
        }

        String dbName = pathAndQuery;
        int qIdx = pathAndQuery.indexOf('?');
        if (qIdx >= 0) {
            dbName = pathAndQuery.substring(0, qIdx);
        }

        return new DatabaseConnectionInfo(
                jdbcUrl,
                fallbackUser,
                fallbackPassword,
                POSTGRES_DRIVER,
                host,
                port,
                dbName
        );
    }

    private static String decode(String val) {
        if (val == null) return null;
        try {
            return URLDecoder.decode(val, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return val;
        }
    }
}
