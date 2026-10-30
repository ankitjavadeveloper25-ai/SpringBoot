package com.winterarc.server;

import com.winterarc.server.config.DatabaseConfig;
import com.winterarc.server.config.DatabaseConnectionInfo;
import com.winterarc.server.config.DatabaseUrlParser;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Winter ARC Pro — PostgreSQL & Cloud Database Configuration Tests")
class DatabaseConfigTest {

    @Nested
    @DisplayName("Render & Cloud URL Parsing (DatabaseUrlParser)")
    class UrlParserTests {

        @Test
        @DisplayName("Parses standard Render internal DATABASE_URL without port")
        void testRenderInternalUrlWithoutPort() {
            String renderUrl = "postgres://db_0k7d_user:N69wrjE2UnigJiS51PrJ1CTDtpDv98ro@dpg-dbhtf5ks728c73ctop1g-a/db_0k7d";

            DatabaseConnectionInfo info = DatabaseUrlParser.parse(
                    renderUrl, null, null, null, null, null, true
            );

            assertNotNull(info);
            assertEquals("jdbc:postgresql://dpg-dbhtf5ks728c73ctop1g-a:5432/db_0k7d", info.jdbcUrl());
            assertEquals("db_0k7d_user", info.username());
            assertEquals("N69wrjE2UnigJiS51PrJ1CTDtpDv98ro", info.password());
            assertEquals("org.postgresql.Driver", info.driverClassName());
            assertEquals("dpg-dbhtf5ks728c73ctop1g-a", info.host());
            assertEquals(5432, info.port());
            assertEquals("db_0k7d", info.databaseName());
            assertTrue(info.isPostgres());
            assertFalse(info.isLocalhost());
        }

        @Test
        @DisplayName("Parses Render URL with embedded credentials in jdbc:postgresql:// format")
        void testRenderUrlWithJdbcPrefixAndCredentials() {
            // This was the exact error: Driver org.postgresql.Driver claims to not accept jdbcUrl
            String embeddedUrl = "jdbc:postgresql://db_0k7d_user:N69wrjE2UnigJiS51PrJ1CTDtpDv98ro@dpg-dbhtf5ks728c73ctop1g-a/db_0k7d";

            DatabaseConnectionInfo info = DatabaseUrlParser.parse(
                    embeddedUrl, null, null, null, null, null, true
            );

            assertNotNull(info);
            // Pure JDBC URL must NOT have credentials
            assertEquals("jdbc:postgresql://dpg-dbhtf5ks728c73ctop1g-a:5432/db_0k7d", info.jdbcUrl());
            assertFalse(info.jdbcUrl().contains("@"), "JDBC URL must never contain '@'");
            assertFalse(info.jdbcUrl().contains("db_0k7d_user"), "JDBC URL must never contain username");
            assertEquals("db_0k7d_user", info.username());
            assertEquals("N69wrjE2UnigJiS51PrJ1CTDtpDv98ro", info.password());
            assertEquals("dpg-dbhtf5ks728c73ctop1g-a", info.host());
            assertEquals(5432, info.port());
        }

        @Test
        @DisplayName("Parses Render external URL with explicit port and SSL query params")
        void testRenderExternalUrlWithPortAndSsl() {
            String renderUrl = "postgres://db_user:my_secret@dpg-host-a.oregon-postgres.render.com:5432/my_db?sslmode=require";

            DatabaseConnectionInfo info = DatabaseUrlParser.parse(
                    renderUrl, null, null, null, null, null, true
            );

            assertNotNull(info);
            assertEquals("jdbc:postgresql://dpg-host-a.oregon-postgres.render.com:5432/my_db?sslmode=require", info.jdbcUrl());
            assertEquals("db_user", info.username());
            assertEquals("my_secret", info.password());
            assertEquals("dpg-host-a.oregon-postgres.render.com", info.host());
            assertEquals(5432, info.port());
            assertEquals("my_db", info.databaseName());
            assertTrue(info.isPostgres());
            assertFalse(info.isLocalhost());
        }

