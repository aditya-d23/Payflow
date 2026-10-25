package com.payflow.payment;

import com.payflow.payment.exception.InvalidRequestException;
import com.payflow.payment.service.CurrencyPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyPolicyTests {

    private final CurrencyPolicy policy = new CurrencyPolicy(List.of("INR", "usd"));

    @Test
    void normalizesCaseAndWhitespace() {
        assertThat(policy.normalizeCurrency(" inr ")).isEqualTo("INR");
        assertThat(policy.normalizeCurrency("USD")).isEqualTo("USD");
    }

    @Test
    void rejectsUnconfiguredOrMissingCurrencies() {
        assertThatThrownBy(() -> policy.normalizeCurrency("EUR"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> policy.normalizeCurrency(null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void equivalentAmountsNormalizeToTheSameStorageValue() {
        assertThat(policy.normalizeAmount(new BigDecimal("40"), "INR"))
                .isEqualTo(new BigDecimal("40.0000"));
        assertThat(policy.normalizeAmount(new BigDecimal("40.00"), "INR"))
                .isEqualTo(new BigDecimal("40.0000"));
        // Trailing zeros beyond the minor unit are not extra precision.
        assertThat(policy.normalizeAmount(new BigDecimal("40.1000"), "INR"))
                .isEqualTo(new BigDecimal("40.1000"));
    }

    @Test
    void rejectsSubMinorUnitAndNonPositiveAmounts() {
        assertThatThrownBy(() -> policy.normalizeAmount(new BigDecimal("0.001"), "INR"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("2 decimal places");
        assertThatThrownBy(() -> policy.normalizeAmount(BigDecimal.ZERO, "INR"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> policy.normalizeAmount(new BigDecimal("-1"), "INR"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> policy.normalizeAmount(null, "INR"))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void refusesToStartWithACurrencyThatCannotBeStored() {
        // XXX ("no currency") has no defined minor unit.
        assertThatThrownBy(() -> new CurrencyPolicy(List.of("XXX")))
                .isInstanceOf(IllegalStateException.class);
        // CLF uses four decimals: the largest the NUMERIC(19,4) columns hold.
        assertThat(new CurrencyPolicy(List.of("CLF")).normalizeCurrency("CLF"))
                .isEqualTo("CLF");
    }
}
