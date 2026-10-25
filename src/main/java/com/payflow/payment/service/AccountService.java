
package com.payflow.payment.service;

import com.payflow.payment.dto.AccountResponse;
import com.payflow.payment.dto.BalanceResponse;
import com.payflow.payment.dto.CreateAccountRequest;
import com.payflow.payment.entity.Account;
import com.payflow.payment.entity.AccountStatus;
import com.payflow.payment.exception.AccountAlreadyExistsException;
import com.payflow.payment.exception.AccountNotFoundException;
import com.payflow.payment.exception.ErrorCode;
import com.payflow.payment.exception.ForbiddenOperationException;
import com.payflow.payment.exception.InvalidRequestException;
import com.payflow.payment.outbox.OutboxService;
import com.payflow.payment.outbox.PayflowEvents;
import com.payflow.payment.repository.AccountRepository;
import com.payflow.payment.security.Caller;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Accounts a caller may not see are reported as not found rather than
 * forbidden, so account numbers cannot be probed for existence.
 */
@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final CurrencyPolicy currencyPolicy;
    private final OutboxService outboxService;

    public AccountService(
            AccountRepository accountRepository,
            CurrencyPolicy currencyPolicy,
            OutboxService outboxService) {
        this.accountRepository = accountRepository;
        this.currencyPolicy = currencyPolicy;
        this.outboxService = outboxService;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request, Caller caller) {
        String accountNumber = request.accountNumber().trim();
        String currency = currencyPolicy.normalizeCurrency(request.currency());
        if (SystemAccounts.isReserved(accountNumber)) {
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED,
                    "Account numbers starting with " + SystemAccounts.RESERVED_PREFIX
                            + " are reserved");
        }
        String ownerId = request.ownerId() == null ? caller.subject() : request.ownerId();
        if (ownerId.isBlank()) {
            // A blank owner can never match a JWT subject: nobody could use it.
            throw new InvalidRequestException(
                    ErrorCode.VALIDATION_FAILED, "Owner ID must not be blank");
        }
        if (!ownerId.equals(caller.subject()) && !caller.admin()) {
            throw new ForbiddenOperationException(
                    "Only administrators can open accounts for another owner");
        }

        // Fast path for the common case; the unique constraint below is what
        // actually guarantees uniqueness under concurrent creation.
        if (accountRepository.existsByAccountNumber(accountNumber)) {
            throw new AccountAlreadyExistsException("Account number already exists");
        }

        Account account;
        try {
            account = accountRepository.saveAndFlush(new Account(
                    ownerId,
                    accountNumber,
                    BigDecimal.ZERO,
                    currency
            ));
        } catch (DataIntegrityViolationException exception) {
            throw new AccountAlreadyExistsException("Account number already exists");
        }
        outboxService.record(
                PayflowEvents.AccountCreated.TYPE,
                PayflowEvents.AccountCreated.VERSION,
                PayflowEvents.ACCOUNT_AGGREGATE,
                account.getAccountNumber(),
                new PayflowEvents.AccountCreated(
                        account.getAccountNumber(),
                        account.getOwnerId(),
                        account.getCurrency(),
                        account.getCreatedAt()));
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountNumber, Caller caller) {
        return AccountResponse.from(findVisible(accountNumber, caller));
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(String accountNumber, Caller caller) {
        Account account = findVisible(accountNumber, caller);
        return new BalanceResponse(
                account.getAccountNumber(),
                account.getBalance(),
                account.getCurrency(),
                OffsetDateTime.now()
        );
    }

    @Transactional
    public AccountResponse changeStatus(
            String accountNumber, AccountStatus status, Caller caller) {
        requireAdmin(caller);
        // Lock so a status change cannot interleave with an in-flight transfer.
        Account account = lock(accountNumber);
        if (account.isSystem()) {
            // Freezing a funding account would halt every deposit and
            // withdrawal in its currency; that needs a deliberate mechanism.
            throw new InvalidRequestException(
                    ErrorCode.INVALID_ACCOUNT_STATE, "System account status cannot be changed");
        }
        account.changeStatus(status);
        return AccountResponse.from(account);
    }

    @Transactional
    public AccountResponse assignOwner(String accountNumber, String ownerId, Caller caller) {
        requireAdmin(caller);
        Account account = lock(accountNumber);
        account.assignOwner(ownerId);
        return AccountResponse.from(account);
    }

    private Account findVisible(String accountNumber, Caller caller) {
        return accountRepository.findByAccountNumber(accountNumber)
                .filter(caller::canView)
                .orElseThrow(() -> notFound(accountNumber));
    }

    private Account lock(String accountNumber) {
        return accountRepository.findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(() -> notFound(accountNumber));
    }

    private static void requireAdmin(Caller caller) {
        if (!caller.admin()) {
            throw new ForbiddenOperationException(
                    "This operation requires the " + Caller.ADMIN_SCOPE + " scope");
        }
    }

    private static AccountNotFoundException notFound(String accountNumber) {
        return new AccountNotFoundException("Account not found: " + accountNumber);
    }
}
