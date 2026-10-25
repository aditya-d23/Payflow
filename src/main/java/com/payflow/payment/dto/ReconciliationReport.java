package com.payflow.payment.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ReconciliationReport(
        boolean consistent,
        OffsetDateTime checkedAt,
        List<AccountMismatch> accountMismatches,
        List<CurrencyImbalance> currencyImbalances,
        List<UUID> unbalancedLedgerTransactions,
        List<UUID> completedTransfersWithoutLedger
) {
    public record AccountMismatch(
            String accountNumber, BigDecimal balance, BigDecimal ledgerBalance) {}

    public record CurrencyImbalance(String currency, BigDecimal netBalance) {}
}
