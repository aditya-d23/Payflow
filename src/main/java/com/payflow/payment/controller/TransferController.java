
package com.payflow.payment.controller;

import com.payflow.payment.dto.TransferRequest;
import com.payflow.payment.dto.TransferResponse;
import com.payflow.payment.service.TransferResult;
import com.payflow.payment.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * A matching replay returns the original representation with its original
     * 201 status, so a client that lost the first response sees exactly what
     * it would have seen. {@code Idempotent-Replayed: true} marks the replay.
     */
    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request
    ) {
        TransferResult result = transferService.transfer(request, idempotencyKey);
        TransferResponse transfer = result.transfer();
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/transfers/"
                        + transfer.transactionReference()))
                .header(REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(transfer);
    }

    @GetMapping("/{transactionReference}")
    public TransferResponse getTransfer(@PathVariable UUID transactionReference) {
        return transferService.getTransfer(transactionReference);
    }
}
