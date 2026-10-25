package com.payflow.payment.service;

import java.math.BigDecimal;

/**
 * A transfer request after validation and normalization. Everything the
 * execution step needs, and nothing it must re-derive.
 */
public record TransferCommand(
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        String requestFingerprint
) {}
