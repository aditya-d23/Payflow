package com.payflow.payment;

import com.payflow.payment.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationPostgresIT extends PostgresIntegrationTest {

    @Test
    void applicationSchemaMatchesTheEntityModel() {
        // The context only starts if Flyway applied cleanly and Hibernate's
        // ddl-auto=validate accepted the result.
        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success",
                Integer.class
        );
        assertThat(failed).isZero();
    }

    @Test
    void upgradesADatabaseCreatedAtVersionTwoWithoutLosingData() {
        jdbcTemplate.execute("DROP DATABASE IF EXISTS upgrade_test");
        jdbcTemplate.execute("CREATE DATABASE upgrade_test");
        DataSource upgradeDb = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/upgrade_test$1"),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        JdbcTemplate upgrade = new JdbcTemplate(upgradeDb);

        // Mirror the state of a developer database that stopped at V2.
        flyway(upgradeDb).target("2").load().migrate();
        upgrade.update("""
                INSERT INTO accounts (account_number, balance, currency)
                VALUES ('PF10001', 900.0000, 'INR'), ('PF10002', 100.0000, 'INR')
                """);
        upgrade.update("""
                INSERT INTO payment_transactions
                    (transaction_reference, from_account_id, to_account_id,
                     amount, currency, status)
                VALUES (gen_random_uuid(), 1, 2, 100.0000, 'INR', 'COMPLETED')
                """);

        flyway(upgradeDb).load().migrate();

        assertThat(upgrade.queryForObject(
                "SELECT balance FROM accounts WHERE account_number = 'PF10001'",
                BigDecimal.class)).isEqualByComparingTo("900");
        assertThat(upgrade.queryForObject(
                "SELECT balance FROM accounts WHERE account_number = 'PF10002'",
                BigDecimal.class)).isEqualByComparingTo("100");
        assertThat(upgrade.queryForObject(
                "SELECT COUNT(*) FROM payment_transactions", Integer.class))
                .isEqualTo(1);
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(
            DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration");
    }
}
