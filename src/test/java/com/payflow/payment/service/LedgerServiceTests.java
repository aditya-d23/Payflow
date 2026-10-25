package com.payflow.payment.service;

import com.payflow.payment.entity.Account;
import com.payflow.payment.entity.EntryDirection;
import com.payflow.payment.service.LedgerService.Posting;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServiceTests {

    private final Account inr1 = new Account("A1", BigDecimal.TEN, "INR");
    private final Account inr2 = new Account("A2", BigDecimal.TEN, "INR");
    private final Account usd = new Account("U1", BigDecimal.TEN, "USD");

    @Test
    void acceptsBalancedMultiLegPostings() {
        assertThatCode(() -> LedgerService.validate("INR", List.of(
                new Posting(inr1, EntryDirection.DEBIT, new BigDecimal("5")),
                new Posting(inr2, EntryDirection.CREDIT, new BigDecimal("3")),
                new Posting(inr2, EntryDirection.CREDIT, new BigDecimal("2")))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnbalancedPostings() {
        assertThatThrownBy(() -> LedgerService.validate("INR", List.of(
                new Posting(inr1, EntryDirection.DEBIT, new BigDecimal("5")),
                new Posting(inr2, EntryDirection.CREDIT, new BigDecimal("4.9999")))))
                .hasMessageContaining("Unbalanced");
    }

    @Test
    void rejectsSingleLegPostings() {
        assertThatThrownBy(() -> LedgerService.validate("INR", List.of(
                new Posting(inr1, EntryDirection.DEBIT, BigDecimal.ONE))))
                .hasMessageContaining("at least two");
    }

    @Test
    void rejectsMixedCurrencies() {
        assertThatThrownBy(() -> LedgerService.validate("INR", List.of(
                new Posting(inr1, EntryDirection.DEBIT, BigDecimal.ONE),
                new Posting(usd, EntryDirection.CREDIT, BigDecimal.ONE))))
                .hasMessageContaining("currency");
    }

    @Test
    void rejectsNonPositiveAmounts() {
        assertThatThrownBy(() -> LedgerService.validate("INR", List.of(
                new Posting(inr1, EntryDirection.DEBIT, BigDecimal.ZERO),
                new Posting(inr2, EntryDirection.CREDIT, BigDecimal.ZERO))))
                .hasMessageContaining("positive");
    }
}
