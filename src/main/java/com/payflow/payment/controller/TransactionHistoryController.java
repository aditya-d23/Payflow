package com.payflow.payment.controller;

import com.payflow.payment.dto.PageResponse;
import com.payflow.payment.dto.TransactionHistoryItemResponse;
import com.payflow.payment.service.TransactionHistoryService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/accounts")
public class TransactionHistoryController {

    private final TransactionHistoryService transactionHistoryService;

    public TransactionHistoryController(
            TransactionHistoryService transactionHistoryService) {
        this.transactionHistoryService = transactionHistoryService;
    }

    @GetMapping("/{accountNumber}/transactions")
    public PageResponse<TransactionHistoryItemResponse> getTransactions(
            @PathVariable String accountNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        if (page < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Page must be zero or greater"
            );
        }

        if (size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Page size must be between 1 and 100"
            );
        }

        return transactionHistoryService.getTransactions(
                accountNumber, page, size
        );
    }
}