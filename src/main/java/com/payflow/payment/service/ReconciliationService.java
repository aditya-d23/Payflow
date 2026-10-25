package com.payflow.payment.service;

import com.payflow.payment.dto.ReconciliationReport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Compares mutable balances against the ledger. Runs at REPEATABLE READ so
 * that every check sees the same snapshot even while transfers commit.
 */
@Service
public class ReconciliationService {

    private final JdbcTemplate jdbcTemplate;

    public ReconciliationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport reconcile() {
        List<ReconciliationReport.AccountMismatch> accountMismatches = jdbcTemplate.query("""
                SELECT a.account_number, a.balance,
                       COALESCE(SUM(CASE WHEN e.direction = 'CREDIT'
                                         THEN e.amount ELSE -e.amount END), 0) AS ledger_balance
                FROM accounts a
                LEFT JOIN ledger_entries e ON e.account_id = a.id
                GROUP BY a.id, a.account_number, a.balance
                HAVING a.balance <> COALESCE(SUM(CASE WHEN e.direction = 'CREDIT'
                                                      THEN e.amount ELSE -e.amount END), 0)
                ORDER BY a.account_number
                """, (rs, row) -> new ReconciliationReport.AccountMismatch(
                rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));

        List<ReconciliationReport.CurrencyImbalance> currencyImbalances = jdbcTemplate.query("""
                SELECT currency, SUM(balance)
                FROM accounts
                GROUP BY currency
                HAVING SUM(balance) <> 0
                ORDER BY currency
                """, (rs, row) -> new ReconciliationReport.CurrencyImbalance(
                rs.getString(1), rs.getBigDecimal(2)));

        List<UUID> unbalanced = jdbcTemplate.query("""
                SELECT t.reference
                FROM ledger_transactions t
                LEFT JOIN ledger_entries e ON e.ledger_transaction_id = t.id
                GROUP BY t.id, t.reference
                HAVING COUNT(e.id) < 2
                    OR COALESCE(SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount END), 0)
                    <> COALESCE(SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount END), 0)
                """, (rs, row) -> rs.getObject(1, UUID.class));

        List<UUID> missingLedger = jdbcTemplate.query("""
                SELECT p.transaction_reference
                FROM payment_transactions p
                LEFT JOIN ledger_transactions l ON l.payment_transaction_id = p.id
                WHERE p.status = 'COMPLETED' AND l.id IS NULL
                """, (rs, row) -> rs.getObject(1, UUID.class));

        boolean consistent = accountMismatches.isEmpty() && currencyImbalances.isEmpty()
                && unbalanced.isEmpty() && missingLedger.isEmpty();
        return new ReconciliationReport(consistent, OffsetDateTime.now(),
                accountMismatches, currencyImbalances, unbalanced, missingLedger);
    }
}
