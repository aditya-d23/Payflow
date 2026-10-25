package com.payflow.payment;

import com.payflow.payment.dto.TransferRequest;
import com.payflow.payment.dto.TransferResponse;
import com.payflow.payment.entity.Account;
import com.payflow.payment.exception.InsufficientBalanceException;
import com.payflow.payment.repository.AccountRepository;
import com.payflow.payment.service.TransferService;
import com.payflow.payment.security.Caller;
import com.payflow.payment.support.PostgresIntegrationTest;
import com.payflow.payment.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Financial invariants under real PostgreSQL row locking. Every transfer runs
 * on its own thread and therefore in its own transaction and connection.
 */
class TransferConcurrencyPostgresIT extends PostgresIntegrationTest {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void concurrentDebitsNeverOverdrawTheSource() throws Exception {
        account("PG10001", "1000.00");
        account("PG10002", "0.00");

        List<Outcome> outcomes = runConcurrently(25, attempt ->
                transfer("PG10001", "PG10002", "100.00", "overdraw-" + attempt));

        assertThat(succeeded(outcomes)).hasSize(10);
        assertThat(failures(outcomes))
                .hasSize(15)
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOf(InsufficientBalanceException.class));
        assertBalance("PG10001", "0.00");
        assertBalance("PG10002", "1000.00");
        assertThat(transactionCount()).isEqualTo(10);
    }

    @Test
    void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
        account("PG10001", "1000.00");
        account("PG10002", "1000.00");

        List<Outcome> outcomes = runConcurrently(40, attempt -> attempt % 2 == 0
                ? transfer("PG10001", "PG10002", "10.00", "a-to-b-" + attempt)
                : transfer("PG10002", "PG10001", "10.00", "b-to-a-" + attempt));

        // Any deadlock would surface as a PessimisticLockingFailureException.
        assertThat(failures(outcomes)).isEmpty();
        assertBalance("PG10001", "1000.00");
        assertBalance("PG10002", "1000.00");
        assertThat(transactionCount()).isEqualTo(40);
    }

    @Test
    void concurrentDuplicatesOfOneKeyMoveMoneyOnce() throws Exception {
        account("PG10001", "100.00");
        account("PG10002", "0.00");

        List<Outcome> outcomes = runConcurrently(10, attempt ->
                transfer("PG10001", "PG10002", "40.00", "duplicate-key"));

        assertThat(failures(outcomes)).isEmpty();
        Set<UUID> references = succeeded(outcomes).stream()
                .map(TransferResponse::transactionReference)
                .collect(Collectors.toSet());
        assertThat(references).hasSize(1);
        assertBalance("PG10001", "60.00");
        assertBalance("PG10002", "40.00");
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void waitingForALockedAccountTimesOutWithoutSideEffects() throws Exception {
        account("PG10001", "100.00");
        account("PG10002", "0.00");

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = holder.submit(() -> transactionTemplate.executeWithoutResult(
                    status -> {
                        jdbcTemplate.queryForList(
                                "SELECT id FROM accounts WHERE account_number = 'PG10001' FOR UPDATE");
                        locked.countDown();
                        await(release);
                    }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            assertThatThrownBy(() -> transfer("PG10001", "PG10002", "1.00", "timeout-key"))
                    .isInstanceOf(PessimisticLockingFailureException.class);
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertThat(waited).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(6));
            release.countDown();
            holding.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }

        assertBalance("PG10001", "100.00");
        assertThat(transactionCount()).isZero();
        // The key was not consumed, so the client's retry succeeds.
        transfer("PG10001", "PG10002", "1.00", "timeout-key");
        assertBalance("PG10001", "99.00");
    }

    @Test
    void failureWhileCreditingTheReceiverRollsBackTheDebit() {
        account("PG10001", "100.00");
        account("PG10002", "0.00");
        failUpdatesOf("accounts", "NEW.account_number = 'PG10002'");

        assertThatThrownBy(() -> transfer("PG10001", "PG10002", "40.00", "credit-fail"))
                .isInstanceOf(RuntimeException.class);

        assertBalance("PG10001", "100.00");
        assertBalance("PG10002", "0.00");
        assertThat(transactionCount()).isZero();
    }

    @Test
    void failureWhileCompletingTheTransactionRecordRollsBackBothBalances() {
        account("PG10001", "100.00");
        account("PG10002", "0.00");
        failUpdatesOf("payment_transactions", "NEW.status = 'COMPLETED'");

        assertThatThrownBy(() -> transfer("PG10001", "PG10002", "40.00", "record-fail"))
                .isInstanceOf(RuntimeException.class);

        assertBalance("PG10001", "100.00");
        assertBalance("PG10002", "0.00");
        assertThat(transactionCount()).isZero();
    }

    @Test
    void balancesStayConsistentAcrossManyMixedTransfers() throws Exception {
        account("PG10001", "500.00");
        account("PG10002", "500.00");
        account("PG10003", "500.00");
        String[] accounts = {"PG10001", "PG10002", "PG10003"};

        List<Outcome> outcomes = runConcurrently(60, attempt -> transfer(
                accounts[attempt % 3],
                accounts[(attempt + 1 + attempt / 3 % 2) % 3],
                "7.25",
                "mixed-" + attempt));

        assertThat(failures(outcomes)).isEmpty();
        BigDecimal total = jdbcTemplate.queryForObject(
                "SELECT SUM(balance) FROM accounts", BigDecimal.class);
        assertThat(total).isEqualByComparingTo("1500.00");
        Integer negative = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE balance < 0", Integer.class);
        assertThat(negative).isZero();
    }

    private void failUpdatesOf(String table, String condition) {
        String function = "fail_" + table + "_update";
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION %s() RETURNS trigger AS $$
                BEGIN
                    IF %s THEN
                        RAISE EXCEPTION 'forced failure for test';
                    END IF;
                    RETURN NEW;
                END $$ LANGUAGE plpgsql
                """.formatted(function, condition));
        jdbcTemplate.execute("""
                CREATE TRIGGER %s BEFORE UPDATE ON %s
                FOR EACH ROW EXECUTE FUNCTION %s()
                """.formatted(function, table, function));
        cleanups.add("DROP TRIGGER " + function + " ON " + table);
        cleanups.add("DROP FUNCTION " + function + "()");
    }

    private final List<String> cleanups = new ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void dropTestTriggers() {
        cleanups.forEach(jdbcTemplate::execute);
        cleanups.clear();
    }

    private interface Attempt {
        TransferResponse run(int attempt) throws Exception;
    }

    private record Outcome(TransferResponse response, Throwable failure) {}

    private static List<Outcome> runConcurrently(int count, Attempt attempt)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<Outcome> task = () -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new Outcome(attempt.run(index), null);
                    } catch (Throwable failure) {
                        return new Outcome(null, failure);
                    }
                };
                futures.add(executor.submit(task));
            }
            ready.await();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<TransferResponse> succeeded(List<Outcome> outcomes) {
        return outcomes.stream().filter(o -> o.failure() == null)
                .map(Outcome::response).toList();
    }

    private static List<Throwable> failures(List<Outcome> outcomes) {
        return outcomes.stream().map(Outcome::failure)
                .filter(java.util.Objects::nonNull).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void account(String number, String balance) {
        accountRepository.save(new Account(TestJwt.ALICE, number, new BigDecimal(balance), "INR"));
    }

    private TransferResponse transfer(String from, String to, String amount, String key) {
        return transferService.transfer(
                new TransferRequest(from, to, new BigDecimal(amount), "INR"), key,
                Caller.customer(TestJwt.ALICE))
                .transfer();
    }

    private void assertBalance(String accountNumber, String expected) {
        assertThat(accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow().getBalance()).isEqualByComparingTo(expected);
    }

    private long transactionCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_transactions", Long.class);
    }
}
