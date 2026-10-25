package com.payflow.payment;

import com.payflow.payment.dto.TransferRequest;
import com.payflow.payment.entity.Account;
import com.payflow.payment.exception.IdempotencyKeyConflictException;
import com.payflow.payment.exception.InsufficientBalanceException;
import com.payflow.payment.repository.AccountRepository;
import com.payflow.payment.service.TransferResult;
import com.payflow.payment.service.TransferService;
import com.payflow.payment.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyPostgresIT extends PostgresIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransferService transferService;

    @Test
    void replayIsServedFromTheDatabaseAfterAnApplicationRestart() {
        account("PG40001", "100.00");
        account("PG40002", "0.00");

        TransferResult original;
        try (ConfigurableApplicationContext first = startApplication()) {
            original = first.getBean(TransferService.class)
                    .transfer(request("PG40001", "PG40002", "30.00"), "restart-key");
        }

        TransferResult replay;
        try (ConfigurableApplicationContext second = startApplication()) {
            replay = second.getBean(TransferService.class)
                    .transfer(request("PG40001", "PG40002", "30"), "restart-key");
        }

        assertThat(original.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transfer().transactionReference())
                .isEqualTo(original.transfer().transactionReference());
        assertBalance("PG40001", "70.00");
        assertBalance("PG40002", "30.00");
    }

    @Test
    void concurrentReuseOfAKeyWithADifferentPayloadIsAConflict() throws Exception {
        account("PG40001", "100.00");
        account("PG40002", "0.00");
        account("PG40003", "100.00");
        account("PG40004", "0.00");

        // Disjoint accounts, so neither request waits on the other's row
        // locks; only the unique index on the key orders them.
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Object> a = CompletableFuture.supplyAsync(() -> attempt(
                start, request("PG40001", "PG40002", "10.00"), "shared-key"));
        CompletableFuture<Object> b = CompletableFuture.supplyAsync(() -> attempt(
                start, request("PG40003", "PG40004", "20.00"), "shared-key"));
        start.countDown();

        Object first = a.get(30, TimeUnit.SECONDS);
        Object second = b.get(30, TimeUnit.SECONDS);

        assertThat(new Object[]{first, second})
                .filteredOn(IdempotencyKeyConflictException.class::isInstance)
                .hasSize(1);
        assertThat(new Object[]{first, second})
                .filteredOn(TransferResult.class::isInstance)
                .hasSize(1);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transactions", Integer.class);
        assertThat(rows).isEqualTo(1);
        BigDecimal moved = jdbcTemplate.queryForObject(
                "SELECT SUM(balance) FROM accounts WHERE account_number IN ('PG40002','PG40004')",
                BigDecimal.class);
        assertThat(moved).isIn(new BigDecimal("10.0000"), new BigDecimal("20.0000"));
    }

    @Test
    void aRejectedAttemptDoesNotConsumeTheKey() {
        account("PG40001", "5.00");
        account("PG40002", "0.00");

        assertThatThrownBy(() -> transferService.transfer(
                request("PG40001", "PG40002", "10.00"), "nsf-then-ok"))
                .isInstanceOf(InsufficientBalanceException.class);

        jdbcTemplate.update(
                "UPDATE accounts SET balance = 50 WHERE account_number = 'PG40001'");
        TransferResult retry = transferService.transfer(
                request("PG40001", "PG40002", "10.00"), "nsf-then-ok");

        assertThat(retry.replayed()).isFalse();
        assertBalance("PG40001", "40.00");
    }

    private Object attempt(CountDownLatch start, TransferRequest request, String key) {
        try {
            start.await();
            return transferService.transfer(request, key);
        } catch (Exception exception) {
            return exception;
        }
    }

    private static ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(PaymentServiceApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword(),
                        "spring.main.banner-mode=off")
                .run();
    }

    private void account(String number, String balance) {
        accountRepository.save(new Account(number, new BigDecimal(balance), "INR"));
    }

    private static TransferRequest request(String from, String to, String amount) {
        return new TransferRequest(from, to, new BigDecimal(amount), "INR");
    }

    private void assertBalance(String accountNumber, String expected) {
        assertThat(accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow().getBalance()).isEqualByComparingTo(expected);
    }
}
