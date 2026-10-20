
package com.payflow.payment.controller;

import com.payflow.payment.dto.TransferRequest;
import com.payflow.payment.entity.PaymentTransaction;
import com.payflow.payment.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentTransaction transfer(
            @Valid @RequestBody TransferRequest request
    ) {
        return transferService.transfer(request);
    }
}
