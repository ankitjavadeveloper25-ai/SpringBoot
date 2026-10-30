package com.winterarc.server.config;

/**
 * Immutable value object holding parsed database connection parameters.
 * Guarantees that the JDBC URL never contains embedded credentials,
 * which cause org.postgresql.Driver to reject the connection string.
 */
public record DatabaseConnectionInfo(
        String jdbcUrl,
        String username,
        String password,
        String driverClassName,
        String host,
        int port,
        String databaseName
) {
    public boolean isPostgres() {
        return "org.postgresql.Driver".equals(driverClassName);
    }

    public boolean isH2() {
        return "org.h2.Driver".equals(driverClassName);
    }

    public boolean isLocalhost() {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
    }

    public String maskedPassword() {
        return (password != null && !password.isEmpty()) ? "******" : "(empty)";
    }
}
