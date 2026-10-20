
package com.payflow.payment.controller;

import com.payflow.payment.dto.CreateAccountRequest;
import com.payflow.payment.entity.Account;
import com.payflow.payment.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Account createAccount(
            @Valid @RequestBody CreateAccountRequest request) {
        return accountService.createAccount(request);
    }

    @GetMapping("/{accountNumber}")
    public Account getAccount(
            @PathVariable String accountNumber) {
        return accountService.getAccount(accountNumber);
    }
}
