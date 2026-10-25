package com.payflow.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One balanced posting. Rows are append-only (enforced by database triggers);
 * corrections are new postings, never edits.
 */
@Entity
@Immutable
@Table(name = "ledger_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LedgerTransactionType type;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_transaction_id", unique = true)
    private PaymentTransaction paymentTransaction;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 255)
    private String description;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "ledgerTransaction", cascade = CascadeType.PERSIST)
    private List<LedgerEntry> entries = new ArrayList<>();

    public LedgerTransaction(
            LedgerTransactionType type,
            String currency,
            PaymentTransaction paymentTransaction,
            String description) {
        this.reference = UUID.randomUUID();
        this.type = type;
        this.currency = currency;
        this.paymentTransaction = paymentTransaction;
        this.description = description;
        this.createdAt = OffsetDateTime.now();
    }

    void addEntry(LedgerEntry entry) {
        entries.add(entry);
    }
}
