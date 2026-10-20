
package com.payflow.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;

public record TransferRequest(

        @NotBlank(message = "Sender account number is required")
        String fromAccountNumber,

        @NotBlank(message = "Receiver account number is required")
        String toAccountNumber,

        @NotNull(message = "Amount is required")
        @DecimalMin(
                value = "0.0001",
                message = "Transfer amount must be positive"
        )
        @Digits(
                integer = 15,
                fraction = 4,
                message = "Amount must have at most 15 integer digits and 4 decimal places"
        )
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        @Pattern(
                regexp = "[A-Z]{3}",
                message = "Currency must be a 3-letter uppercase code"
        )
        String currency
) {}
