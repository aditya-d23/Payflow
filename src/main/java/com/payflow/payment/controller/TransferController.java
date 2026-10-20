
package com.payflow.payment.controller;

import com.payflow.payment.dto.TransferRequest;
import com.payflow.payment.entity.PaymentTransaction;
import com.payflow.payment.service.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentTransaction transfer(
            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "Idempotency-Key header is required")
            @Size(
                    max = 255,
                    message = "Idempotency-Key header must not exceed 255 characters"
            )
            String idempotencyKey,
            @Valid @RequestBody TransferRequest request
    ) {
        return transferService.transfer(request, idempotencyKey);
    }
}
