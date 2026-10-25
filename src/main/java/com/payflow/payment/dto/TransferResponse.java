package com.payflow.payment.dto;

import com.payflow.payment.entity.PaymentTransaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public view of a transfer. Deliberately excludes account balances and
 * internal identifiers, which the entity graph would otherwise expose.
 */
public record TransferResponse(
        UUID transactionReference,
        String status,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        String currency,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt
) {
    public static TransferResponse from(PaymentTransaction transaction) {
        return new TransferResponse(
                transaction.getTransactionReference(),
                transaction.getStatus().name(),
                transaction.getFromAccount().getAccountNumber(),
                transaction.getToAccount().getAccountNumber(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getCreatedAt(),
                transaction.getCompletedAt()
        );
    }
}
