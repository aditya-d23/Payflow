package com.payflow.payment.service;

import com.payflow.payment.dto.TransferResponse;

/**
 * @param replayed true when the response was served from an earlier request
 *                 with the same Idempotency-Key, so no money moved this time
 */
public record TransferResult(TransferResponse transfer, boolean replayed) {}
