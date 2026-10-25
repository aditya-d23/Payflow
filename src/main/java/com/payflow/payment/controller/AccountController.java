
package com.payflow.payment.controller;

import com.payflow.payment.dto.AccountResponse;
import com.payflow.payment.dto.AssignOwnerRequest;
import com.payflow.payment.dto.BalanceResponse;
import com.payflow.payment.dto.CreateAccountRequest;
import com.payflow.payment.dto.UpdateAccountStatusRequest;
import com.payflow.payment.security.Caller;
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
            @Valid @RequestBody CreateAccountRequest request,
            Caller caller) {
        AccountResponse account = accountService.createAccount(request, caller);
        return ResponseEntity
                .created(URI.create("/api/v1/accounts/" + account.accountNumber()))
                .body(account);
    }

    @GetMapping("/{accountNumber}")
    public AccountResponse getAccount(
            @PathVariable String accountNumber, Caller caller) {
        return accountService.getAccount(accountNumber, caller);
    }

    @GetMapping("/{accountNumber}/balance")
    public BalanceResponse getBalance(
            @PathVariable String accountNumber, Caller caller) {
        return accountService.getBalance(accountNumber, caller);
    }

    @PatchMapping("/{accountNumber}/status")
    public AccountResponse changeStatus(
            @PathVariable String accountNumber,
            @Valid @RequestBody UpdateAccountStatusRequest request,
            Caller caller) {
        return accountService.changeStatus(accountNumber, request.status(), caller);
    }

    @PutMapping("/{accountNumber}/owner")
    public AccountResponse assignOwner(
            @PathVariable String accountNumber,
            @Valid @RequestBody AssignOwnerRequest request,
            Caller caller) {
        return accountService.assignOwner(accountNumber, request.ownerId(), caller);
    }
}