        @Test
        @DisplayName("Supports separate DB_URL, DB_USERNAME, and DB_PASSWORD")
        void testSeparateJdbcUrlAndCredentials() {
            String cleanJdbcUrl = "jdbc:postgresql://dpg-dbhtf5ks728c73ctop1g-a:5432/db_0k7d";
            String user = "db_0k7d_user";
            String pass = "separate_password_123";

            DatabaseConnectionInfo info = DatabaseUrlParser.parse(
                    cleanJdbcUrl, user, pass, null, null, null, true
            );

            assertNotNull(info);
            assertEquals("jdbc:postgresql://dpg-dbhtf5ks728c73ctop1g-a:5432/db_0k7d", info.jdbcUrl());
            assertEquals("db_0k7d_user", info.username());
            assertEquals("separate_password_123", info.password());
            assertEquals("dpg-dbhtf5ks728c73ctop1g-a", info.host());
            assertEquals(5432, info.port());
            assertEquals("db_0k7d", info.databaseName());
        }

        @Test
        @DisplayName("Supports Railway individual host variables (PGHOST, PGPORT, etc.)")
        void testRailwayHostVariables() {
            DatabaseConnectionInfo info = DatabaseUrlParser.parse(
                    null, "postgres", "super_secret", "roundhouse.proxy.rlwy.net", "23456", "railway", true
            );

            assertNotNull(info);
            assertEquals("jdbc:postgresql://roundhouse.proxy.rlwy.net:23456/railway", info.jdbcUrl());
            assertEquals("postgres", info.username());
            assertEquals("super_secret", info.password());
            assertEquals("roundhouse.proxy.rlwy.net", info.host());
            assertEquals(23456, info.port());
            assertEquals("railway", info.databaseName());
        }

        @Test
        @DisplayName("Decodes URL-encoded credentials correctly")
        void testUrlEncodedCredentials() {
            String encodedUrl = "postgres://my%2Buser:p%40ss%3Aword@dpg-host:5432/mydb";

            DatabaseConnectionInfo info = DatabaseUrlParser.parse(
                    encodedUrl, null, null, null, null, null, true
            );

            assertNotNull(info);
            assertEquals("my+user", info.username());
            assertEquals("p@ss:word", info.password());
            assertEquals("jdbc:postgresql://dpg-host:5432/mydb", info.jdbcUrl());
        }

        @Test
        @DisplayName("Production returns null if no configuration provided (fail fast)")
        void testProductionFailsFastWhenMissing() {
            DatabaseConnectionInfo info = DatabaseUrlParser.parse(
                    null, null, null, null, null, null, true
            );

            assertNull(info, "In production mode, unconfigured database must return null to trigger fail-fast");
        }

        @Test
        @DisplayName("Non-production falls back to H2 database when unconfigured")
        void testNonProductionFallsBackToH2() {
            DatabaseConnectionInfo info = DatabaseUrlParser.parse(
                    null, null, null, null, null, null, false
            );

            assertNotNull(info);
            assertTrue(info.isH2());
            assertEquals("org.h2.Driver", info.driverClassName());
            assertTrue(info.jdbcUrl().startsWith("jdbc:h2:mem:"));
            assertEquals("sa", info.username());
        }

        @Test
        @DisplayName("Correctly identifies localhost hosts to prevent container connection refusal")
        void testLocalhostDetection() {
            DatabaseConnectionInfo localInfo = DatabaseUrlParser.parse(
                    "jdbc:postgresql://localhost:5432/winterarcdb", "user", "pass", null, null, null, false
            );
            assertNotNull(localInfo);
            assertTrue(localInfo.isLocalhost());

            DatabaseConnectionInfo loopbackInfo = DatabaseUrlParser.parse(
                    "jdbc:postgresql://127.0.0.1:5432/winterarcdb", "user", "pass", null, null, null, false
            );
            assertNotNull(loopbackInfo);
            assertTrue(loopbackInfo.isLocalhost());

            DatabaseConnectionInfo remoteInfo = DatabaseUrlParser.parse(
                    "jdbc:postgresql://dpg-dbhtf5ks728c73ctop1g-a:5432/winterarcdb", "user", "pass", null, null, null, false
            );
            assertNotNull(remoteInfo);
            assertFalse(remoteInfo.isLocalhost());
        }
    }

