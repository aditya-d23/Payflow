
package com.payflow.payment.service;

import com.payflow.payment.dto.TransferRequest;
import com.payflow.payment.entity.PaymentTransaction;
import com.payflow.payment.exception.IdempotencyKeyConflictException;
import com.payflow.payment.repository.PaymentTransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class TransferService {

    private final PaymentTransactionRepository transactionRepository;
    private final TransferExecutionService transferExecutionService;

    public TransferService(
            PaymentTransactionRepository transactionRepository,
            TransferExecutionService transferExecutionService
    ) {
        this.transactionRepository = transactionRepository;
        this.transferExecutionService = transferExecutionService;
    }

    public PaymentTransaction transfer(
            TransferRequest request,
            String idempotencyKey
    ) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String normalizedCurrency = request.normalizedCurrency();
        String requestFingerprint = fingerprint(request, normalizedCurrency);

        return transactionRepository.findByIdempotencyKey(normalizedKey)
                .map(transaction -> resolveExistingTransaction(
                        transaction,
                        requestFingerprint
                ))
                .orElseGet(() -> executeTransfer(
                        request,
                        normalizedKey,
                        requestFingerprint,
                        normalizedCurrency
                ));
    }

    private PaymentTransaction executeTransfer(
            TransferRequest request,
            String idempotencyKey,
            String requestFingerprint,
            String normalizedCurrency
    ) {
        try {
            return transferExecutionService.execute(
                    request,
                    idempotencyKey,
                    requestFingerprint,
                    normalizedCurrency
            );
        } catch (DataIntegrityViolationException exception) {
            return transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .map(transaction -> resolveExistingTransaction(
                            transaction,
                            requestFingerprint
                    ))
                    .orElseThrow(() -> exception);
        }
    }

    private PaymentTransaction resolveExistingTransaction(
            PaymentTransaction transaction,
            String requestFingerprint
    ) {
        if (!transaction.getIdempotencyRequestFingerprint()
                .equals(requestFingerprint)) {
            throw new IdempotencyKeyConflictException(
                    "Idempotency key was already used for a different transfer"
            );
        }

        return transaction;
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Idempotency-Key header is required"
            );
        }

        String normalizedKey = idempotencyKey.trim();
        if (normalizedKey.length() > 255) {
            throw new IllegalArgumentException(
                    "Idempotency-Key header must not exceed 255 characters"
            );
        }

        return normalizedKey;
    }

    private String fingerprint(
            TransferRequest request,
            String normalizedCurrency
    ) {
        String canonicalRequest = String.join(
                "\u0000",
                request.fromAccountNumber().trim(),
                request.toAccountNumber().trim(),
                request.amount().setScale(4, RoundingMode.UNNECESSARY)
                        .toPlainString(),
                normalizedCurrency
        );

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
