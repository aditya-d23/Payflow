
package com.payflow.payment.service;

import com.payflow.payment.dto.CashMovementRequest;
import com.payflow.payment.dto.TransferRequest;
import com.payflow.payment.dto.TransferResponse;
import com.payflow.payment.entity.PaymentTransaction;
import com.payflow.payment.entity.TransferType;
import com.payflow.payment.exception.ErrorCode;
import com.payflow.payment.exception.ForbiddenOperationException;
import com.payflow.payment.exception.IdempotencyKeyConflictException;
import com.payflow.payment.exception.InvalidRequestException;
import com.payflow.payment.exception.PayflowException;
import com.payflow.payment.exception.TransferNotFoundException;
import com.payflow.payment.repository.PaymentTransactionRepository;
import com.payflow.payment.security.Caller;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
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
    private final MeterRegistry meterRegistry;

    public TransferService(
            PaymentTransactionRepository transactionRepository,
            TransferExecutionService transferExecutionService,
            CurrencyPolicy currencyPolicy,
            SystemAccounts systemAccounts,
            MeterRegistry meterRegistry
    ) {
        this.transactionRepository = transactionRepository;
        this.transferExecutionService = transferExecutionService;
        this.currencyPolicy = currencyPolicy;
        this.systemAccounts = systemAccounts;
        this.meterRegistry = meterRegistry;
    }

    public TransferResult transfer(
            TransferRequest request,
            String idempotencyKey,
            Caller caller
    ) {
        return execute(normalize(caller, TransferType.TRANSFER,
                request.fromAccountNumber(), request.toAccountNumber(),
                request.amount(), request.currency(), idempotencyKey));
    }

    /** Money entering PayFlow: credits the customer, debits the funding account. */
    public TransferResult deposit(
            CashMovementRequest request, String idempotencyKey, Caller caller) {
        requireAdmin(caller);
        String currency = currencyPolicy.normalizeCurrency(request.currency());
        TransferCommand command = normalize(caller, TransferType.DEPOSIT,
                SystemAccounts.fundingAccountNumber(currency), request.accountNumber(),
                request.amount(), currency, idempotencyKey);
        // Only after validation, so a rejected request never creates an account.
        systemAccounts.ensureFundingAccount(currency);
        return execute(command);
    }

    /** Money leaving PayFlow: debits the customer, credits the funding account. */
    public TransferResult withdraw(
            CashMovementRequest request, String idempotencyKey, Caller caller) {
        requireAdmin(caller);
        String currency = currencyPolicy.normalizeCurrency(request.currency());
        TransferCommand command = normalize(caller, TransferType.WITHDRAWAL,
                request.accountNumber(), SystemAccounts.fundingAccountNumber(currency),
                request.amount(), currency, idempotencyKey);
        systemAccounts.ensureFundingAccount(currency);
        return execute(command);
    }

    /**
     * Records {@code payflow.transfers{type,outcome}} and
     * {@code payflow.transfer.duration{type,outcome}}. Labels are bounded
     * enums (transfer type, error code); never account numbers, keys, or IDs.
     */
    private TransferResult execute(TransferCommand command) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            TransferResult result = executeOnce(command);
            outcome = result.replayed() ? "replayed" : "completed";
            return result;
        } catch (PayflowException exception) {
            outcome = exception.getErrorCode().name().toLowerCase(Locale.ROOT);
            throw exception;
        } catch (PessimisticLockingFailureException exception) {
            outcome = "lock_timeout";
            throw exception;
        } finally {
            String type = command.type().name().toLowerCase(Locale.ROOT);
            meterRegistry.counter("payflow.transfers", "type", type, "outcome", outcome)
                    .increment();
            sample.stop(meterRegistry.timer("payflow.transfer.duration",
                    "type", type, "outcome", outcome));
        }
    }

    private TransferResult executeOnce(TransferCommand command) {
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

    /** Visible to administrators and to the owners of either account. */
    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID transactionReference, Caller caller) {
        return transactionRepository.findByTransactionReference(transactionReference)
                .filter(t -> caller.canView(t.getFromAccount())
                        || caller.canView(t.getToAccount()))
                .map(TransferResponse::from)
                .orElseThrow(() -> new TransferNotFoundException(
                        "Transfer not found: " + transactionReference));
    }

    private Optional<TransferResult> findReplay(TransferCommand command) {
        return transactionRepository.findByIdempotencyKey(
                        command.caller().subject(), command.idempotencyKey())
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

    private static void requireAdmin(Caller caller) {
        if (!caller.admin()) {
            throw new ForbiddenOperationException(
                    "Deposits and withdrawals require the " + Caller.ADMIN_SCOPE + " scope");
        }
    }

    private TransferCommand normalize(
            Caller caller,
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
                caller, type, from, to, amount, currency, key,
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
