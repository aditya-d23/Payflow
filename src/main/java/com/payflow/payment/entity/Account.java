
package com.payflow.payment.entity;

import com.payflow.payment.exception.InsufficientBalanceException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, unique = true, length = 30)
    private String accountNumber;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Account(String accountNumber, BigDecimal balance, String currency) {
        this.accountNumber = accountNumber;
        this.balance = balance;
        this.currency = currency;
        this.status = "ACTIVE";
        this.createdAt = OffsetDateTime.now();
    }
    public void debit(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Debit amount must be positive"
            );
        }
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(
                    "Insufficient account balance"
            );
        }

        balance = balance.subtract(amount);
    }
    public void credit(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Credit amount must be positive"
            );
        }

        balance = balance.add(amount);
    }
}
