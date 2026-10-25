package com.payflow.payment.dto;

import com.payflow.payment.entity.Account;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record AccountResponse(
        String accountNumber,
        String ownerId,
        String currency,
        String status,
        BigDecimal balance,
        OffsetDateTime createdAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getAccountNumber(),
                account.getOwnerId(),
                account.getCurrency(),
                account.getStatus().name(),
                account.getBalance(),
                account.getCreatedAt()
        );
    }
}
