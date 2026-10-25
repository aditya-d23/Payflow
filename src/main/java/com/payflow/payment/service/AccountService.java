
package com.payflow.payment.service;

import com.payflow.payment.dto.AccountResponse;
import com.payflow.payment.dto.BalanceResponse;
import com.payflow.payment.dto.CreateAccountRequest;
import com.payflow.payment.entity.Account;
import com.payflow.payment.entity.AccountStatus;
import com.payflow.payment.exception.AccountAlreadyExistsException;
import com.payflow.payment.exception.AccountNotFoundException;
import com.payflow.payment.exception.ErrorCode;
import com.payflow.payment.exception.InvalidRequestException;
import com.payflow.payment.repository.AccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final CurrencyPolicy currencyPolicy;

    public AccountService(
            AccountRepository accountRepository,
            CurrencyPolicy currencyPolicy) {
        this.accountRepository = accountRepository;
        this.currencyPolicy = currencyPolicy;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        String accountNumber = request.accountNumber().trim();
        String currency = currencyPolicy.normalizeCurrency(request.currency());
        if (SystemAccounts.isReserved(accountNumber)) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "Account numbers starting with " + SystemAccounts.RESERVED_PREFIX
                            + " are reserved");
        }

        // Fast path for the common case; the unique constraint below is what
        // actually guarantees uniqueness under concurrent creation.
        if (accountRepository.existsByAccountNumber(accountNumber)) {
            throw new AccountAlreadyExistsException("Account number already exists");
        }

        try {
            Account account = accountRepository.saveAndFlush(new Account(
                    accountNumber,
                    BigDecimal.ZERO,
                    currency
            ));
            return AccountResponse.from(account);
        } catch (DataIntegrityViolationException exception) {
            throw new AccountAlreadyExistsException("Account number already exists");
        }
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountNumber) {
        return AccountResponse.from(find(accountNumber));
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountNumber) {
        Account account = find(accountNumber);
        return new BalanceResponse(
                account.getAccountNumber(),
                account.getBalance(),
                account.getCurrency(),
                OffsetDateTime.now()
        );
    }

    @Transactional
    public AccountResponse changeStatus(String accountNumber, AccountStatus status) {
        // Lock so a status change cannot interleave with an in-flight transfer.
        Account account = accountRepository
                .findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(() -> notFound(accountNumber));
        account.changeStatus(status);
        return AccountResponse.from(account);
    }

    private Account find(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> notFound(accountNumber));
    }

    private static AccountNotFoundException notFound(String accountNumber) {
        return new AccountNotFoundException("Account not found: " + accountNumber);
    }
}
