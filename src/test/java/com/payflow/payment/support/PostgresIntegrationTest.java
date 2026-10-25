package com.payflow.payment.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

/**
 * Runs the full application against a real PostgreSQL with Flyway migrations
 * and {@code ddl-auto=validate}. One container is shared by every subclass in
 * the JVM so the Spring context cache stays effective.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @AfterEach
    void truncateApplicationTables() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public'
                  AND tablename <> 'flyway_schema_history'
                """, String.class);
        if (!tables.isEmpty()) {
            jdbcTemplate.execute("TRUNCATE " + String.join(", ", tables)
                    + " RESTART IDENTITY CASCADE");
        }
    }
}
