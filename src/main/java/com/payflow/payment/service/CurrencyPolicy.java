package com.payflow.payment.service;

import com.payflow.payment.exception.ErrorCode;
import com.payflow.payment.exception.InvalidRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which ISO 4217 currencies PayFlow accepts and how many minor units
 * an amount in each may carry. Amounts are stored at scale 4 (NUMERIC(19,4)),
 * so only currencies with at most four fraction digits are allowed.
 */
@Component
public class CurrencyPolicy {

    static final int STORAGE_SCALE = 4;

    private final Set<String> supported;

    public CurrencyPolicy(
            @Value("${payflow.currencies.supported:INR,USD,EUR,GBP}")
            List<String> supported) {
        this.supported = supported.stream()
                .map(code -> code.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        for (String code : this.supported) {
            int digits = Currency.getInstance(code).getDefaultFractionDigits();
            if (digits < 0 || digits > STORAGE_SCALE) {
                throw new IllegalStateException(
                        "Currency " + code + " cannot be stored at scale "
                                + STORAGE_SCALE);
            }
        }
    }

    public String normalizeCurrency(String currency) {
        String normalized = currency == null
                ? ""
                : currency.trim().toUpperCase(Locale.ROOT);
        if (!supported.contains(normalized)) {
            throw new InvalidRequestException(
                    ErrorCode.UNSUPPORTED_CURRENCY,
                    "Currency is not supported: " + normalized
            );
        }
        return normalized;
    }

    /**
     * Validates a positive amount against the currency's minor units and
     * returns it at storage scale, so 40, 40.0 and 40.00 are the same value.
     */
    public BigDecimal normalizeAmount(BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidRequestException(
                    ErrorCode.INVALID_AMOUNT, "Amount must be positive");
        }
        int allowed = Currency.getInstance(currency).getDefaultFractionDigits();
        if (amount.stripTrailingZeros().scale() > allowed) {
            throw new InvalidRequestException(
                    ErrorCode.INVALID_AMOUNT,
                    currency + " amounts allow at most " + allowed
                            + " decimal places"
            );
        }
        // Lossless: the scale check above guarantees no rounding is needed.
        return amount.setScale(STORAGE_SCALE);
    }
}