    @Nested
    @DisplayName("DatabaseConfig Spring Bean Life-cycle & Profile Tests")
    class DatabaseConfigBeanTests {

        private final DatabaseConfig databaseConfig = new DatabaseConfig();

        @Test
        @DisplayName("Fails fast in 'prod' profile if no configuration is provided")
        void testProdProfileThrowsWhenNoDatabaseProvided() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("prod");

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
                databaseConfig.createHikariConfig(env);
            });

            assertTrue(ex.getMessage().contains("Production database configuration missing"));
        }

        @Test
        @DisplayName("Fails fast in 'prod' profile if database host resolves to localhost")
        void testProdProfileRejectsLocalhostFallback() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("prod");
            env.setProperty("DB_URL", "jdbc:postgresql://localhost:5432/winterarcdb");
            env.setProperty("DB_USERNAME", "winterarc_admin");
            env.setProperty("DB_PASSWORD", "secret");

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
                databaseConfig.createHikariConfig(env);
            });

            assertTrue(ex.getMessage().contains("localhost"));
        }

        @Test
        @DisplayName("Allows localhost in 'prod' profile ONLY when ALLOW_PROD_LOCALHOST=true")
        void testProdProfileAllowsLocalhostWithExplicitOverride() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("prod");
            env.setProperty("DB_URL", "jdbc:postgresql://localhost:5432/winterarcdb");
            env.setProperty("DB_USERNAME", "winterarc_admin");
            env.setProperty("DB_PASSWORD", "secret");
            env.setProperty("ALLOW_PROD_LOCALHOST", "true");

            HikariConfig config = databaseConfig.createHikariConfig(env);
            assertNotNull(config);
            assertEquals("jdbc:postgresql://localhost:5432/winterarcdb", config.getJdbcUrl());
            assertEquals("winterarc_admin", config.getUsername());
        }

        @Test
        @DisplayName("Configures Render DATABASE_URL successfully in 'prod' profile")
        void testProdProfileWithRenderDatabaseUrl() {
            MockEnvironment env = new MockEnvironment();
            env.setActiveProfiles("prod");
            env.setProperty("DATABASE_URL", "postgres://db_0k7d_user:N69wrjE2UnigJiS51PrJ1CTDtpDv98ro@dpg-dbhtf5ks728c73ctop1g-a/db_0k7d");

            HikariConfig config = databaseConfig.createHikariConfig(env);
            assertNotNull(config);
            assertEquals("jdbc:postgresql://dpg-dbhtf5ks728c73ctop1g-a:5432/db_0k7d", config.getJdbcUrl());
            assertEquals("db_0k7d_user", config.getUsername());
            assertEquals("org.postgresql.Driver", config.getDriverClassName());
            assertEquals(10, config.getMaximumPoolSize());
            assertEquals(2, config.getMinimumIdle());
        }

        @Test
        @DisplayName("Default profile gracefully creates H2 in-memory DataSource")
        void testDefaultProfileCreatesH2DataSource() {
            MockEnvironment env = new MockEnvironment();
            // No profile specified -> defaults to dev/test with H2

            HikariDataSource ds = (HikariDataSource) databaseConfig.dataSource(env);
            assertNotNull(ds);
            assertTrue(ds.getJdbcUrl().startsWith("jdbc:h2:mem:winterarcdb"));
            assertEquals("org.h2.Driver", ds.getDriverClassName());
            assertEquals("sa", ds.getUsername());
            ds.close();
        }
    }
}
