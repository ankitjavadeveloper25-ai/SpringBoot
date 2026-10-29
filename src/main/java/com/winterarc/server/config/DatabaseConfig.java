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
import java.net.URI;

@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    @Bean
    @Primary
    public DataSource dataSource(Environment env) {
        String databaseUrl = env.getProperty("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            databaseUrl = env.getProperty("DATABASE_PRIVATE_URL");
        }
        if (databaseUrl == null || databaseUrl.isBlank()) {
            databaseUrl = env.getProperty("DATABASE_PUBLIC_URL");
        }

        // If Railway, Render, or another cloud provider supplied a postgresql:// or postgres:// URI
        if (databaseUrl != null && (databaseUrl.startsWith("postgres://") || databaseUrl.startsWith("postgresql://"))) {
            try {
                log.info("Detected cloud DATABASE_URL. Configuring PostgreSQL connection...");
                URI uri = new URI(databaseUrl);
                String host = uri.getHost();
                int port = uri.getPort() != -1 ? uri.getPort() : 5432;
                String path = uri.getPath();
                String dbName = (path != null && path.length() > 1) ? path.substring(1) : "railway";

                String userInfo = uri.getUserInfo();
                String username = null;
                String password = null;
                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    username = parts[0];
                    password = parts[1];
                } else if (userInfo != null) {
                    username = userInfo;
                }

                String jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + dbName;

                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(jdbcUrl);
                if (username != null) config.setUsername(username);
                if (password != null) config.setPassword(password);
                config.setDriverClassName("org.postgresql.Driver");
                config.setMaximumPoolSize(20);
                config.setMinimumIdle(3);
                config.setIdleTimeout(300000);
                config.setConnectionTimeout(20000);

                log.info("PostgreSQL DataSource configured successfully for host={}:{}, database={}", host, port, dbName);
                return new HikariDataSource(config);
            } catch (Exception e) {
                log.warn("Failed to parse DATABASE_URL URI: {}. Falling back to standard environment properties.", e.getMessage());
            }
        }

        // Fallback: standard Spring Boot DataSource properties from environment or application configuration
        HikariConfig config = new HikariConfig();
        String url = env.getProperty("spring.datasource.url");

        if (url == null || url.isBlank()) {
            String pgHost = env.getProperty("PGHOST");
            if (pgHost != null && !pgHost.isBlank()) {
                String pgPort = env.getProperty("PGPORT", "5432");
                String pgDb = env.getProperty("PGDATABASE", "railway");
                url = "jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + pgDb;
                config.setUsername(env.getProperty("PGUSER", "postgres"));
                config.setPassword(env.getProperty("PGPASSWORD", ""));
                config.setDriverClassName("org.postgresql.Driver");
            } else {
                url = "jdbc:h2:mem:winterarcdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
                config.setDriverClassName("org.h2.Driver");
            }
        } else {
            String username = env.getProperty("spring.datasource.username");
            String password = env.getProperty("spring.datasource.password");
            if (username != null) config.setUsername(username);
            if (password != null) config.setPassword(password);
            String driver = env.getProperty("spring.datasource.driver-class-name");
            if (driver != null) config.setDriverClassName(driver);
        }

        config.setJdbcUrl(url);
        return new HikariDataSource(config);
    }
}
