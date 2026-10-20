
package com.payflow.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(

        @NotBlank(message = "Account number is required")
        @Pattern(
                regexp = "[A-Za-z0-9-]{3,30}",
                message = "Account number must be 3-30 characters and contain only letters, numbers, or hyphens"
        )
        String accountNumber,

        @NotBlank(message = "Currency is required")
        @Pattern(
                regexp = "[A-Z]{3}",
                message = "Currency must be a 3-letter uppercase code"
        )
        String currency
) {}
