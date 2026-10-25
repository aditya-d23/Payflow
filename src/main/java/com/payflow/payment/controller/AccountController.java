
package com.payflow.payment.controller;

import com.payflow.payment.dto.AccountResponse;
import com.payflow.payment.dto.BalanceResponse;
import com.payflow.payment.dto.CreateAccountRequest;
import com.payflow.payment.dto.UpdateAccountStatusRequest;
import com.payflow.payment.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request) {
        AccountResponse account = accountService.createAccount(request);
        return ResponseEntity
                .created(URI.create("/api/v1/accounts/" + account.accountNumber()))
                .body(account);
    }

    @GetMapping("/{accountNumber}")
    public AccountResponse getAccount(
            @PathVariable String accountNumber) {
        return accountService.getAccount(accountNumber);
    }

    @GetMapping("/{accountNumber}/balance")
    public BalanceResponse getBalance(
            @PathVariable String accountNumber) {
        return accountService.getBalance(accountNumber);
    }

    @PatchMapping("/{accountNumber}/status")
    public AccountResponse changeStatus(
            @PathVariable String accountNumber,
            @Valid @RequestBody UpdateAccountStatusRequest request) {
        return accountService.changeStatus(accountNumber, request.status());
    }
}
