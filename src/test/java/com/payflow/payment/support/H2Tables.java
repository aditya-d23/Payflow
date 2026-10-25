package com.payflow.payment.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** Empties every application table in the shared in-memory H2 test database. */
public final class H2Tables {

    private H2Tables() {
    }

    public static void truncateAll(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.queryForList("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                    """, String.class)
                    .forEach(table -> jdbcTemplate.execute(
                            "TRUNCATE TABLE " + table + " RESTART IDENTITY"));
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }
}
