package com.payflow.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/** A deposit into, or withdrawal from, a customer account. */
public record CashMovementRequest(

        @NotBlank(message = "Account number is required")
        String accountNumber,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.0001", message = "Amount must be positive")
        @Digits(integer = 15, fraction = 4,
                message = "Amount must have at most 15 integer digits and 4 decimal places")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a 3-letter uppercase code")
        String currency
) {}
