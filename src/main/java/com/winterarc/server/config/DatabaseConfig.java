package com.winterarc.server.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Bean
    @Primary
    public DataSource dataSource(Environment env) {
        HikariConfig config = createHikariConfig(env);
        return new HikariDataSource(config);
    }

    public HikariConfig createHikariConfig(Environment env) {
        boolean isProd = isProductionProfile(env);
        log.info("Configuring Winter ARC Pro DataSource (isProd={})", isProd);

        // 1. Resolve raw URL candidate from environment or configuration properties
        String rawUrl = resolveDatabaseUrl(env);

        // 2. Resolve fallback credentials from various standard keys
        String fallbackUser = resolveProperty(env, "DB_USERNAME", "DATABASE_USERNAME", "SPRING_DATASOURCE_USERNAME", "PGUSER", "spring.datasource.username");
        String fallbackPassword = resolveProperty(env, "DB_PASSWORD", "DATABASE_PASSWORD", "SPRING_DATASOURCE_PASSWORD", "PGPASSWORD", "spring.datasource.password");

        // 3. Resolve individual host variables
        String pgHost = resolveProperty(env, "PGHOST", "DB_HOST");
        String pgPort = resolveProperty(env, "PGPORT", "DB_PORT");
        String pgDatabase = resolveProperty(env, "PGDATABASE", "DB_NAME");

        // 4. Parse connection details
        DatabaseConnectionInfo connInfo = DatabaseUrlParser.parse(
                rawUrl,
                fallbackUser,
                fallbackPassword,
                pgHost,
                pgPort,
                pgDatabase,
                isProd
        );

        // 5. Fail-fast safety checks in production
        if (connInfo == null) {
            String errorMsg = """
                
                ================================================================================
                FATAL: Production Database Configuration Missing on Render / Cloud!
                ================================================================================
                The application was started with production profile (SPRING_PROFILES_ACTIVE=prod),
                but no valid PostgreSQL database connection configuration could be found.
                
                Render Configuration Steps:
                1. Open your Render Dashboard -> select your Web Service 'winterarc-backend'.
                2. Go to the 'Environment' tab.
                3. Either:
                   Option A (Recommended): Add 'DATABASE_URL' and set it to the Internal Database URL
                                           from your Render PostgreSQL instance:
                                           postgres://<user>:<password>@<host>/<database>
                   Option B: Set explicit variables:
                             DB_URL      = jdbc:postgresql://<host>:5432/<database>
                             DB_USERNAME = <user>
                             DB_PASSWORD = <password>
                
                Fallback to localhost or in-memory H2 is strictly disabled in production mode.
                ================================================================================
                """;
            log.error(errorMsg);
            throw new IllegalStateException("Production database configuration missing: Set DATABASE_URL or DB_URL / DB_USERNAME / DB_PASSWORD on Render.");
        }

        if (isProd) {
            if (connInfo.isH2()) {
                throw new IllegalStateException("H2 database cannot be used in production. Configure Render PostgreSQL.");
            }

            boolean allowLocalhost = "true".equalsIgnoreCase(env.getProperty("ALLOW_PROD_LOCALHOST"));
            if (connInfo.isLocalhost() && !allowLocalhost) {
                String errorMsg = String.format("""
                    
                    ================================================================================
                    FATAL: Unsafe Localhost Database Connection Rejected in Production!
                    ================================================================================
                    The database host resolved to '%s' (localhost) while running under 'prod' profile.
                    
                    Why this happens on Render:
                    Render web services run in isolated Docker containers with no local PostgreSQL
                    listening on port 5432. Falling back to localhost causes:
                    'Connection to localhost:5432 refused'.
                    
                    How to fix on Render:
                    In your Render Dashboard -> Web Service -> Environment:
                    Ensure 'DATABASE_URL' or 'DB_URL' is set to your Render PostgreSQL host (e.g. dpg-xxx-a).
                    Do not use 'localhost' in production environment variables.
                    ================================================================================
                    """, connInfo.host());
                log.error(errorMsg);
                throw new IllegalStateException("Production database resolved to localhost. Please configure your remote Render PostgreSQL database host.");
            }
        }

        // 6. Configure HikariCP
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(connInfo.jdbcUrl());
        if (connInfo.username() != null && !connInfo.username().isBlank()) {
            config.setUsername(connInfo.username());
        }
        if (connInfo.password() != null && !connInfo.password().isBlank()) {
            config.setPassword(connInfo.password());
        }
        config.setDriverClassName(connInfo.driverClassName());
        config.setPoolName(isProd ? "WinterArcProdPool" : "WinterArcDevPool");

        if (connInfo.isPostgres()) {
            config.setMaximumPoolSize(isProd ? 10 : 5);
            config.setMinimumIdle(isProd ? 2 : 1);
            config.setIdleTimeout(300000); // 5 minutes
            config.setConnectionTimeout(20000); // 20 seconds
            config.setMaxLifetime(1800000); // 30 minutes
            config.setValidationTimeout(5000); // 5 seconds
            config.setLeakDetectionThreshold(60000); // 60 seconds
        } else {
            config.setMaximumPoolSize(5);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(10000);
        }

        log.info("DataSource configuration prepared: pool={}, driver={}, url={}, host={}, port={}, db={}, user={}, pass={}",
                config.getPoolName(),
                connInfo.driverClassName(),
                connInfo.jdbcUrl(),
                connInfo.host(),
                connInfo.port(),
                connInfo.databaseName(),
                connInfo.username() != null ? connInfo.username() : "(none)",
                connInfo.maskedPassword());

        return config;
    }

    public static boolean isProductionProfile(Environment env) {
        for (String profile : env.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        String p1 = env.getProperty("spring.profiles.active");
        if (p1 != null && (p1.contains("prod") || p1.contains("production"))) {
            return true;
        }
        String p2 = env.getProperty("SPRING_PROFILES_ACTIVE");
        return p2 != null && (p2.contains("prod") || p2.contains("production"));
    }

    private static String resolveDatabaseUrl(Environment env) {
        String[] candidates = {
                "DB_URL",
                "DATABASE_URL",
                "DATABASE_PRIVATE_URL",
                "DATABASE_PUBLIC_URL",
                "SPRING_DATASOURCE_URL",
                "JDBC_DATABASE_URL",
                "spring.datasource.url"
        };
        for (String candidate : candidates) {
            String val = env.getProperty(candidate);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }
        return null;
    }

    private static String resolveProperty(Environment env, String... keys) {
        for (String key : keys) {
            String val = env.getProperty(key);
            if (val != null && !val.isBlank()) {
                return val.trim();
            }
        }
        return null;
    }
}
