package com.payflow.payment.service;

import com.payflow.payment.entity.TransferType;
import com.payflow.payment.security.Caller;

import java.math.BigDecimal;

/**
 * A transfer request after validation and normalization. Everything the
 * execution step needs, and nothing it must re-derive.
 */
public record TransferCommand(
        Caller caller,
        TransferType type,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        String requestFingerprint
) {}
