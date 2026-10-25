package com.payflow.payment;

import com.payflow.payment.dto.CashMovementRequest;
import com.payflow.payment.dto.CreateAccountRequest;
import com.payflow.payment.dto.ReconciliationReport;
import com.payflow.payment.dto.TransferRequest;
import com.payflow.payment.service.AccountService;
import com.payflow.payment.service.ReconciliationService;
import com.payflow.payment.service.TransferService;
import com.payflow.payment.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerPostgresIT extends PostgresIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void aTransferPostsOneBalancedPairOfEntries() {
        open("PG50001");
        open("PG50002");
        deposit("PG50001", "100.00", "fund-1");

        transferService.transfer(new TransferRequest(
                "PG50001", "PG50002", new BigDecimal("40.00"), "INR"), "ledger-pair");

        List<Map<String, Object>> entries = jdbcTemplate.queryForList("""
                SELECT a.account_number, e.direction, e.amount
                FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                JOIN payment_transactions p ON p.id = t.payment_transaction_id
                JOIN accounts a ON a.id = e.account_id
                WHERE p.idempotency_key = 'ledger-pair'
                ORDER BY e.direction
                """);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("account_number", "PG50002")
                .containsEntry("direction", "CREDIT");
        assertThat(entries.get(1)).containsEntry("account_number", "PG50001")
                .containsEntry("direction", "DEBIT");
        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }

    @Test
    void balancesReconcileWithTheLedgerAfterConcurrentMixedActivity() throws Exception {
        String[] accounts = {"PG50001", "PG50002", "PG50003", "PG50004"};
        for (String account : accounts) {
            open(account);
            deposit(account, "500.00", "seed-" + account);
        }

        ExecutorService executor = Executors.newFixedThreadPool(16);
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            int n = i;
            work.add(CompletableFuture.runAsync(() -> {
                try {
                    switch (n % 4) {
                        case 0 -> deposit(accounts[n % 4], "3.00", "dep-" + n);
                        case 1 -> transferService.withdraw(new CashMovementRequest(
                                accounts[n % 4], new BigDecimal("2.00"), "INR"), "wd-" + n);
                        default -> transferService.transfer(new TransferRequest(
                                accounts[n % 4], accounts[(n + 1) % 4],
                                new BigDecimal("7.50"), "INR"), "tr-" + n);
                    }
                } catch (RuntimeException failure) {
                    failures.add(failure);
                }
            }, executor));
        }
        CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).join();
        executor.shutdown();

        // Every account stays well funded, so nothing should have been rejected.
        assertThat(failures).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions", Integer.class))
                .isEqualTo(accounts.length + 120);

        ReconciliationReport report = reconciliationService.reconcile();
        assertThat(report.accountMismatches()).isEmpty();
        assertThat(report.currencyImbalances()).isEmpty();
        assertThat(report.unbalancedLedgerTransactions()).isEmpty();
        assertThat(report.completedTransfersWithoutLedger()).isEmpty();
        assertThat(report.consistent()).isTrue();
    }

    @Test
    void reconciliationDetectsABalanceChangedOutsideTheLedger() {
        open("PG50001");
        deposit("PG50001", "10.00", "fund-1");

        jdbcTemplate.update("UPDATE accounts SET balance = balance + 1 "
                + "WHERE account_number = 'PG50001'");

        ReconciliationReport report = reconciliationService.reconcile();
        assertThat(report.consistent()).isFalse();
        assertThat(report.accountMismatches())
                .singleElement()
                .satisfies(mismatch -> {
                    assertThat(mismatch.accountNumber()).isEqualTo("PG50001");
                    assertThat(mismatch.balance()).isEqualByComparingTo("11.00");
                    assertThat(mismatch.ledgerBalance()).isEqualByComparingTo("10.00");
                });
        assertThat(report.currencyImbalances()).hasSize(1);
    }

    @Test
    void ledgerRowsAreAppendOnly() {
        open("PG50001");
        deposit("PG50001", "10.00", "fund-1");

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE ledger_entries SET amount = 1"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM ledger_entries"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM ledger_transactions"))
                .hasMessageContaining("append-only");
    }

    @Test
    void theDatabaseRejectsAnUnbalancedLedgerTransactionAtCommit() {
        open("PG50001");
        open("PG50002");
        Long a = id("PG50001");
        Long b = id("PG50002");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            Long tx = jdbcTemplate.queryForObject("""
                    INSERT INTO ledger_transactions (reference, type, currency)
                    VALUES (gen_random_uuid(), 'TRANSFER', 'INR') RETURNING id
                    """, Long.class);
            jdbcTemplate.update("""
                    INSERT INTO ledger_entries (ledger_transaction_id, account_id, direction, amount, currency)
                    VALUES (?, ?, 'DEBIT', 10, 'INR'), (?, ?, 'CREDIT', 9, 'INR')
                    """, tx, a, tx, b);
        })).hasMessageContaining("unbalanced");

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void systemAccountsCannotBeUsedForCustomerTransfersOrCreated() {
        open("PG50001");
        deposit("PG50001", "10.00", "fund-1");

        assertThatThrownBy(() -> transferService.transfer(new TransferRequest(
                "PG50001", "SYS-FUNDING-INR", BigDecimal.ONE, "INR"), "to-system"))
                .hasMessageContaining("Account not found");
        assertThatThrownBy(() -> transferService.transfer(new TransferRequest(
                "SYS-FUNDING-INR", "PG50001", BigDecimal.ONE, "INR"), "from-system"))
                .hasMessageContaining("Account not found");
        assertThatThrownBy(() -> accountService.createAccount(
                new CreateAccountRequest("sys-mine", "INR")))
                .hasMessageContaining("reserved");
    }

    private void open(String accountNumber) {
        accountService.createAccount(new CreateAccountRequest(accountNumber, "INR"));
    }

    private void deposit(String accountNumber, String amount, String key) {
        transferService.deposit(new CashMovementRequest(
                accountNumber, new BigDecimal(amount), "INR"), key);
    }

    private Long id(String accountNumber) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM accounts WHERE account_number = ?", Long.class, accountNumber);
    }
}
