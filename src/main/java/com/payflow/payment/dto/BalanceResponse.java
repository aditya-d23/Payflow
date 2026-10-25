package com.payflow.payment.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record BalanceResponse(
        String accountNumber,
        BigDecimal balance,
        String currency,
        OffsetDateTime asOf
) {}
