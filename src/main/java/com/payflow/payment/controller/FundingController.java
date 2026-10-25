package com.payflow.payment.controller;

import com.payflow.payment.dto.CashMovementRequest;
import com.payflow.payment.dto.TransferResponse;
import com.payflow.payment.security.Caller;
import com.payflow.payment.service.TransferResult;
import com.payflow.payment.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Money entering or leaving PayFlow. These are operator actions (for example,
 * settling a bank transfer), recorded against the currency's funding account.
 */
@RestController
@RequestMapping("/api/v1")
public class FundingController {

    private final TransferService transferService;

    public FundingController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/deposits")
    public ResponseEntity<TransferResponse> deposit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CashMovementRequest request,
            Caller caller) {
        return created(transferService.deposit(request, idempotencyKey, caller));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<TransferResponse> withdraw(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CashMovementRequest request,
            Caller caller) {
        return created(transferService.withdraw(request, idempotencyKey, caller));
    }

    private static ResponseEntity<TransferResponse> created(TransferResult result) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/transfers/"
                        + result.transfer().transactionReference()))
                .header(TransferController.REPLAYED_HEADER,
                        String.valueOf(result.replayed()))
                .body(result.transfer());
    }
}
