
package com.payflow.payment.entity;

import com.payflow.payment.exception.ErrorCode;
import com.payflow.payment.exception.InsufficientBalanceException;
import com.payflow.payment.exception.TransferRejectedException;
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    private AccountType type;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public Account(String accountNumber, BigDecimal balance, String currency) {
        this.accountNumber = accountNumber;
        this.balance = balance;
        this.currency = currency;
        this.status = AccountStatus.ACTIVE;
        this.type = AccountType.CUSTOMER;
        this.createdAt = OffsetDateTime.now();
    }

    /**
     * The per-currency counterparty for money entering or leaving PayFlow.
     * Its balance may go negative; customer balances may not.
     */
    public static Account system(String accountNumber, String currency) {
        Account account = new Account(accountNumber, BigDecimal.ZERO, currency);
        account.type = AccountType.SYSTEM;
        return account;
    }

    public boolean isSystem() {
        return type == AccountType.SYSTEM;
    }

    public void ensureActive() {
        if (status != AccountStatus.ACTIVE) {
            throw new TransferRejectedException(
                    ErrorCode.ACCOUNT_NOT_ACTIVE,
                    "Account " + accountNumber + " is " + status
            );
        }
    }

    public void changeStatus(AccountStatus target) {
        if (status == AccountStatus.CLOSED && target != AccountStatus.CLOSED) {
            throw new TransferRejectedException(
                    ErrorCode.INVALID_ACCOUNT_STATE,
                    "A closed account cannot be reopened"
            );
        }
        if (target == AccountStatus.CLOSED && balance.signum() != 0) {
            throw new TransferRejectedException(
                    ErrorCode.INVALID_ACCOUNT_STATE,
                    "Only an account with a zero balance can be closed"
            );
        }
        status = target;
    }

    public void debit(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Debit amount must be positive"
            );
        }
        if (!isSystem() && balance.compareTo(amount) < 0) {
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
