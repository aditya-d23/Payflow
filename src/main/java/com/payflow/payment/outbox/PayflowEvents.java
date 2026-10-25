package com.payflow.payment.outbox;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Event payloads. Bump a payload's version when changing it incompatibly.
 * There is no TransferFailed event: rejected transfers roll back completely
 * and leave no durable state to announce.
 */
public final class PayflowEvents {

    public static final String TRANSFER_AGGREGATE = "transfer";
    public static final String ACCOUNT_AGGREGATE = "account";

    private PayflowEvents() {
    }

    public record TransferCompleted(
            UUID transactionReference,
            String type,
            String fromAccountNumber,
            String toAccountNumber,
            BigDecimal amount,
            String currency,
            OffsetDateTime completedAt
    ) {
        public static final String TYPE = "TransferCompleted";
        public static final int VERSION = 1;
    }

    public record AccountCreated(
            String accountNumber,
            String ownerId,
            String currency,
            OffsetDateTime createdAt
    ) {
        public static final String TYPE = "AccountCreated";
        public static final int VERSION = 1;
    }
}
