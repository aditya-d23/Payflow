
package com.payflow.payment.service;

import com.payflow.payment.dto.CashMovementRequest;
import com.payflow.payment.dto.TransferRequest;
import com.payflow.payment.dto.TransferResponse;
import com.payflow.payment.entity.PaymentTransaction;
import com.payflow.payment.entity.TransferType;
import com.payflow.payment.exception.ErrorCode;
import com.payflow.payment.exception.IdempotencyKeyConflictException;
import com.payflow.payment.exception.InvalidRequestException;
import com.payflow.payment.exception.TransferNotFoundException;
import com.payflow.payment.repository.PaymentTransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Idempotency front door for transfers. Deliberately not transactional: the
 * execution transaction must be able to fail on the idempotency unique
 * constraint and roll back, after which this class looks up the winner in a
 * fresh transaction.
 */
@Service
public class TransferService {

    // Visible ASCII only: keys appear in logs and must not carry control characters.
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");

    private final PaymentTransactionRepository transactionRepository;
    private final TransferExecutionService transferExecutionService;
    private final CurrencyPolicy currencyPolicy;
    private final SystemAccounts systemAccounts;

    public TransferService(
            PaymentTransactionRepository transactionRepository,
            TransferExecutionService transferExecutionService,
            CurrencyPolicy currencyPolicy,
            SystemAccounts systemAccounts
    ) {
        this.transactionRepository = transactionRepository;
        this.transferExecutionService = transferExecutionService;
        this.currencyPolicy = currencyPolicy;
        this.systemAccounts = systemAccounts;
    }

    public TransferResult transfer(
            TransferRequest request,
            String idempotencyKey
    ) {
        return execute(normalize(TransferType.TRANSFER,
                request.fromAccountNumber(), request.toAccountNumber(),
                request.amount(), request.currency(), idempotencyKey));
    }

    /** Money entering PayFlow: credits the customer, debits the funding account. */
    public TransferResult deposit(CashMovementRequest request, String idempotencyKey) {
        String currency = currencyPolicy.normalizeCurrency(request.currency());
        return execute(normalize(TransferType.DEPOSIT,
                systemAccounts.ensureFundingAccount(currency), request.accountNumber(),
                request.amount(), currency, idempotencyKey));
    }

    /** Money leaving PayFlow: debits the customer, credits the funding account. */
    public TransferResult withdraw(CashMovementRequest request, String idempotencyKey) {
        String currency = currencyPolicy.normalizeCurrency(request.currency());
        return execute(normalize(TransferType.WITHDRAWAL,
                request.accountNumber(), systemAccounts.ensureFundingAccount(currency),
                request.amount(), currency, idempotencyKey));
    }

    private TransferResult execute(TransferCommand command) {
        Optional<TransferResult> replay = findReplay(command);
        if (replay.isPresent()) {
            return replay.get();
        }

        try {
            return new TransferResult(
                    transferExecutionService.execute(command), false);
        } catch (DataIntegrityViolationException exception) {
            // Lost the race for this key: the winner has committed, so replay it.
            // Any other integrity failure has no matching row and is rethrown.
            return findReplay(command).orElseThrow(() -> exception);
        }
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID transactionReference) {
        return transactionRepository.findByTransactionReference(transactionReference)
                .map(TransferResponse::from)
                .orElseThrow(() -> new TransferNotFoundException(
                        "Transfer not found: " + transactionReference));
    }

    private Optional<TransferResult> findReplay(TransferCommand command) {
        return transactionRepository.findByIdempotencyKey(command.idempotencyKey())
                .map(existing -> replay(existing, command.requestFingerprint()));
    }

    private static TransferResult replay(
            PaymentTransaction existing,
            String requestFingerprint
    ) {
        if (!existing.getIdempotencyRequestFingerprint().equals(requestFingerprint)) {
            throw new IdempotencyKeyConflictException(
                    "Idempotency key was already used for a different transfer"
            );
        }
        return new TransferResult(TransferResponse.from(existing), true);
    }

    private TransferCommand normalize(
            TransferType type,
            String fromAccountNumber,
            String toAccountNumber,
            BigDecimal requestedAmount,
            String requestedCurrency,
            String idempotencyKey) {
        String key = normalizeIdempotencyKey(idempotencyKey);
        if (fromAccountNumber == null || toAccountNumber == null) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "Account numbers are required");
        }
        String currency = currencyPolicy.normalizeCurrency(requestedCurrency);
        String from = fromAccountNumber.trim();
        String to = toAccountNumber.trim();
        BigDecimal amount = currencyPolicy.normalizeAmount(requestedAmount, currency);

        // The type is implied by the accounts (deposits and withdrawals always
        // involve a SYS- account, transfers never do), so it is not fingerprinted;
        // this keeps fingerprints of pre-ledger transfers valid.
        return new TransferCommand(
                type, from, to, amount, currency, key,
                fingerprint(from, to, amount.toPlainString(), currency)
        );
    }

    private static String normalizeIdempotencyKey(String idempotencyKey) {
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new InvalidRequestException(
                    ErrorCode.INVALID_IDEMPOTENCY_KEY,
                    "Idempotency-Key must be 1-255 visible ASCII characters"
            );
        }
        return key;
    }

    /**
     * SHA-256 over the normalized fields, NUL-separated so that field
     * boundaries cannot be shifted ("AB"+"C" vs "A"+"BC"). The amount is at
     * storage scale, so 40, 40.0 and 40.0000 fingerprint identically.
     */
    static String fingerprint(String... canonicalFields) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    String.join("\u0000", canonicalFields)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
