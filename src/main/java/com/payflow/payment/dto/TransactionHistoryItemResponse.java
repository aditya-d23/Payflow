package com.payflow.payment.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TransactionHistoryItemResponse(
        UUID transactionReference,
        String direction,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        String currency,
        String status,
        OffsetDateTime createdAt
) {}