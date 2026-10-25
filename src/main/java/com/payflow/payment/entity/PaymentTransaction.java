
package com.payflow.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "payment_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_reference", nullable = false, unique = true)
    private UUID transactionReference;

    @Column(name = "idempotency_key", unique = true, length = 255)
    private String idempotencyKey;

    @Column(name = "idempotency_request_fingerprint", length = 64)
    private String idempotencyRequestFingerprint;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_account_id", nullable = false)
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_account_id", nullable = false)
    private Account toAccount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public PaymentTransaction(
            Account fromAccount,
            Account toAccount,
            BigDecimal amount,
            String currency,
            String idempotencyKey,
            String idempotencyRequestFingerprint
    ) {
        this.transactionReference = UUID.randomUUID();
        this.idempotencyKey = idempotencyKey;
        this.idempotencyRequestFingerprint = idempotencyRequestFingerprint;
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amount = amount;
        this.currency = currency;
        this.status = TransferStatus.PENDING;
        this.createdAt = OffsetDateTime.now();
    }

    public void markCompleted() {
        this.status = TransferStatus.COMPLETED;
        this.completedAt = OffsetDateTime.now();
    }
}
