
package com.payflow.payment.service;

import com.payflow.payment.dto.CreateAccountRequest;
import com.payflow.payment.entity.Account;
import com.payflow.payment.exception.AccountAlreadyExistsException;
import com.payflow.payment.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.payflow.payment.exception.AccountNotFoundException;
import java.math.BigDecimal;
import java.util.Locale;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public Account createAccount(CreateAccountRequest request) {
        String accountNumber = request.accountNumber();
        String currency = request.currency().toUpperCase(Locale.ROOT);

        if (accountRepository.existsByAccountNumber(accountNumber)) {
            throw new AccountAlreadyExistsException("Account number already exists");
        }

        Account account = new Account(
                accountNumber,
                BigDecimal.ZERO,
                currency
        );

        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public Account getAccount(String accountNumber) throws AccountNotFoundException {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() ->
                        new AccountNotFoundException(
                                "Account not found: " + accountNumber
                        )
                );
    }
}
