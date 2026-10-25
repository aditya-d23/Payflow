package com.payflow.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AssignOwnerRequest(
        @NotBlank(message = "Owner ID is required")
        @Size(max = 255, message = "Owner ID must not exceed 255 characters")
        String ownerId
) {}
