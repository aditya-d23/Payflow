package com.payflow.payment.dto;

import com.payflow.payment.entity.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateAccountStatusRequest(
        @NotNull(message = "Status is required")
        AccountStatus status
) {}
